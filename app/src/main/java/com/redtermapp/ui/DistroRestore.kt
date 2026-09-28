package com.redtermapp.ui

import android.content.Context
import android.util.Log
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
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
        onProgress: (String) -> Unit
    ): Result {
        val hadOriginal = rootfsDir.exists()
        val parent = rootfsDir.parentFile
            ?: return Result(false, "no parent directory", hadOriginal)
        val staged = File(parent, "${rootfsDir.name}.restoring")
        val retired = File(parent, "${rootfsDir.name}.old")
        var swapped = false
        return try {
            staged.deleteRecursively()
            staged.mkdirs()
            onProgress(context.getString(R.string.restore_extracting))
            val process = ProcessBuilder(
                "tar", "-xzf", backupFile.absolutePath,
                "-C", staged.absolutePath, "--strip-components=1"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val code = process.waitFor()
            if (code != 0) {
                throw RuntimeException(output.ifEmpty { "tar exited with code $code" })
            }
            if (!looksLikeDistro(staged)) {
                val nested = File(staged, rootfsDir.name)
                if (looksLikeDistro(nested)) {
                    hoistChildren(nested, staged)
                } else {
                    throw RuntimeException(context.getString(R.string.restore_not_a_distro))
                }
            }
            onProgress(context.getString(R.string.restore_installing))
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
            Result(true, null, hadOriginal)
        } catch (e: Exception) {
            Log.w("DistroRestore", "Restore of $distroName failed", e)
            if (!swapped) {
                staged.deleteRecursively()
                retired.deleteRecursively()
            }
            Result(false, e.message, hadOriginal)
        }
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
