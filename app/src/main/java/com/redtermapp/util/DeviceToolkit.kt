package com.redtermapp.util

import android.content.Context
import java.io.File

/**
 * The optional toolkit behind the device shell: a dynamic busybox, a
 * dynamic bash, and the app's own SSH client (ssh, ssh-keygen), per ABI,
 * extracted into app storage.
 *
 * The device shell works without it — `/system/bin/sh` is always there.
 * The toolkit is what upgrades that to bash with awk, find, ps and the
 * rest, and it is installed the way the ssh binaries are: extracted from
 * assets, best-effort, and never allowed to fail the session. A device
 * whose ABI is not shipped gets the plain shell and says so in the log,
 * which is the same shape as sftp and scp: optional binaries that
 * extract best-effort and can never fail the launch.
 *
 * busybox and bash are dynamic bionic executables, never static: a static
 * NDK build aborts on start with the underaligned-TLS error, and the
 * build that produces them is checked for both properties before it is
 * allowed into the assets. The SSH client is different: it is the app's
 * own self-contained binary, shipped under `assets/ssh/` and copied in
 * unchanged, so it needs no libraries of its own.
 */
object DeviceToolkit {

    /**
     * Bumped when the assets are rebuilt; the stamp is `<arch>:<version>`.
     * v2: the extraction now sets the exec bit on the binaries (v1 shipped
     * them 0644 and proot refused them); an existing v1 stamp must not
     * short-circuit the re-extraction.
     * v3: the toolkit now also carries the app's own SSH client (ssh and
     * ssh-keygen, extracted from the same assets the SSH feature uses), so
     * the shell can reach it; a v2 stamp must re-extract to gain them.
     */
    const val TOOLKIT_VERSION = 3

    private const val STAMP_NAME = "version"

    /**
     * The SSH client the shell carries: the very binaries the SSH feature
     * ships under `assets/ssh/<arch>/`. They land in the toolkit directory,
     * which the launcher already puts on PATH, so `ssh` and `ssh-keygen`
     * become ordinary shell commands. Extracted best-effort (see [extract]):
     * they are a bonus, and the toolkit still provides the shell without
     * them. The list mirrors [SshClient.REQUIRED_PROGRAMS] so the shell and
     * the SSH feature cannot drift apart.
     */
    val SSH_TOOLS = listOf("ssh", "ssh-keygen")

    /**
     * The two ABI vocabularies disagree, and the failure is a missing file:
     * assets use the build-openssh names, Build.SUPPORTED_ABIS the NDK
     * ones. Mapped per [com.redtermapp.mosh.MoshClient.assetArch] — that
     * function is the one tested over every shipped ABI, and this one is
     * required to agree with it, which is what its test asserts.
     */
    fun assetArch(abi: String?): String? = when (abi) {
        "arm64-v8a", "aarch64" -> "aarch64"
        "armeabi-v7a", "armv7a", "arm" -> "armv7a"
        "x86_64" -> "x86_64"
        "x86", "i686" -> "i686"
        else -> null
    }

    /** Where the toolkit lives on disk, or null for an ABI with no assets. */
    fun dir(context: Context): File? = assetArch(
        com.redtermapp.distro.ProotLaunch.deviceAbi()
    )?.let { File(context.filesDir, "device-toolkit/$it") }

    /**
     * The extracted directory when it is current for this device, otherwise
     * null. The stamp carries the ABI as well as the version: re-extracting
     * for the same version on a device that changed ABI would otherwise
     * run the other architecture's binaries.
     */
    fun isReady(context: Context): File? {
        val dir = dir(context) ?: return null
        val stamp = File(dir, STAMP_NAME)
        if (!stamp.exists()) return null
        val expected = "${assetArch(com.redtermapp.distro.ProotLaunch.deviceAbi())}:$TOOLKIT_VERSION"
        return if (stamp.readText().trim() == expected) dir else null
    }

