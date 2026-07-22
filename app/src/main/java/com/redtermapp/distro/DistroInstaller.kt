package com.redtermapp.distro

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

class DistroInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    private var deviceAbi: String = "aarch64"

    fun setDeviceAbi(abi: String) {
        deviceAbi = abi
    }

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val rootfsDir = getRootfsDir(distro.name).also { it.mkdirs() }
            val tarball = File(context.cacheDir, "${distro.name}.tar.xz")
            if (tarball.exists()) tarball.delete()
            val tarballUrl = distro.tarballUrlFor(deviceAbi)
            Log.i("DistroInstaller", "Downloading $tarballUrl")

            downloadTarball(tarballUrl, tarball, onProgress)

            val expectedSha = distro.sha256For(deviceAbi)
            if (expectedSha.isNotEmpty()) {
                verifyChecksum(tarball, expectedSha)
            }

            extractTarball(tarball, rootfsDir, onProgress)
            setupRootfs(rootfsDir, distro)
            tarball.delete()
            saveInstalled(distro.name)
            Log.i("DistroInstaller", "Install complete for ${distro.name}")
        } catch (e: Throwable) {
            Log.e("DistroInstaller", "Install failed", e)
            throw Exception("Install failed: ${e.message}", e)
        }
    }

    private suspend fun downloadTarball(
        urlString: String,
        dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val httpUrl = URL(urlString)
        val conn = httpUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode for $urlString")
        }

        val total = conn.contentLengthLong
        val buffer = ByteArray(8192)

        FileOutputStream(dest).use { output ->
            conn.inputStream.use { input ->
                var read: Int
                var downloaded = 0L
                val startTime = System.currentTimeMillis()

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val percent = ((downloaded * 100) / total).toInt()
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val speed = if (elapsed > 0) {
                            "${(downloaded / 1024 / elapsed)} KB/s"
                        } else "0 KB/s"
                        onProgress(Progress(percent, speed))
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun verifyChecksum(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.lowercase()) {
            throw Exception("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
    }

    private suspend fun extractTarball(
        tarball: File,
        dest: File,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val total = tarball.length()
            var extracted = 0L

            FileInputStream(tarball).use { fis ->
                XZCompressorInputStream(fis).use { xz ->
                    TarArchiveInputStream(xz).use { tarIn ->
                        // Detect top-level prefix from first entry
                        val firstEntry = tarIn.nextTarEntry
                        var prefixToStrip = ""
                        if (firstEntry != null) {
                            val name = firstEntry.name
                            val slash = name.indexOf('/')
                            if (slash > 0) {
                                prefixToStrip = name.substring(0, slash + 1)
                                Log.i("DistroInstaller", "Stripping prefix: $prefixToStrip")
                            }
                        }

                        // Helper to process an entry with prefix stripping
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
                                    java.nio.file.Files.createSymbolicLink(
                                        target.toPath(),
                                        java.nio.file.Paths.get(linkTarget)
                                    )
                                } catch (e: Exception) {
                                    Log.w("DistroInstaller",
                                        "Symlink failed ${entry.name} -> $linkTarget: ${e.message}")
                                }
                            } else if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    val buf = ByteArray(8192)
                                    while (true) {
                                        val read = tarIn.read(buf)
                                        if (read == -1) break
                                        out.write(buf, 0, read)
                                    }
                                }
                                val isExec = (entry.mode and 64) != 0 ||
                                    (entry.mode and 1) != 0
                                target.setReadable(true, false)
                                target.setExecutable(isExec, false)
                                target.setWritable(!isExec)
                            }

                            extracted += entry.size
                        }

                        // Process first entry if it exists
                        if (firstEntry != null) {
                            processEntry(firstEntry)
                        }

                        var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            processEntry(entry)
                            val percent = if (total > 0) ((extracted * 100) / total).toInt() else 0
                            onProgress(Progress(percent, "Extracting"))
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        } catch (e: NoClassDefFoundError) {
            throw Exception("Missing compression library: ${e.message}")
        }
    }

    private fun setupRootfs(rootfs: File, distro: Distro) {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        val fstab = File(rootfs, "etc/fstab")
        if (!fstab.exists()) {
            fstab.writeText("none /proc proc defaults 0 0\nnone /sys sysfs defaults 0 0\n")
        }

        repairRootfs(rootfs)
    }

    fun repairRootfs(rootfs: File): String {
        val repairs = mutableListOf<String>()

        File(rootfs, "root").mkdirs()
        repairs.add("Created /root")

        // Check for tarball prefix dir (e.g., alpine-aarch64/) and migrate files up
        val subdirs = rootfs.listFiles()?.filter { it.isDirectory && it.name.contains('-') } ?: emptyList()
        for (subdir in subdirs) {
            val innerBin = File(subdir, "bin")
            if (innerBin.exists()) {
                repairs.add("Found nested rootfs in ${subdir.name}/, migrating...")
                subdir.listFiles()?.forEach { file ->
                    val dest = File(rootfs, file.name)
                    if (file.isDirectory) {
                        file.copyRecursively(dest, overwrite = true)
                        file.deleteRecursively()
                    } else {
                        file.copyTo(dest, overwrite = true)
                        file.delete()
                    }
                }
                repairs.add("Migrated files from ${subdir.name}/ to rootfs")
            }
        }

        val busybox = File(rootfs, "bin/busybox")
        if (busybox.exists()) {
            if (!busybox.canExecute()) {
                busybox.setExecutable(true, false)
                repairs.add("Made bin/busybox executable")
            }
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                sh.delete()
                try {
                    java.nio.file.Files.createSymbolicLink(
                        sh.toPath(), java.nio.file.Paths.get("busybox")
                    )
                } catch (_: Exception) {
                    busybox.copyTo(sh, overwrite = true)
                    sh.setExecutable(true, false)
                    repairs.add("Copied bin/busybox -> bin/sh")
                }
                if (sh.canExecute()) {
                    repairs.add("bin/sh is now executable")
                } else {
                    repairs.add("WARN: bin/sh still not executable")
                }
            }
        } else {
            repairs.add("WARN: bin/busybox not found in rootfs")
            val binDir = File(rootfs, "bin")
            if (binDir.exists()) {
                val contents = binDir.list()?.joinToString(", ") ?: "empty"
                repairs.add("bin/ contents: $contents")
            } else {
                repairs.add("bin/ directory missing!")
            }
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            repairs.add("Created etc/resolv.conf")
        }

        return repairs.joinToString("\n")
    }

    fun isInstalled(distroName: String): Boolean =
        File(context.filesDir, "installed/${distroName}").exists()

    fun getRootfsDir(distroName: String): File =
        File(context.filesDir, "rootfs/$distroName")

    private fun saveInstalled(distroName: String) {
        File(context.filesDir, "installed").mkdirs()
        File(context.filesDir, "installed/$distroName").writeText(distroName)
    }

    fun getInstalledDistros(): List<String> {
        val dir = File(context.filesDir, "installed")
        return if (dir.exists()) dir.list()?.toList() ?: emptyList() else emptyList()
    }

    fun uninstall(distroName: String) {
        getRootfsDir(distroName).deleteRecursively()
        File(context.filesDir, "installed/$distroName").delete()
    }
}
