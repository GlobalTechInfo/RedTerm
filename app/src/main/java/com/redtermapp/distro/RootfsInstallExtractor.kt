package com.redtermapp.distro

import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Unpacks a rootfs tarball, keeping the modes the archive records.
 *
 * This exists because the extraction used to live inside [DistroInstaller] as a private
 * method, and could therefore not be tested: `Os.chmod` and `Os.symlink` are not mocked
 * on a JVM classpath. It flattened every directory to 0755 as it created it and reduced
 * every file to "executable or not", so the mode in the header was read and then thrown
 * away. That is what produced, on Arch and ARMtix, on every package transaction:
 *
 *   warning: directory permissions differ on /tmp/
 *   filesystem: 755  package: 1777
 *   warning: directory permissions differ on /root/
 *   filesystem: 755  package: 750
 *
 * The published images were correct throughout — `armtix-aarch64-rt-v1.0.0.tar.xz`
 * carries 1777 on /tmp, 2755 on its setgid directories and 750 on /root — so no change
 * to how the images are built could ever have fixed it.
 *
 * proot-distro's install is the reference (`proot-distro.sh:535`): `--preserve-permissions`
 * to keep the archived mode, `--delay-directory-restore` to apply directory modes after
 * their contents. The delay is not optional. A directory the archive records as 0555
 * cannot have children created inside it, so applying its mode on arrival strands
 * everything below it.
 *
 * Restore goes through [TarExtractor], which does this correctly and has always done so.
 */
object RootfsInstallExtractor {

    /**
     * A seam so the logic can be exercised off-device, where `Os` is not mocked.
     *
     * `Os.chmod` rather than `java.io.File`: the latter is API 1 and cannot set the group
     * bits at all, and a rootfs has group permissions that matter.
     */
    internal var changeMode: (path: String, mode: Int) -> Unit =
        { path, mode -> android.system.Os.chmod(path, mode) }

    internal var makeSymlink: (target: String, path: String) -> Unit =
        { target, path -> android.system.Os.symlink(target, path) }

    private class PendingDir(val target: File, val mode: Int)

    /**
     * Paths whose mode could not be applied, with the reason.
     *
     * Populated during [extract] and read afterwards. Never null, never thrown: the
     * install has to complete, but a caller is entitled to know what it got wrong.
     */
    val modeFailures = mutableListOf<String>()

    /**
     * Extracts every remaining entry of [tarIn] into [dest].
     *
     * Returns the number of entries written. [onProgress] is given a percentage derived
     * from bytes consumed, which is the best available here and is clamped to 99 because
     * the caller has work left to do after the last entry.
     *
     * [onLog] rather than `android.util.Log`: this object is pure, so it can be unit
     * tested, and `android.util.Log` throws when it is not mocked.
     */
    fun extract(
        tarIn: TarArchiveInputStream,
        dest: File,
        totalCompressed: Long = 0L,
        checkCancel: () -> Unit = {},
        onProgress: (percent: Int) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Int {
        val firstEntry = tarIn.getNextEntry()
        var prefixToStrip = ""
        if (firstEntry != null) {
            val name = firstEntry.name
            val slash = name.indexOf('/')
            if (slash > 0) {
                prefixToStrip = name.substring(0, slash + 1)
                onLog("Stripping prefix: $prefixToStrip")
            }
        }

        var processed = 0L
        var entryCount = 0
        val pendingDirs = ArrayList<PendingDir>()

        fun applyMode(target: File, mode: Int) {
            try {
                // 0xFFF keeps setuid, setgid and sticky and drops the file-type nibble,
                // which is not an argument to chmod. This is what leaves /tmp at 1777 and
                // a setgid directory at 2755 rather than flattening both to 0755.
                changeMode(target.absolutePath, mode and 0xFFF)
            } catch (e: Exception) {
                // Recorded, not swallowed.
                //
                // When a mode is not applied the entry keeps whatever mkdirs() gave it,
                // which on Android is 0775 under the app's umask. A rootfs whose /tmp
                // came out drwxrwxr-x instead of drwxrwxrwt then reports, correctly and
                // on every package transaction:
                //
                //   warning: directory permissions differ on /tmp/
                //   filesystem: 775  package: 1777
                //
                // and nothing anywhere names the chmod that failed. The install still
                // continues -- the content matters more than one mode -- but the caller
                // can now say which entries are wrong instead of leaving it to be
                // inferred from a package manager three sessions later.
                modeFailures += "${target.name} wanted ${Integer.toOctalString(mode and 0xFFF)}" +
                    ": ${e.message ?: e.javaClass.simpleName}"
            }
        }

        fun processEntry(entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry) {
            var entryName = entry.name
            if (prefixToStrip.isNotEmpty() && entryName.startsWith(prefixToStrip)) {
                entryName = entryName.removePrefix(prefixToStrip)
            }
            if (entryName.isEmpty()) return
            val target = File(dest, entryName)
            if (entry.isSymbolicLink) {
                val linkTarget = entry.linkName
                target.parentFile?.mkdirs()
                try {
                    target.delete()
                    makeSymlink(linkTarget, target.absolutePath)
                } catch (e: Exception) {
                    onLog("Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
                pendingDirs.add(PendingDir(target, entry.mode))
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = tarIn.read(buf)
                        if (read == -1) break
                        checkCancel()
                        out.write(buf, 0, read)
                        processed += read
                    }
                }
                applyMode(target, entry.mode)
            }
        }

        if (firstEntry != null) processEntry(firstEntry)
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? =
            tarIn.getNextEntry()
        while (entry != null) {
            checkCancel()
            processEntry(entry)
            entryCount++
            val pct = if (totalCompressed > 0) {
                ((processed * 100L) / (totalCompressed * 3L)).toInt().coerceAtMost(99)
            } else 0
            onProgress(pct)
            entry = tarIn.getNextEntry()
        }

        // Deepest first, so a parent recorded as read-only cannot stop its own children
        // from being chmod-ed. Path length is a proxy for depth that costs nothing per
        // entry; TarExtractor orders the same way.
        for (dir in pendingDirs.sortedByDescending { it.target.path.length }) {
            applyMode(dir.target, dir.mode)
        }
        return entryCount
    }
}