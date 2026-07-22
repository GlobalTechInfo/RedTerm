package com.redtermapp.distro

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
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
            xzLib.setReadable(true, false)
            context.assets.open("xz/bin/xz").use { input ->
                FileOutputStream(xzBin).use { input.copyTo(it) }
            }
            xzBin.setReadable(true, false)
            xzBin.setExecutable(true, false)
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
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
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
        val firstEntry = tarIn.getNextTarEntry()
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
        fun processEntry(entry: TarArchiveEntry) {
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
                        target.toPath(), java.nio.file.Paths.get(linkTarget)
                    )
                } catch (e: Exception) {
                    Log.w("DistroInstaller", "Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = tarIn.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        processed += read
                    }
                }
                val isExec = (entry.mode and 64) != 0 || (entry.mode and 1) != 0
                target.setReadable(true, false)
                target.setExecutable(isExec, false)
                target.setWritable(!isExec)
            }
        }
        if (firstEntry != null) processEntry(firstEntry)
        var entry = tarIn.getNextTarEntry()
        while (entry != null) {
            processEntry(entry)
            entryCount++
            val pct = if (totalCompressed > 0) {
                ((processed * 100L) / (totalCompressed * 3L)).toInt().coerceAtMost(99)
            } else 0
            onProgress(Progress(pct, "Extracting"))
            entry = tarIn.getNextTarEntry()
        }
    }

    private fun getAndroidDnsServers(): List<String> {
        val servers = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = cm.activeNetwork
            if (network != null) {
                val lp: LinkProperties? = cm.getLinkProperties(network)
                if (lp != null) {
                    for (addr in lp.dnsServers) {
                        val host = addr.hostAddress ?: continue
                        if (!servers.contains(host)) servers.add(host)
                    }
                }
            }
        } catch (_: Exception) {}
        if (servers.isEmpty()) {
            try {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java, String::class.java)
                for (i in 1..4) {
                    val value = get.invoke(null, "net.dns$i", "") as String
                    if (value.isNotEmpty() && !servers.contains(value)) {
                        servers.add(value)
                    }
                }
            } catch (_: Exception) {}
        }
        return servers
    }

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
        resolv.writeText(lines.joinToString("\n") + "\n")
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
        group.writeText(sb.toString())
    }

    private fun setupRootfs(rootfs: File, distro: Distro) {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            passwd.appendText("root:x:$uid:0:root:/root:/bin/sh\n")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        writeResolvConf(rootfs)
        val fstab = File(rootfs, "etc/fstab")
        if (!fstab.exists()) {
            fstab.writeText("none /proc proc defaults 0 0\nnone /sys sysfs defaults 0 0\n")
        }
        repairRootfs(rootfs)
    }

    fun repairRootfs(rootfs: File): String {
        val repairs = mutableListOf<String>()
        val uid = android.os.Process.myUid()

        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            passwd.appendText("root:x:$uid:0:root:/root:/bin/sh\n")
            repairs.add("Added passwd entry for uid $uid")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
            repairs.add("Created /etc/hosts")
        }

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