    /**
     * Extracts the assets once, best-effort.
     *
     * [Context.getAssets] is not injectable, so the testable core is
     * [extract]; this wrapper is the only part that touches the platform
     * and it is what keeps a failure from reaching the session: anything
     * wrong here logs and returns null, and the shell falls back to the
     * system one.
     */
    fun ensureInstalled(context: Context): File? {
        val dir = dir(context) ?: return null
        if (isReady(context) != null) return dir
        return try {
            extract(context, com.redtermapp.distro.ProotLaunch.deviceAbi(), dir)
        } catch (e: Exception) {
            AppLog.w(context, "device-toolkit", "extraction failed, using the system shell: ${e.message}")
            null
        }
    }

    /** Copies the toolkit binaries (busybox, bash) and the app's SSH client
      *  for [arch] out of the assets into [into], and links the busybox
      *  applets next to it so the extra tools are usable by name.
     *
     * `busybox` is a single mult-call binary: `awk`, `find`, `ps` and the
     * rest are applets it dispatches on `argv[0]`. Shipping only the binary
     * means none of them resolve as a bare command, so the extraction also
     * lays down one symlink per applet. The links point into app storage
     * and are only exec'd *inside* the proot session, where the loader
     * carries them — outside it, app storage is noexec and a bare command
     * could not run anyway, which is why the fallback shell never advertises
     * them.
     */
    internal fun extract(context: Context, arch: String, into: File): File? {
        val assetArch = assetArch(arch) ?: return null
        into.mkdirs()
        for (name in listOf("busybox", "bash")) {
            val bin = File(into, name)
            context.assets.open("device-shell/$assetArch/$name").use { input ->
                bin.outputStream().use { output -> input.copyTo(output) }
            }
            // The copy lands 0644: asset extraction does not carry the APK's
            // mode, and proot's executability check rejects a non-+x binary
            // with "is not executable" before the loader ever runs. This is
            // the line the ssh install has and the toolkit build once lacked.
            bin.setExecutable(true, true)
        }
        // The app's own SSH client, a bonus tool for the shell: copied in
        // best-effort from the assets the SSH feature already ships, so its
        // absence never sinks a toolkit that busybox and bash still provide.
        // ssh and ssh-keygen are self-contained, so a plain copy is all they
        // need; they run under proot's loader exactly like the rest.
        for (name in SSH_TOOLS) {
            val bin = File(into, name)
            try {
                context.assets.open("ssh/$assetArch/$name").use { input ->
                    bin.outputStream().use { output -> input.copyTo(output) }
                }
                bin.setExecutable(true, true)
            } catch (e: Exception) {
                AppLog.w(context, "device-toolkit", "optional ssh tool $name unavailable: ${e.message}")
            }
        }
        linkApplets(context, into)
        File(into, STAMP_NAME).writeText("${assetArch}:$TOOLKIT_VERSION")
        AppLog.i(context, "device-toolkit", "installed $assetArch into ${into.absolutePath}")
        return into
    }

    /** The applets worth a bare name in a phone shell, each a link to `busybox`. */
    private val APPLETS = listOf(
        "awk", "basename", "cat", "chmod", "cp", "cut", "date", "dd", "df", "du",
        "echo", "env", "expr", "find", "grep", "head", "id", "kill", "ln", "ls",
        "md5sum", "mkdir", "more", "mount", "mv", "ps", "pwd", "rm", "rmdir",
        "sed", "sh", "sleep", "sort", "stat", "tail", "tar", "tee", "timeout",
        "touch", "tr", "true", "uname", "wc", "which", "xargs", "zcat",
        "gzip", "gunzip", "bzip2", "less"
    )

    /**
     * Creates one symlink per applet, best-effort: a filesystem that refuses
     * a link loses only the bare name, not the binary, so a failure is
     * logged and not propagated out of the extraction.
     */
    private fun linkApplets(context: Context, into: File) {
        val target = File(into, "busybox").absolutePath
        for (applet in APPLETS) {
            val link = File(into, applet)
            if (link.exists()) link.delete()
            try {
                android.system.Os.symlink(target, link.absolutePath)
            } catch (e: Exception) {
                AppLog.w(context, "device-toolkit", "applet link $applet: ${e.message}")
            }
        }
    }

