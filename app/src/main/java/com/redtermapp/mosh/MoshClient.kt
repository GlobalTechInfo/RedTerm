package com.redtermapp.mosh

import android.content.Context
import androidx.core.content.edit
import android.os.Build
import android.util.Log
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import java.io.File

/**
 * Installs and launches the mosh client executable.
 *
 * This is an executable rather than a library call because `TerminalSession` is `final`
 * and its constructor takes a shell *path* — it builds the subprocess itself through
 * `JNI.createSubprocess`, so a mosh session that is not a process cannot be a session at
 * all. The app already ships cross-built binaries as per-ABI assets and runs them through
 * proot's `-L` loader, and mosh takes that same route, which means the session machinery,
 * the emulator, split panes, resize and the session list need no change whatsoever.
 *
 * **The session key goes in the environment, never on a command line.** The launcher
 * script is written into app storage and logged on the way past, so an `MOSH_KEY=… ssh`
 * prefix or an argument would put the key on disk in exactly the place it was promised not
 * to be. This is `SshAskpass`'s rule arriving a second time, in a different subsystem.
 */
object MoshClient {

    private const val TAG = "mosh"

    /** Bumped whenever the shipped binary changes, so an upgrade re-extracts.
     * -2: the client acts on mosh's own shutdown protocol, so `exit` ends the
     * session instead of leaving it at `logout`. */
    const val VERSION = "1.4.0-2"

    private const val PREFS = "mosh_client"
    private const val KEY_STAMP = "ready"

    /** The asset directory name, which is build-openssh.sh's naming, not jniLibs'. */
    private const val PROGRAM = "redterm-mosh"

    /**
     * Where the client lives, as the guest sees it.
     *
     * One constant for the install path *and* the launcher's command, because those two
     * being different is a 127 with nothing to explain it. They were: the client was
     * extracted to `rootfs/bin/` beside ssh, and the launcher asked for
     * `rootfs/root/bin/`, because inside the rootfs `/root` is the ssh client's *home*
     * rather than a directory to put programs in. The session then exited 127 on every
     * mosh server on every device, with the bootstrap reporting a perfectly healthy
     * `started a mosh session` line just before it.
     */
    internal const val PROGRAM_GUEST_PATH = "/bin/$PROGRAM"

    /** The file [ensureInstalled] writes, given the rootfs directory. */
    internal fun installFile(rootfs: File): File = File(rootfs, PROGRAM_GUEST_PATH)

    /** The command the launcher runs. Same string as [PROGRAM_GUEST_PATH]. */
    internal fun launcherTarget(): String = ProotLaunch.quoteForShell(PROGRAM_GUEST_PATH)

    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The reason mosh cannot be used on this device, or null when it can. */
    @Volatile
    var installError: String? = null
        private set

    /**
     * The asset directory for this device.
     *
     * `Build.SUPPORTED_ABIS` speaks jniLibs (`arm64-v8a`, `armeabi-v7a`, `x86_64`,
     * `x86`) and the assets speak build-openssh.sh's names (`aarch64`, `armv7a`,
     * `x86_64`, `i686`). Using the ABI name verbatim means opening
     * `assets/mosh/arm64-v8a/…`, which does not exist — and `assets.open` on a missing
     * path throws, so the symptom is "could not install the mosh client" on every
     * device, including ones the binary was built for.
     */
    private fun archName(): String? = assetArch(Build.SUPPORTED_ABIS.firstOrNull())

    /**
     * The asset directory name for a device ABI.
     *
     * A pure function so it can be tested against every ABI the app ships, because the
     * two vocabularies differ in every single case and the failure is a missing asset
     * rather than an error: the binary is built, it is in the APK, and every device says
     * the client could not be installed.
     */
    internal fun assetArch(abi: String?): String? = when (abi) {
        "arm64-v8a", "aarch64" -> "aarch64"
        "armeabi-v7a", "armv7a", "arm" -> "armv7a"
        "x86_64" -> "x86_64"
        "x86", "i686" -> "i686"
        else -> null
    }

