package com.redtermapp.distro

import android.system.Os
import android.system.StructStat
import java.io.File

/**
 * Writes a shell startup file into a distribution's `/root`, whatever the archive
 * recorded for that directory.
 *
 * Four of the published images ship `/root` as `0550`:
 *
 *     oracle  fedora  almalinux  rocky        dr-xr-x---
 *
 * and the rest as `0700`, `0710` or `0750`. Only `0700`/`0750`/`0710` can be written by
 * the app, because on Android the extracted tree belongs to the app's own uid, so the
 * *owner* bits are what apply. A `0550` `/root` has no owner-write bit at all, and
 * `File.writeText` then fails:
 *
 *     java.io.FileNotFoundException: .../rootfs/oracle/root/.bashrc:
 *         open failed: EACCES (Permission denied)
 *
 * which crashed the app. It was never Oracle-specific -- it is the whole RHEL family,
 * Fedora being upstream of RHEL, and Oracle being a RHEL derivative.
 *
 * `0550` is not wrong. It is what those distributions intend for root's home, and the
 * right answer is not to loosen it permanently but to borrow the write bit for the one
 * file being written and put the mode back afterwards. Leaving `/root` at `0700` or
 * `0750` afterwards is a permanent, unrequested change to how the distribution looks
 * from inside a session.
 *
 * Both modes are restored even when the write fails, so a failed attempt does not leave
 * a distro more open than it was found.
 */
internal object BashrcWriter {

    /**
     * Seams. `Os.chmod` and `Os.stat` are framework classes that are not mocked on a JVM
     * test classpath, and a permission fix that cannot be exercised off-device is a
     * permission fix that is only ever verified by the crash it was written to stop.
     */
    internal var chmod: (path: String, mode: Int) -> Unit =
        { path, mode -> Os.chmod(path, mode) }

    internal var modeOf: (path: String) -> Int =
        { path -> Os.stat(path).st_mode and 0xFFF }

    /** Owner write, and nothing else: the one bit being borrowed. */
    private const val OWNER_WRITE = 0x80

    sealed class Result {
        /**
         * [previous] is the content that was there before, read *before* the write.
         *
         * Read here rather than by the caller afterwards: "restore original" reads
         * `bashrc_orig_*` back out of preferences, so a caller that captures the
         * original after writing stores the new template as the original and turns
         * restore into a no-op.
         */
        data class Written(val previous: String?) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Writes [content] to `<rootfs>/root/[fileName]`.
     *
     * Returns [Result.Failed] rather than throwing, because the caller applies this to
     * several distributions in one go and a throw on the first one abandons the rest
     * half-done while reporting nothing about which ones were touched.
     */
    fun write(rootfs: File, content: String, fileName: String = ".bashrc"): Result {
        val home = File(rootfs, "root")
        if (!home.isDirectory && !home.mkdirs()) {
            return Result.Failed("cannot create $home")
        }

        val homeMode = readMode(home)
        val target = File(home, fileName)
        val targetMode = if (target.exists()) readMode(target) else null

        try {
            // `or` rather than a fixed 0700: borrowing the write bit is the whole point,
            // and replacing the mode would quietly widen every other bit the archive set.
            homeMode?.let { runCatching { chmod(home.absolutePath, it or OWNER_WRITE) } }
            targetMode?.let { runCatching { chmod(target.absolutePath, it or OWNER_WRITE) } }
            val previous = if (target.exists()) runCatching { target.readText() }.getOrNull() else null
            target.writeText(content)
            return Result.Written(previous)
        } catch (e: Exception) {
            // The errno is the only useful thing here. "Permission denied" with the path
            // is what the crash reported, and it named the file without saying whether
            // the directory or the file was the obstacle.
            val what = when {
                targetMode != null && targetMode and OWNER_WRITE == 0 -> "$fileName is read-only"
                homeMode != null && homeMode and OWNER_WRITE == 0 ->
                    "/root is ${octal(homeMode)} and cannot be written to"
                else -> e.message ?: e.javaClass.simpleName
            }
            return Result.Failed("$what (${e.message ?: e.javaClass.simpleName})")
        } finally {
            // Restore first the file, then its directory, so the directory is never
            // tightened while something inside it still needs writing.
            targetMode?.let { runCatching { chmod(target.absolutePath, it) } }
            homeMode?.let { runCatching { chmod(home.absolutePath, it) } }
        }
    }

    private fun readMode(f: File): Int? = runCatching { modeOf(f.absolutePath) }.getOrNull()

    private fun octal(mode: Int): String = "0" + Integer.toOctalString(mode)
}