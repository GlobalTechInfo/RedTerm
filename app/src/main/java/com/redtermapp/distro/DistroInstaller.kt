package com.redtermapp.distro

import android.content.Context
import android.os.Build
import android.util.Log
import com.redtermapp.DnsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DistroInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SIZE_CACHE_STALE_MS = 10L * 60L * 1000L
        private const val DIR_MODE = 493
        private const val FILE_MODE = 420
        private const val FILE_EXEC_MODE = 493

        private val sizeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "redterm-size-scan").apply { isDaemon = true }
        }

        fun formatSize(bytes: Long): String = when {
            bytes < 0L -> ""
            bytes < 1_000_000L -> "${bytes / 1000} KB"
            bytes < 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
            else -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
        }
    }

    @Volatile
    var cancelled = false

    private var deviceAbi: String = "aarch64"

    fun setDeviceAbi(abi: String) {
        deviceAbi = abi
    }

    fun cancel() {
        cancelled = true
    }

    /**
     * Removes any half-finished rootfs/installed marker left by a previous
     * attempt while keeping cached downloads so they can be resumed.
     */
    fun prepareForInstall(distroName: String) {
        getRootfsDir(distroName).deleteRecursively()
        File(context.filesDir, "installed/$distroName").delete()
    }

    fun hasPartialDownload(distroName: String): Boolean =
        File(tarballDir(), "$distroName.tar.xz.part").length() > 0L

    private fun sizeCacheFile(distroName: String): File = File(context.filesDir, "sizes/$distroName")

    /**
     * Returns the last computed rootfs size, or -1 when it has never been scanned.
     * Never walks the filesystem, so it is safe to call from the main thread.
     */
    fun cachedSizeBytes(distroName: String): Long {
        val file = sizeCacheFile(distroName)
        if (!file.isFile) return -1L
        return file.readText().trim().toLongOrNull() ?: -1L
    }

    fun isSizeCacheStale(distroName: String): Boolean {
        val file = sizeCacheFile(distroName)
        if (!file.isFile) return true
        return System.currentTimeMillis() - file.lastModified() > SIZE_CACHE_STALE_MS
    }

    /**
     * Recomputes the rootfs size on a background thread. Walking tens of
     * thousands of files on the main thread causes an ANR for large distros.
     */
    fun refreshSizeCache(distroName: String, onDone: ((Long) -> Unit)? = null) {
        sizeExecutor.execute {
            val bytes = try {
                val rootfs = getRootfsDir(distroName)
                if (rootfs.isDirectory) {
                    rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else {
                    -1L
                }
            } catch (_: Throwable) {
                -1L
            }
            if (bytes >= 0L) {
                try {
                    sizeCacheFile(distroName).apply {
                        parentFile?.mkdirs()
                        writeText(bytes.toString())
                    }
                } catch (_: Exception) {}
            }
            onDone?.invoke(bytes)
        }
    }

    fun clearSizeCache(distroName: String) {
        try {
            sizeCacheFile(distroName).delete()
        } catch (_: Exception) {}
    }

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        val rootfsDir = getRootfsDir(distro.name)
        val tarball = File(tarballDir(), "${distro.name}.tar.xz")
        val partial = File(tarballDir(), "${distro.name}.tar.xz.part")
        try {
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val expectedSha = distro.sha256For(deviceAbi)
            if (tarball.exists() && (expectedSha.isEmpty() || checksumMatches(tarball, expectedSha))) {
                onProgress(Progress(100, "Cached"))
            } else {
                val tarballUrl = distro.tarballUrlFor(deviceAbi)
                Log.i("DistroInstaller", "Downloading $tarballUrl")
                downloadTarball(tarballUrl, partial, onProgress)
                checkCancel()
                if (expectedSha.isNotEmpty() && !checksumMatches(partial, expectedSha)) {
                    partial.delete()
                    throw Exception("SHA-256 mismatch. Corrupt download discarded - retry to start over.")
                }
                if (tarball.exists()) tarball.delete()
                if (!partial.renameTo(tarball)) {
                    partial.copyTo(tarball, overwrite = true)
                    partial.delete()
                }
            }

            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir, distro)
            saveInstalled(distro.name)
            Log.i("DistroInstaller", "Install complete for ${distro.name}")
        } catch (e: CancelledException) {
            Log.i("DistroInstaller", "Install cancelled for ${distro.name}")
            cleanup(distro.name, keepPartialDownload = false)
            throw e
        } catch (e: CancellationException) {
            cleanup(distro.name, keepPartialDownload = false)
            throw e
        } catch (e: Throwable) {
            Log.e("DistroInstaller", "Install failed", e)
            cleanup(distro.name, keepPartialDownload = true)
            throw Exception("Install failed: ${e.message}", e)
        }
    }

    private fun tarballDir(): File =
        File(context.filesDir, "tarballs").apply { mkdirs() }

    fun hasCachedTarball(distroName: String): Boolean =
        File(tarballDir(), "$distroName.tar.xz").exists()

    /**
     * Restores a distro to its freshly extracted state: wipes installed
     * packages, caches and shell configs. Uses the cached base tarball when
     * available, otherwise falls back to a fresh download.
     */
    suspend fun resetToDefault(
        distroName: String,
        onProgress: (Progress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        val distro = com.redtermapp.distro.DistroRegistry.allDistros
            .firstOrNull { it.name == distroName }
        if (distro == null) return@withContext false
        try {
            val rootfsDir = getRootfsDir(distroName)
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val tarball = File(tarballDir(), "$distroName.tar.xz")
            val expectedSha = distro.sha256For(deviceAbi)
            val tarballUsable = tarball.exists() &&
                (expectedSha.isEmpty() || checksumMatches(tarball, expectedSha))
            if (!tarballUsable) {
                install(distro, onProgress)
                return@withContext true
            }
            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir, distro)
            saveInstalled(distroName)
            Log.i("DistroInstaller", "Reset complete for $distroName")
            true
        } catch (e: CancelledException) {
            cleanup(distroName, keepPartialDownload = false)
            false
        } catch (e: Throwable) {
            Log.e("DistroInstaller", "Reset failed", e)
            cleanup(distroName, keepPartialDownload = true)
            throw Exception("Reset failed: ${e.message}", e)
        }
    }

    class CancelledException : Exception("Installation cancelled")

    private fun checkCancel() {
        if (cancelled) throw CancelledException()
    }

    private fun cleanup(distroName: String, keepPartialDownload: Boolean) {
        try {
            getRootfsDir(distroName).deleteRecursively()
        } catch (_: Exception) {}
        if (!keepPartialDownload) {
            try {
                File(tarballDir(), "$distroName.tar.xz.part").delete()
            } catch (_: Exception) {}
        }
        try {
            File(context.cacheDir, "${distroName}.tar.xz").delete()
        } catch (_: Exception) {}
        try {
            File(context.filesDir, "installed/$distroName").delete()
        } catch (_: Exception) {}
        clearSizeCache(distroName)
    }

    private fun progressFor(downloaded: Long, total: Long): Int =
        if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0

    private suspend fun downloadTarball(
        urlString: String,
        partial: File,
        onProgress: (Progress) -> Unit,
        allowRestart: Boolean = true
    ) {
        partial.parentFile?.mkdirs()
        var resumeFrom = if (partial.exists()) partial.length() else 0L
        if (resumeFrom <= 0L) {
            partial.delete()
            resumeFrom = 0L
        }
        checkCancel()

        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        if (resumeFrom > 0L) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && allowRestart && resumeFrom > 0L) {
            conn.disconnect()
            partial.delete()
            downloadTarball(urlString, partial, onProgress, allowRestart = false)
            return
        }

        val resumed = responseCode == HTTP_PARTIAL && resumeFrom > 0L
        if (responseCode != HTTP_OK && !resumed) {
            conn.disconnect()
            throw Exception("HTTP $responseCode for $urlString")
        }

        val alreadyDownloaded = if (resumed) resumeFrom else 0L
        val contentLength = conn.contentLengthLong
        val total = if (contentLength > 0L) alreadyDownloaded + contentLength else 0L
        if (resumed) {
            onProgress(Progress(progressFor(alreadyDownloaded, total), "Resuming"))
        }

        try {
            val buffer = ByteArray(16384)
            FileOutputStream(partial, resumed).use { output ->
                conn.inputStream.use { input ->
                    var runBytes = 0L
                    val startTime = System.currentTimeMillis()
                    while (true) {
                        checkCancel()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        runBytes += read
                        val elapsed = ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(1L)
                        onProgress(
                            Progress(
                                progressFor(alreadyDownloaded + runBytes, total),
                                "${runBytes / 1024 / elapsed} KB/s"
                            )
                        )
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            if (!cancelled && partial.length() == 0L) partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun checksumMatches(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    private fun getNativeXz(): File? {
        val abis = android.os.Build.SUPPORTED_64_BIT_ABIS
        if (abis.isEmpty() || abis[0] != "arm64-v8a") return null
        val xzDir = File(context.codeCacheDir, "xz")
        val xzBin = File(xzDir, "xz")
        val xzLib = File(xzDir, "liblzma.so.5")
        if (xzBin.canExecute() && xzLib.canRead()) return xzBin
        try {
            xzDir.mkdirs()
            context.assets.open("xz/lib/liblzma.so.5").use { input ->
                FileOutputStream(xzLib).use { input.copyTo(it) }
            }
            xzLib.setReadable(true, true)
            context.assets.open("xz/bin/xz").use { input ->
                FileOutputStream(xzBin).use { input.copyTo(it) }
            }
            xzBin.setReadable(true, true)
            xzBin.setExecutable(true, true)
            if (xzBin.canExecute()) return xzBin
        } catch (e: Exception) {
            Log.w("DistroInstaller", "Native xz not available", e)
            xzBin.delete()
            xzLib.delete()
        }
        return null
    }

    private suspend fun extractTarball(
        tarball: File,
        dest: File,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val nativeXz = getNativeXz()
        if (nativeXz != null) {
            try {
                extractWithNativeXz(nativeXz, tarball, dest, onProgress)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w("DistroInstaller", "Native xz failed, falling back to Java", e)
                extractWithJavaXz(tarball, dest, onProgress)
            }
        } else {
            extractWithJavaXz(tarball, dest, onProgress)
        }
    }

    private fun extractWithNativeXz(
        xzBin: File, tarball: File, dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val pb = ProcessBuilder(xzBin.absolutePath, "-dc", tarball.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = xzBin.parentFile!!.absolutePath
        val process = pb.start()
        try {
            process.inputStream.use { input ->
                BufferedInputStream(input, 65536).use { bis ->
                    TarArchiveInputStream(bis).use { tarIn ->
                        extractTarEntries(tarIn, dest, tarball.length(), onProgress)
                    }
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Exception("Native xz decompressor failed (exit $exitCode), falling back")
            }
        } catch (e: CancelledException) {
            killProcess(process)
            throw e
        } catch (e: Exception) {
            killProcess(process)
            throw e
        }
    }

    private fun killProcess(process: Process) {
        process.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        }
    }

    private fun extractWithJavaXz(
        tarball: File, dest: File,
        onProgress: (Progress) -> Unit
    ) {
        try {
            val total = tarball.length()
            var extracted = 0L
            FileInputStream(tarball).use { fis ->
                XZCompressorInputStream(fis).use { xzIn ->
                    BufferedInputStream(xzIn, 65536).use { bis ->
                        TarArchiveInputStream(bis).use { tarIn ->
                            extractTarEntries(tarIn, dest, total, onProgress)
                        }
                    }
                }
            }
        } catch (e: NoClassDefFoundError) {
            throw Exception("Missing compression library: ${e.message}")
        }
    }

    private fun extractTarEntries(
        tarIn: TarArchiveInputStream, dest: File,
        totalCompressed: Long, onProgress: (Progress) -> Unit
    ) {
        val firstEntry = tarIn.getNextEntry()
        var prefixToStrip = ""
        if (firstEntry != null) {
            val name = firstEntry.name
            val slash = name.indexOf('/')
            if (slash > 0) {
                prefixToStrip = name.substring(0, slash + 1)
                Log.i("DistroInstaller", "Stripping prefix: $prefixToStrip")
            }
        }
        var processed = 0L
        var entryCount = 0
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
                    android.system.Os.symlink(linkTarget, target.absolutePath)
                } catch (e: Exception) {
                    Log.w("DistroInstaller", "Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
                chmod(target, DIR_MODE)
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
                val perm = entry.mode and 0x1FF
                val isExec = (perm and 0b001001001) != 0
                chmod(target, if (isExec) FILE_EXEC_MODE else FILE_MODE)
            }
        }
        if (firstEntry != null) processEntry(firstEntry)
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel()
            processEntry(entry)
            entryCount++
            val pct = if (totalCompressed > 0) {
                ((processed * 100L) / (totalCompressed * 3L)).toInt().coerceAtMost(99)
            } else 0
            onProgress(Progress(pct, "Extracting"))
            entry = tarIn.getNextEntry()
        }
    }

    private fun getAndroidDnsServers(): List<String> =
        DnsHelper.getAndroidDnsServers(context)

    private fun writeResolvConf(rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        val lines = mutableListOf<String>()
        val dns = getAndroidDnsServers()
        for (s in dns) {
            lines.add("nameserver $s")
        }
        if (dns.size < 3) {
            for (fallback in listOf("8.8.8.8", "1.1.1.1")) {
                if (!lines.any { it.contains(fallback) }) {
                    lines.add("nameserver $fallback")
                }
            }
        }
        safeWriteText(resolv, lines.joinToString("\n") + "\n")
    }

    private fun ensureSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existing = if (group.exists()) group.readText() else ""
        val sb = StringBuilder(existing)
        val baseEntries = listOf(
            "root:x:0:root", "wheel:x:0:root",
            "inet:x:3003:", "everybody:x:9997:"
        )
        for (entry in baseEntries) {
            val name = entry.substringBefore(':')
            if (!existing.contains(":$name")) {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                sb.append(entry).append('\n')
            }
        }
        try {
            val status = java.io.File("/proc/self/status").readLines()
            val groupsLine = status.firstOrNull { it.startsWith("Groups:") } ?: return
            val gids = groupsLine.removePrefix("Groups:").trim().split("\\s+".toRegex())
            for (gidStr in gids) {
                val gid = gidStr.toIntOrNull() ?: continue
                if (gid <= 0) continue
                val name = "android_$gid"
                if (!existing.contains(":$name:")) {
                    if (!sb.endsWith('\n')) sb.append('\n')
                    sb.append("$name:x:$gid:\n")
                }
            }
        } catch (_: Exception) {}
        safeWriteText(group, sb.toString())
    }

    private fun ensureWritable(file: File) {
        if (file.exists()) ensureOwnerAccess(file)
        file.parentFile?.let { if (!it.canWrite()) ensureOwnerAccess(it) }
    }

    private fun safeWriteText(file: File, text: String) {
        ensureWritable(file)
        file.writeText(text)
    }

    private fun safeAppendText(file: File, text: String) {
        ensureWritable(file)
        file.appendText(text)
    }

    private fun setupRootfs(rootfs: File, distro: Distro) {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
        }
        writeResolvConf(rootfs)
        val fstab = File(rootfs, "etc/fstab")
        if (!fstab.exists()) {
            safeWriteText(fstab, "none /proc proc defaults 0 0\nnone /sys sysfs defaults 0 0\n")
        }
        createDeviceNodes(rootfs)
        repairRootfs(rootfs)
    }

    private fun fixupDirectoryPermissions(rootfs: File) {
        rootfs.walkTopDown().filter { it.isDirectory }.forEach { d ->
            chmod(d, DIR_MODE)
        }
    }

    private fun ensureOwnerAccess(file: File) {
        chmod(file, if (file.isDirectory) DIR_MODE else FILE_MODE)
    }

    private fun chmod(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.absolutePath, mode)
        } catch (e: Exception) {
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(file.isDirectory || !file.name.endsWith(".so"), true)
        }
    }

    fun repairRootfs(rootfs: File): String {
        val repairs = mutableListOf<String>()
        val uid = android.os.Process.myUid()

        if (!File(rootfs, ".perms_fixed").exists()) {
            fixupDirectoryPermissions(rootfs)
            try {
                File(rootfs, ".perms_fixed").writeText("1")
            } catch (_: Exception) {}
            repairs.add("Fixed directory permissions")
        }

        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
            repairs.add("Added passwd entry for uid $uid")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
            repairs.add("Created /etc/hosts")
        }

        File(rootfs, "root").mkdirs()
        repairs.add("Created /root")

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
                busybox.setExecutable(true, true)
                repairs.add("Made bin/busybox executable")
            }
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                sh.delete()
                try {
                    android.system.Os.symlink("busybox", sh.absolutePath)
                } catch (_: Exception) {
                    busybox.copyTo(sh, overwrite = true)
                    sh.setExecutable(true, true)
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
            writeResolvConf(rootfs)
            repairs.add("Created etc/resolv.conf with Android DNS")
        } else {
            val dns = getAndroidDnsServers()
            val content = resolv.readText()
            val needsDns = dns.any { !content.contains(it) }
            if (needsDns) {
                writeResolvConf(rootfs)
                repairs.add("Updated etc/resolv.conf with Android DNS")
            }
        }

        return repairs.joinToString("\n")
    }

    fun isInstalled(distroName: String): Boolean =
        File(context.filesDir, "installed/${distroName}").exists()

    fun getRootfsDir(distroName: String): File =
        File(context.filesDir, "rootfs/$distroName")

    fun saveInstalled(distroName: String) {
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
        File(tarballDir(), "$distroName.tar.xz").delete()
        File(tarballDir(), "$distroName.tar.xz.part").delete()
        File(context.cacheDir, "${distroName}.tar.xz").delete()
        clearSizeCache(distroName)
    }

    fun detectDistro(rootfsDir: File): String {
        val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
        return when {
            osRelease.contains("Alpine", ignoreCase = true) -> "alpine"
            osRelease.contains("Ubuntu", ignoreCase = true) -> "ubuntu"
            osRelease.contains("Debian", ignoreCase = true) -> "debian"
            File(rootfsDir, "etc/fedora-release").exists() || osRelease.contains("Fedora", ignoreCase = true) -> "fedora"
            osRelease.contains("Void", ignoreCase = true) -> "void"
            osRelease.contains("Manjaro", ignoreCase = true) -> "manjaro"
            osRelease.contains("Arch Linux", ignoreCase = true) -> "arch"
            osRelease.contains("Rocky Linux", ignoreCase = true) -> "rocky"
            osRelease.contains("AlmaLinux", ignoreCase = true) -> "almalinux"
            osRelease.contains("openSUSE", ignoreCase = true) -> "opensuse"
            File(rootfsDir, "etc/debian_version").exists() -> "debian"
            else -> "unknown"
        }
    }

    private fun createDeviceNodes(rootfs: File) {
        val devDir = File(rootfs, "dev")
        devDir.mkdirs()
        for (dev in listOf("null", "zero", "random", "urandom")) {
            val f = File(devDir, dev)
            if (!f.exists()) {
                try {
                    f.writeText("")
                } catch (_: Exception) {}
            }
        }
    }
}
