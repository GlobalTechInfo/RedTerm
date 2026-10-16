package com.redtermapp.distro

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Single source of truth for the proot invocation.
 *
 * The interactive terminal and the non-interactive command runner must launch a
 * distro identically, otherwise a command that works in the terminal can fail
 * when run from the app (missing binds, different PATH, missing env fixes).
 */
object ProotLaunch {

    /**
     * Wraps [value] in single quotes for the shell, escaping any it contains.
     *
     * POSIX has no way to escape a single quote inside a single-quoted string, so
     * the string is closed, a backslash-escaped quote is emitted, and it is
     * reopened. The value survives byte for byte, which matters for empty
     * arguments and for arguments that themselves contain quotes.
     */
    internal fun quoteForShell(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Writes the launcher script for a distro.
     *
     * @param command null for an interactive login shell, otherwise a shell
     *   command to execute instead of the shell.
     */
    fun writeLauncher(
        context: Context,
        rootfsDir: File,
        startInner: String? = null,
        command: String? = null,
        scriptName: String = "launch.sh",
        mergeStderr: Boolean = true
    ): File {
        val out = File(context.filesDir, scriptName)
        out.parentFile?.mkdirs()
        out.writeText(
            buildScript(
                nativeLibDir = context.applicationInfo.nativeLibraryDir,
                rootfsPath = rootfsDir.absolutePath,
                hasLoader32 = File("${context.applicationInfo.nativeLibraryDir}/libproot-loader32.so").exists(),
                startInner = startInner,
                command = command,
                mergeStderr = mergeStderr
            )
        )
        out.setExecutable(true, true)
        return out
    }

    /** Pure so the exact script the terminal depends on can be unit tested. */
    fun buildScript(
        nativeLibDir: String,
        rootfsPath: String,
        hasLoader32: Boolean,
        startInner: String? = null,
        command: String? = null,
        mergeStderr: Boolean = true
    ): String {
        return buildScriptText(
            nativeLibDir = nativeLibDir,
            rootfsPath = rootfsPath,
            hasLoader32 = hasLoader32,
            startInner = startInner,
            command = command,
            mergeStderr = mergeStderr
        )
    }

    private fun buildScriptText(
        nativeLibDir: String,
        rootfsPath: String,
        hasLoader32: Boolean,
        startInner: String?,
        command: String?,
        mergeStderr: Boolean
    ): String {
        val prootBin = "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libproot-loader.so"
        val ldr32 =
            if (hasLoader32) "export PROOT_LOADER_32=$nativeLibDir/libproot-loader32.so\n" else ""
        val rp = rootfsPath

        // Merging stderr into stdout is right for anything a human reads, and
        // fatal for a binary protocol: one warning about a host key landing in the
        // middle of an SFTP packet stream desynchronises it permanently. Callers
        // that speak a protocol turn this off and read stderr themselves.
        val redirect = if (mergeStderr) " 2>&1" else ""
        val tail = if (command == null) {
            "/system/bin/sh -i" + redirect
        } else {
            // The startup script carries the per-distro environment repairs
            // (mirrors, PATH, locale), so it has to be sourced for one-shot
            // commands too or they fail in ways the terminal does not.
            //
            // The whole thing has to be quoted, not just wrapped in quotes: a
            // command containing a single quote would end the quoting early, and
            // an empty argument such as `ssh-keygen -N ''` would be swallowed by
            // the outer shell entirely. That silently turned "-N '' -C comment"
            // into "-N -C comment", so keygen read "-C" as the passphrase and
            // rejected the command line.
            val inner =
                "if [ -f /root/.startup ]; then . /root/.startup >/dev/null 2>&1; fi; " +
                    command
            "/system/bin/sh -c ${quoteForShell(inner)}" + redirect
        }

        val script = """#!/system/bin/sh
umask 022
export HOME=/root
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
export ENV=/root/.startup
export TERM=xterm-256color
export COLORTERM=truecolor
if command -v locale >/dev/null 2>&1 && locale -a 2>/dev/null | grep -qi '^C\.utf-\?8$'; then
    export LANG=C.UTF-8
    export LC_ALL=C.UTF-8
else
    export LANG=C
    export LC_ALL=C
fi
export PROOT_LOADER=$prootLoader
${ldr32}export PROOT_TMP_DIR=$rp/tmp
mkdir -p "$rp/tmp"
# No --link2symlink.
#
# It exists to emulate `link()` on a filesystem that cannot do it, and it is not
# needed for anything this app ships. Measured across a complete image:
#
#     find . -xdev -type f -links +1 | wc -l   ->  0
#
# A rootfs has no hard links in it. TarExtractor materialises them as independent
# copies on the way in and on the way out, precisely because proot would alias
# inodes the guest treats as separate.
#
# Against that, it has a measured cost. Run under proot with the flag, every
# download into the package cache produced nothing at all:
#
#     zypper --non-interactive refresh
#     Parse error: repomd.xml[1] Document is empty
#
# and the raw cache directory ended up with no files in it at all, reproducibly,
# while the same command under the same image without the flag worked. It
# corrupts cache writes rather than emulating anything, and openSUSE's package
# cache is built entirely from writes into /var/cache/zypp.
#
# The risk is that a package which calls link() at run time now fails instead of
# silently getting a symlink. That failure is loud and names the package; a
# corrupted package cache is neither.
exec $prootBin -0 -L -r "$rp" -w ${startInner ?: "/root"} --sysvipc --kill-on-exit \
${BOUND_PATHS.joinToString(" ") { "-b $it" }} \
    $tail
"""
        return script
    }

    /**
     * The paths proot bind-mounts into the rootfs at run time, split by kind.
     *
     * All are directories except [BOUND_FILES], where a single file is bound. Excluding
     * that file's whole parent directory, as an earlier version did, silently drops any
     * real content the distro shipped there.
     *
     * The launcher and the backup exclusions are both derived from this list so the two
     * cannot drift apart.
     */
    // /sdcard here is the path proot binds from inside the rootfs namespace, not the
    // app's own storage path: Environment.getExternalStorageDirectory() would return a
    // different path and break the bind. SdCardPath does not apply here.
    @Suppress("SdCardPath")
    val BOUND_DIRS = listOf(
        "/dev", "/proc", "/sys", "/system", "/apex", "/sdcard", "/storage", "/mnt"
    )

    /** Host files bound individually; excluded by exact path, not by parent directory. */
    val BOUND_FILES = listOf("/linkerconfig/ld.config.txt")

    val BOUND_PATHS: List<String> = BOUND_DIRS + BOUND_FILES

    fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