    /**
     * The launcher for a toolkit session: proot's loader execs bash out of
     * app storage, which is noexec on Android 12+.
     *
     * It mirrors the proven distro launcher deliberately, because that one is
     * what has been measured on a device: a `#!/system/bin/sh` script run as
     * `sh -c <script>` with an empty environment, where the script exports
     * *every* variable the child needs. A controlled absolute `PATH` (not
     * `$PATH:...`, which is empty under that env) is what lets the session
     * keep finding the device's own commands *and* the toolkit's. No `PS1` is
     * set: the bundled bash shows its own default prompt (`bash-5.2$ `), which
     * is more transparent than a marker and already tells the user this is the
     * toolkit's bash rather than the fallback system sh (a different prompt).
     *
     * What is deliberately *not* in the proot invocation, against the
     * distro one: no `-0` (faking uid 0 in the user's own filesystem
     * changes what ownership checks report and would read like root the app
     * does not have), no `--link2symlink` (that would turn the device's
     * real hard links into copies-in-waiting), and no binds (with root the
     * real filesystem, `/dev` and `/proc` are already the right ones).
     *
     * `PROOT_TMP_DIR` is the app's own directory, not the guest's `/tmp`:
     * the guest root is the real filesystem, and its `/tmp` is
      * `/data/local/tmp`, which the app's uid cannot write to.
      *
      * The final line runs proot in the *foreground*, not `exec`'d: a failed
     * `exec` is a dead session that prints nothing, which is exactly the
     * failure nobody can fix. A failed foreground call leaves this shell
     * free to say so and hand the user a working system shell, and proot's
     * own stderr reaches the terminal — where a failure report belongs.
     */
    fun launcherScript(
        nativeLibDir: String,
        toolkitDir: File,
        cwd: String,
        prootTmpDir: File,
    ): String {
        val prootBin = "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libproot-loader.so"
        val toolkit = toolkitDir.absolutePath
        // Absolute, not `$PATH:...`: the launcher runs under an empty
        // environment (the distro pattern), so an append would be empty and
        // the child would lose the device's own commands. The toolkit
        // directory comes last so a device command always wins a name
        // collision.
        val path = "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/sbin:/bin:/usr/bin:/usr/sbin:$toolkit"
        // Deliberately no PS1: the bundled bash shows its own default prompt
        // (e.g. `bash-5.2$ `), which is more transparent than a `[shell]`
        // marker -- it names the actual shell and version in use. That already
        // distinguishes the toolkit's bash from the fallback system sh below
        // (a different default prompt), so overriding it buys nothing and would
        // also shadow a prompt the user sets in their own ~/.bashrc, which a
        // real bash honours.
        return """#!/system/bin/sh
umask 022
export HOME="$cwd"
export PATH=$path
export TERM=xterm-256color
export PROOT_LOADER="$prootLoader"
export PROOT_TMP_DIR="$prootTmpDir"
mkdir -p "$prootTmpDir"
if "$prootBin" -L -r / -w "$cwd" --kill-on-exit "$toolkit/bash"; then
  exit 0
fi
echo "proot/bash did not start (its trace is above); falling back to the system shell"
exec /system/bin/sh -i
""".trimIndent()
    }

    /**
     * Writes [launcherScript] to the per-session file the shell reads.
     * One file per session, the rule from the distro launchers: a fixed
     * name let a second session overwrite the script the first was still
     * reading.
     */
    fun writeLauncher(context: Context, sessionId: String, cwd: String): File? {
        val toolkit = isReady(context) ?: ensureInstalled(context) ?: return null
        val prootTmp = File(context.filesDir, "device-toolkit-tmp")
        prootTmp.mkdirs()
        val out = File(context.filesDir, "device-launch-$sessionId.sh")
        out.writeText(
            launcherScript(context.applicationInfo.nativeLibraryDir, toolkit, cwd, prootTmp)
        )
        out.setExecutable(true, true)
        return out
    }
}
