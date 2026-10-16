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

        /** 0644, the mode a link takes when its target's was never seen. */
        val PLAIN_FILE_MODE = 420

        // Mode of every regular file as the archive recorded it, so a hard link that
        // arrives after its target can inherit it: a type-1 entry carries none.
        val recordedMode = mutableMapOf<String, Int>()

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
            } else if (entry.isLink) {
                // A hard link in the archive: type 1, no content of its own, and no
                // mode either -- the mode lives on the first, regular member.
                //
                // Arch ships ~600 of these in /etc/ca-certificates/extracted/cadir,
                // where the first member is 0444 and every later member is a link to
                // it. There was no branch for this at all, so each one fell through to
                // the regular-file path and was written as a *zero-byte file*, with
                // whatever mode the header happened to carry. Every certificate reached
                // through /etc/ssl/certs was then an empty file.
                //
                // They are materialised as independent copies rather than real hard
                // links on purpose: proot emulates link() with link2symlink, so a real
                // hard link would alias inodes that the guest treats as separate. The
                // restore path has always done this for the same reason.
                target.parentFile?.mkdirs()
                // The link target is written two different ways in the wild and both
                // occur in this project's own archives:
                //
                //   sibling        "GlobalSign_Root_R46.pem"    (the shipped images)
                //   archive-root   "cadir/B.pem"                 (GNU tar -cf)
                //
                // so try the sibling first, then the root, then a basename of the
                // sibling form. Guessing one is how a hard link turns into an empty
                // file that looks identical to a hard link whose target is missing.
                val parent = File(entryName).parentFile?.path.orEmpty()
                val candidates = listOf(
                    File(dest, File(parent, entry.linkName).path),
                    File(dest, entry.linkName),
                    File(dest, File(parent, File(entry.linkName).name).path)
                )
                val source = candidates.firstOrNull { it.isFile }
                try {
                    if (source != null && source.canonicalPath != target.canonicalPath) {
                        source.copyTo(target, overwrite = true)
                        // The target's own mode, which is the mode the archive recorded
                        // for the file these two names share.
                        applyMode(target, recordedMode[source.absolutePath] ?: PLAIN_FILE_MODE)
                    } else {
                        // The target has not been extracted yet, which tar does not do
                        // but a hand-built archive might. An empty file is wrong and a
                        // missing one is worse; say so rather than pretend.
                        onLog("Hard link ${entry.name} has no extracted target ${entry.linkName}")
                        target.createNewFile()
                    }
                } catch (e: Exception) {
                    onLog("Hard link ${entry.name} -> ${entry.linkName} failed: ${e.message}")
                }
            } else {
                target.parentFile?.mkdirs()
                // Replace rather than write into. A file left by an earlier extraction
                // carries the mode recorded then, and opening a 0444 file for writing is
                // EACCES -- which is how one unreadable certificate aborted a whole
                // install.
                if (target.exists()) target.delete()
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
                recordedMode[target.absolutePath] = entry.mode
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