package com.redtermapp.proot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.io.File

object ProotRunner {

    fun buildCommand(
        context: Context,
        rootfsPath: String,
        distroName: String
    ): ProotCommand {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val prootTmpDir = File(context.cacheDir, "proot-tmp")
        prootTmpDir.mkdirs()
        val tmpDir = File(context.cacheDir, "tmp")
        tmpDir.mkdirs()

        val rootfs = File(rootfsPath)
        writeResolvConf(context, rootfs)
        writeSupplementaryGroups(rootfs)

        val shell = detectShell(rootfsPath)

        val fakeUidLib = extractFakeUidLib(context)

        val envVars = buildList {
            add("PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
            add("PROOT_LOADER=$nativeLibDir/libproot-loader.so")
            val loader32 = File("$nativeLibDir/libproot-loader32.so")
            if (loader32.exists()) {
                add("PROOT_LOADER_32=$nativeLibDir/libproot-loader32.so")
            }
            add("HOME=/root")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("TMPDIR=/tmp")
            add("SHELL=$shell")
            if (fakeUidLib != null) {
                add("LD_PRELOAD=/lib/libfakeuid.so")
            }
        }

        val args = mutableListOf<String>().apply {
            add("-0")
            add("-L")
            add("-r")
            add(rootfsPath)
            add("-w")
            add("/root")
            add("--link2symlink")
            if (fakeUidLib != null) {
                add("-b")
                add("${fakeUidLib.absolutePath}:/lib/libfakeuid.so")
            }
            for (sysMnt in listOf(
                "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                "/sdcard", "/storage", "/mnt",
                "/dev", "/proc", "/sys",
            )) {
                val f = File(sysMnt)
                if (f.canRead()) {
                    val resolved = try { f.canonicalPath } catch (_: Exception) { sysMnt }
                    add("-b")
                    add(resolved)
                }
            }
            add("-b")
            add("/dev/urandom:/dev/random")
            val procFd = File("/proc/self/fd")
            if (procFd.exists()) {
                add("-b")
                add("/proc/self/fd:/dev/fd")
                val fds = procFd.listFiles() ?: emptyArray()
                for (fd in fds) {
                    val name = fd.name
                    when (name) {
                        "0" -> { add("-b"); add("/proc/self/fd/0:/dev/stdin") }
                        "1" -> { add("-b"); add("/proc/self/fd/1:/dev/stdout") }
                        "2" -> { add("-b"); add("/proc/self/fd/2:/dev/stderr") }
                    }
                }
            }
            add(shell)
            add("--login")
        }

        val prootBin = "$nativeLibDir/libproot.so"

        return ProotCommand(
            prootBin = prootBin,
            args = args.toTypedArray(),
            env = envVars.toTypedArray()
        )
    }

    private fun getAndroidDnsServers(context: Context): List<String> {
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

    private fun writeResolvConf(context: Context, rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        val lines = mutableListOf<String>()
        val dns = getAndroidDnsServers(context)
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

    private fun writeSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existing = if (group.exists()) group.readText() else ""
        val sb = StringBuilder(existing)
        if (!existing.contains("\ninet:")) sb.append("inet:x:3003:\n")
        if (!existing.contains("\neverybody:")) sb.append("everybody:x:9997:\n")
        try {
            val status = java.io.File("/proc/self/status").readLines()
            val groupsLine = status.firstOrNull { it.startsWith("Groups:") } ?: return
            val gids = groupsLine.removePrefix("Groups:").trim().split("\\s+".toRegex())
            for (gidStr in gids) {
                val gid = gidStr.toIntOrNull() ?: continue
                if (gid <= 0) continue
                val name = "android_$gid"
                if (!existing.contains(":\n$name:") && !existing.contains(":$name:")) {
                    sb.append("$name:x:$gid:\n")
                }
            }
        } catch (_: Exception) {}
        group.writeText(sb.toString())
    }

    private fun extractFakeUidLib(context: Context): File? {
        val dir = File(context.codeCacheDir, "root-compat")
        val lib = File(dir, "libfakeuid.so")
        if (lib.canRead()) return lib
        try {
            dir.mkdirs()
            context.assets.open("root-compat/libfakeuid.so").use { input ->
                lib.outputStream().use { input.copyTo(it) }
            }
            lib.setReadable(true, false)
            if (lib.canRead()) return lib
        } catch (e: Exception) {
            android.util.Log.w("ProotRunner", "fakeuid not available", e)
        }
        return null
    }

    private fun detectShell(rootfsPath: String): String {
        val shells = listOf("/bin/bash", "/bin/sh", "/bin/ash")
        for (s in shells) {
            if (File("$rootfsPath$s").canExecute()) return s
        }
        return "/bin/sh"
    }
}

data class ProotCommand(
    val prootBin: String,
    val args: Array<String>,
    val env: Array<String>
)