package com.redtermapp.ui

import android.content.Context
import android.util.Log
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.util.AppLog
import com.redtermapp.distro.TarExtractor
import com.redtermapp.distro.TarMembers
import java.io.File

/**
 * Restores a distro from a backup archive.
 *
 * The archive is extracted to a staging directory and only swapped in once it
 * validates as a distro, so a failed or corrupt restore always leaves the
 * existing install exactly as it was.
 */
class DistroRestore(
    private val context: Context,
    private val installer: DistroInstaller
) {

    data class Result(val succeeded: Boolean, val error: String?, val originalKept: Boolean)

    fun restore(
        backupFile: File,
        distroName: String,
        rootfsDir: File,
        onProgress: (message: String, fraction: Float, total: Int) -> Unit
    ): Result {
        val hadOriginal = rootfsDir.exists()
        val parent = rootfsDir.parentFile
            ?: return Result(false, "no parent directory", hadOriginal)
        val staged = File(parent, "${rootfsDir.name}.restoring")
        val retired = File(parent, "${rootfsDir.name}.old")
        var swapped = false
        return try {
            // Read the archive before extracting it. A truncated gzip stream surfaces as
            // a short list or a failure here, where it costs nothing, rather than as a
            // half-populated staging directory that looks like a distro.
            val listed = countMembers(backupFile)
            if (listed <= 0) {
                throw RuntimeException(context.getString(R.string.restore_archive_unreadable))
            }
            staged.deleteRecursively()
            staged.mkdirs()

            // The staging directory is on the app's own storage, not on the shared
            // volume the archive lives on, and it has to hold the *expanded* distro —
            // two to three gigabytes for Arch — while the existing install is still
            // there. Backup has always checked for room before writing; restore had no
            // equivalent, so a full disk produced a wall of tar errors and a swap that
            // was never reached. Refusing here names the reason instead.
            val needed = installer.freeBytesAt(staged.parentFile ?: staged)
            val want = uncompressedEstimate(backupFile, listed)
            if (needed in 1 until want) {
                throw RuntimeException(
                    context.getString(
                        R.string.restore_not_enough_space,
                        formatBytes(want),
                        formatBytes(needed)
                    )
                )
            }

            onProgress(context.getString(R.string.restore_extracting), 0f, listed)
            // Our own extractor rather than the device's tar. A rootfs is full of real
            // hard links — Arch's ca-certificates cadir is several hundred of them — and
            // tar restores those with link(), which fails here with EPERM and took the
            // whole restore down with it. Hard links are materialised as copies instead,
            // which is also what proot needs: it emulates them with link2symlink, so a
            // real hard link would alias inodes the guest treats as separate.
            val extracted = TarExtractor.extract(
                backupFile, staged, stripComponents = 1
            ) { done ->
                onProgress(
                    context.getString(R.string.restore_extracting),
                    (done.toFloat() / listed).coerceIn(0f, 1f),
                    listed
                )
            }
            if (!extracted.ok) {
                // A handful of individual entries failing is not a failed restore, but it
                // is never silent: what is missing is exactly what a later session breaks
                // on. The count is reported rather than the whole lot.
                AppLog.w(
                    context, "restore",
                    "restore $distroName: ${extracted.failed.size} entries failed; " +
                        "first: ${extracted.failed.take(3).joinToString("; ")}"
                )
            }
            // A staging directory that looks like a distro is not the same as a whole
            // one. `listed` was counted from the archive before extraction, so it is the
            // number this tree is supposed to have; far fewer means extraction was cut
            // short, and swapping that in would replace a working install with a
            // half-populated one that only fails later, inside a session.
            val onDisk = countTree(staged)
            if (onDisk == 0) {
                throw RuntimeException(context.getString(R.string.restore_archive_empty))
            }
            if (onDisk < listed / 2) {
                throw RuntimeException(
                    context.resources.getQuantityString(
                        R.plurals.restore_incomplete, onDisk, onDisk, listed
                    )
                )
            }
            if (!looksLikeDistro(staged)) {
                val nested = File(staged, rootfsDir.name)
                if (looksLikeDistro(nested)) {
                    hoistChildren(nested, staged)
                } else {
                    throw RuntimeException(context.getString(R.string.restore_not_a_distro))
                }
            }
            onProgress(context.getString(R.string.restore_installing), 1f, listed)
            retired.deleteRecursively()
            if (hadOriginal && !rootfsDir.renameTo(retired)) {
                throw RuntimeException("Could not set aside the existing install")
            }
            if (!staged.renameTo(rootfsDir)) {
                if (hadOriginal) retired.renameTo(rootfsDir)
                throw RuntimeException("Could not move the restored distro into place")
            }
            swapped = true
            retired.deleteRecursively()
            installer.saveInstalled(distroName)
            installer.repairRootfs(rootfsDir)
            AppLog.i(
                context, "restore",
                "restore $distroName from ${backupFile.name} OK ($listed entries)"
            )
            Result(true, null, hadOriginal)
        } catch (e: Exception) {
            Log.w("DistroRestore", "Restore of $distroName failed", e)
            // Mirrored into the app log, for the same reason backup mirrors it:
            // android.util.Log is invisible in Diagnostics, so a restore that failed
            // left no trace the user could read or share. The full message goes in,
            // not a tail of it — the whole point is that this is the only copy.
            AppLog.w(
                context, "restore",
                "restore $distroName from ${backupFile.name} FAILED: ${e.message}"
            )
            if (!swapped) {
                staged.deleteRecursively()
                retired.deleteRecursively()
            }
            Result(false, e.message, hadOriginal)
        }
    }

    private fun countMembers(archive: File): Int = TarMembers.count(archive)

    /**
     * Rough upper bound on what the archive needs once expanded.
     *
     * The compressed size is a floor, not the answer, so it is taken as the estimate and
     * only used to refuse a restore that certainly cannot fit. Rootfs contents are
     * largely already-compressed binaries, and gzip gets roughly three times on a distro
     * of that mix, so three times the archive is a bound rarely wrong in the direction
     * that matters.
     */
    private fun uncompressedEstimate(archive: File, listed: Int): Long =
        (archive.length() * 3).coerceAtLeast(listed.toLong() * 1024L)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "${bytes / 1000} KB"
    }

    /** Files and directories below [dir], not counting [dir] itself. */
    private fun countTree(dir: File): Int {
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                count++
                if (child.isDirectory) stack.addLast(child)
            }
        }
        return count
    }

    private fun looksLikeDistro(dir: File): Boolean =
        File(dir, "etc/os-release").exists() ||
            File(dir, "etc/debian_version").exists() ||
            File(dir, "bin/sh").exists()

    /** Fallback for tar builds that ignore --strip-components. */
    private fun hoistChildren(from: File, to: File) {
        val children = from.listFiles() ?: return
        for (child in children) {
            val target = File(to, child.name)
            target.deleteRecursively()
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
        from.deleteRecursively()
    }
}
