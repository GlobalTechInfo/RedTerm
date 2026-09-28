package com.redtermapp.distro

import android.content.Context
import android.os.Build
import android.util.Log
import com.redtermapp.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether Termux publishes a newer proot-distro base image for a distro.
 *
 * The release asset name embeds the distro, the proot architecture and a build
 * date, so the installed archive's URL is the only reliable way to tell which
 * image a rootfs was built from. That URL is recorded at install time.
 */
class BaseImageUpdate(private val context: Context) {

    data class Release(val tag: String, val assetName: String, val url: String, val sizeBytes: Long)

    data class Comparison(
        val installedTag: String,
        val latest: Release?,
        val error: String? = null
    ) {
        val hasUpdate: Boolean get() = latest != null && latest.assetName != installedTag
    }

    private fun markerFile(distroName: String) = File(context.filesDir, "installed_base/$distroName")

    /**
     * The asset the rootfs was built from. Installs made by this app record it
     * explicitly; for distros installed by an older build the marker is absent,
     * so the version this app would install today is the best available answer.
     */
    fun installedAsset(distroName: String): String {
        val recorded = try {
            markerFile(distroName).readText().trim()
        } catch (_: Exception) {
            ""
        }
        if (recorded.isNotEmpty()) return recorded
        val distro = DistroRegistry.allDistros.firstOrNull { it.name == distroName }
            ?: return ""
        val url = distro.tarballUrlFor(Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
        return url.substringAfterLast('/').substringBeforeLast(".tar.xz")
    }

    fun recordInstalledAsset(distroName: String, assetName: String) {
        val file = markerFile(distroName)
        file.parentFile?.mkdirs()
        file.writeText(assetName)
    }

    fun clear(distroName: String) {
        markerFile(distroName).delete()
    }

    fun latestRelease(distroName: String): Comparison {
        val prootArch = abiToProotArch(Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
        val installed = installedAsset(distroName)
        return try {
            val url = URL(
                "https://api.github.com/repos/termux/proot-distro/releases/latest"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "RedTerm")
            }
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val assets = json.optJSONArray("assets")
            var match: Release? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    // e.g. debian-aarch64-pg_13.2-20250401.tar.xz
                    if (name.startsWith("$distroName-$prootArch") && name.endsWith(".tar.xz")) {
                        match = Release(
                            tag = tag,
                            assetName = name,
                            url = asset.optString("browser_download_url", ""),
                            sizeBytes = asset.optLong("size", 0L)
                        )
                        break
                    }
                }
            }
            Comparison(installed, match)
        } catch (e: Exception) {
            Log.w("BaseImageUpdate", "Release check failed for $distroName", e)
            Comparison(installed, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Replaces the system files with a newer base image while keeping the data a
     * user cares about: /root and /home. Everything else reverts to whatever the
     * new image ships, which is why the UI confirms before calling this.
     */
    suspend fun applyUpdate(
        distro: Distro,
        release: Release,
        onProgress: (String) -> Unit
    ): Result {
        val installer = DistroInstaller(context)
        val rootfs = installer.getRootfsDir(distro.name)
        if (!rootfs.isDirectory) {
            return Result(false, context.getString(R.string.distro_not_installed))
        }
        val staging = File(context.filesDir, "rootfs-staging/${distro.name}")
        val keep = File(context.filesDir, "rootfs-keep/${distro.name}")
        return try {
            if (staging.exists()) staging.deleteRecursively()
            if (keep.exists()) keep.deleteRecursively()
            staging.mkdirs()
            keep.mkdirs()

            onProgress(context.getString(R.string.update_base_downloading, release.assetName))
            val tarball = File(context.cacheDir, "base-${distro.name}.tar.xz")
            if (!download(release.url, tarball)) {
                return Result(false, "download failed")
            }

            onProgress(context.getString(R.string.update_base_extracting))
            installer.extractBaseImage(tarball, staging) { onProgress(it) }
            tarball.delete()

            // The proot archives contain a single top-level directory.
            val extractedRoot = staging.listFiles()?.firstOrNull { it.isDirectory }
                ?: return Result(false, "unexpected archive layout")

            onProgress("Saving your files…")
            for (dir in listOf("root", "home")) {
                val source = File(extractedRoot, dir)
                if (source.isDirectory) source.copyRecursively(File(keep, dir), overwrite = true)
            }
            for (dir in listOf("root", "home")) {
                val existing = File(rootfs, dir)
                if (existing.exists()) existing.deleteRecursively()
                val saved = File(keep, dir)
                if (saved.isDirectory) saved.copyRecursively(existing, overwrite = true)
            }

            // Swap the tree: the old one is only removed once the new files and
            // the user's data are both in place.
            val retired = File(context.filesDir, "rootfs-retired/${distro.name}")
            if (retired.exists()) retired.deleteRecursively()
            retired.parentFile?.mkdirs()
            if (!rootfs.renameTo(retired)) {
                return Result(false, "could not move the old rootfs aside")
            }
            if (!extractedRoot.renameTo(rootfs)) {
                retired.renameTo(rootfs)
                return Result(false, "could not install the new rootfs")
            }
            retired.deleteRecursively()
            keep.deleteRecursively()
            staging.deleteRecursively()

            installer.repairRootfs(rootfs)
            recordInstalledAsset(distro.name, release.assetName)
            installer.clearSizeCache(distro.name)
            Result(true, null)
        } catch (e: Exception) {
            Log.w("BaseImageUpdate", "Base image update failed", e)
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            if (keep.exists()) keep.deleteRecursively()
        }
    }

    private fun download(url: String, target: File): Boolean = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "RedTerm")
        }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        connection.disconnect()
        target.length() > 0
    } catch (e: Exception) {
        Log.w("BaseImageUpdate", "download failed", e)
        false
    }

    data class Result(val succeeded: Boolean, val error: String?)
}