    fun binary(context: Context): File = installFile(SshClient.rootfs(context))

    /**
     * Copies the executable out of the assets for this device's ABI, once per version.
     *
     * Re-extraction is keyed on the version as well as the ABI: an upgrade that leaves a
     * stale binary in place would run a session client from before a fix, which is the
     * kind of bug that reads as "mosh is unreliable" rather than "mosh is two releases
     * old".
     */
    fun ensureInstalled(context: Context): Boolean {
        if (installing.get()) return true
        if (!installing.compareAndSet(false, true)) return true
        return try {
            ensureInstalledNow(context)
        } finally {
            installing.set(false)
        }
    }

    private fun ensureInstalledNow(context: Context): Boolean {
        val arch = archName()
        if (arch == null) {
            installError = "this device reports no supported ABI"
            AppLog.e(context, TAG, installError!!)
            return false
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = "$arch:$VERSION"
        val out = binary(context)
        if (prefs.getString(KEY_STAMP, null) == stamp && out.length() > 0) {
            return true
        }

        // A newer ABI has to remove the older one: `Build.SUPPORTED_ABIS[0]` is the
        // preference order, and the alternative is a binary that matches no shipped
        // ABI being left in the rootfs where nothing can run it.
        val asset = "mosh/$arch/$PROGRAM"
        return try {
            val parent = out.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                installError = "could not create $parent"
                AppLog.e(context, TAG, installError!!)
                return false
            }
            // Through a temporary name: a partially-copied executable that is executable
            // and truncated is a session that fails in a way that names nothing.
            val staging = File(parent, "$PROGRAM.staging")
            context.assets.open(asset).use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (staging.length() == 0L) {
                installError = "the packaged client is empty"
                AppLog.e(context, TAG, installError!!)
                staging.delete()
                return false
            }
            if (!staging.renameTo(out)) {
                staging.copyTo(out, overwrite = true)
                staging.delete()
            }
            out.setExecutable(true, true)
            prefs.edit { putString(KEY_STAMP, stamp) }
            AppLog.d(context, TAG, "installed mosh client for $arch (${out.length()} bytes)")
            true
        } catch (e: Exception) {
            installError = "could not install the mosh client: ${e.message}"
            AppLog.e(context, TAG, installError!!)
            Log.w(TAG, "mosh client install failed", e)
            false
        }
    }

    /**
     * Writes the launcher script that runs the client under proot, and returns its path.
     *
     * The command inside the launcher is **only** the binary's path. Everything the
     * session needs — address, port, key, size — is passed through [environmentFor] as
     * process environment, because this script is a file in app storage that gets logged
     * when it runs.
     */
    fun launcher(context: Context, scriptName: String): String? {
        if (!ensureInstalled(context)) return null
        return try {
            ProotLaunch.writeLauncher(
                context = context,
                rootfsDir = SshClient.rootfs(context),
                startInner = "/root",
                command = launcherTarget(),
                scriptName = scriptName,
                mergeStderr = true,
            ).absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "could not write the mosh launcher", e)
            null
        }
    }

    /**
     * The session's environment, including the secret.
     *
     * Returned as a list rather than logged: this is the one place `MOSH_KEY` is written
     * down in the app, and `TerminalSession` passes it straight to the subprocess.
     */
    fun environmentFor(context: Context, endpoint: MoshBootstrap.Endpoint, cols: Int, rows: Int): Array<String> {
        val home = SshClient.homeDir(context)
        return arrayOf(
            "HOME=${home.absolutePath}",
            "TERM=xterm-256color",
            "PATH=/system/bin",
            "MOSH_KEY=${endpoint.key}",
            "MOSH_IP=${endpoint.ip}",
            "MOSH_PORT=${endpoint.port}",
            "MOSH_COLS=$cols",
            "MOSH_ROWS=$rows",
        )
    }
}
