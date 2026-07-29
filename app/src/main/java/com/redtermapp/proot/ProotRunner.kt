package com.redtermapp.proot

import android.content.Context
import java.io.File
import com.redtermapp.DnsHelper

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

        val fakeUidLib = extractFakeUidLib(context)
        val procDir = createSyntheticProc(context)

        val envVars = buildList {
            add("PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
            add("PROOT_LOADER=$nativeLibDir/libproot-loader.so")
            val loader32 = File("$nativeLibDir/libproot-loader32.so")
            if (loader32.exists()) {
                add("PROOT_LOADER_32=$nativeLibDir/libproot-loader32.so")
            }
            add("HOME=/root")
            add("PATH=/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("TMPDIR=/tmp")
            add("SHELL=sh")
            add("LANG=C.UTF-8")
            add("COLORTERM=truecolor")
            if (fakeUidLib != null) {
                add("LD_PRELOAD=/lib/libfakeuid.so")
            }
            val androidVars = mapOf(
                "ANDROID_ROOT" to "ANDROID_ROOT",
                "ANDROID_DATA" to "ANDROID_DATA",
                "ANDROID_RUNTIME_ROOT" to "ANDROID_RUNTIME_ROOT",
                "ANDROID_TZDATA_ROOT" to "ANDROID_TZDATA_ROOT",
                "ANDROID_ART_ROOT" to "ANDROID_ART_ROOT",
                "ANDROID_I18N_ROOT" to "ANDROID_I18N_ROOT",
                "BOOTCLASSPATH" to "BOOTCLASSPATH",
                "DEX2OATBOOTCLASSPATH" to "DEX2OATBOOTCLASSPATH",
                "EXTERNAL_STORAGE" to "EXTERNAL_STORAGE",
            )
            for ((key, _) in androidVars) {
                System.getenv(key)?.let { add("$key=$it") }
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
            add("--sysvipc")
            add("--kill-on-exit")
            if (fakeUidLib != null) {
                add("-b")
                add("${fakeUidLib.absolutePath}:/lib/libfakeuid.so")
            }
            for (sysMnt in listOf(
                "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                "/linkerconfig/ld.config.txt",
                "/linkerconfig/com.android.art/ld.config.txt",
                "/plat_property_contexts", "/property_contexts",
                "/sdcard", "/storage", "/mnt", "/data",
                "/dev", "/proc", "/sys",
            )) {
                val f = File(sysMnt)
                if (f.exists()) {
                    val resolved = try { f.canonicalPath } catch (_: Exception) { sysMnt }
                    add("-b")
                    add(resolved)
                }
            }
            add("-b")
            add("/dev/urandom:/dev/random")
            if (procDir != null) {
                add("-b")
                add("${procDir.absolutePath}/stat:/proc/stat")
                add("-b")
                add("${procDir.absolutePath}/vmstat:/proc/vmstat")
            }
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
            add("sh")
            add("--login")
        }

        val prootBin = "$nativeLibDir/libproot.so"

        return ProotCommand(
            prootBin = prootBin,
            args = args.toTypedArray(),
            env = envVars.toTypedArray()
        )
    }

    private fun createSyntheticProc(context: Context): File? {
        val dir = File(context.cacheDir, "proc-synthetic")
        dir.mkdirs()
        try {
            val statFile = File(dir, "stat")
            if (!statFile.exists()) {
                statFile.writeText(
                    """
cpu  0 0 0 0 0 0 0 0 0 0
intr 0 0 0 0 0 0 0 0 0 0
ctxt 0
btime ${System.currentTimeMillis() / 1000}
processes 0
procs_running 1
procs_blocked 0
""".trimStart()
                )
            }
            val vmstatFile = File(dir, "vmstat")
            if (!vmstatFile.exists()) {
                vmstatFile.writeText(
                    """
nr_free_pages 100000
nr_alloc_batch 0
nr_inactive_anon 0
nr_active_anon 0
nr_inactive_file 0
nr_active_file 0
nr_unevictable 0
nr_mlock 0
nr_anon_pages 0
nr_mapped 0
nr_file_pages 0
nr_dirty 0
nr_writeback 0
nr_slab_reclaimable 0
nr_slab_unreclaimable 0
nr_page_table_pages 0
nr_kernel_stack 0
nr_unstable 0
nr_bounce 0
nr_vmscan_write 0
nr_vmscan_immediate_reclaim 0
nr_writeback_temp 0
nr_isolated_anon 0
nr_isolated_file 0
nr_shmem 0
nr_dirtied 0
nr_written 0
nr_pages_scanned 0
pgpgin 0
pgpgout 0
pswpin 0
pswpout 0
pgalloc_dma 0
pgalloc_dma32 0
pgalloc_normal 0
pgalloc_movable 0
pgfree 0
pgactivate 0
pgdeactivate 0
pgfault 0
pgmajfault 0
pgrefill_dma 0
pgrefill_dma32 0
pgrefill_normal 0
pgrefill_movable 0
pgsteal_kswapd_dma 0
pgsteal_kswapd_dma32 0
pgsteal_kswapd_normal 0
pgsteal_kswapd_movable 0
pgsteal_direct_dma 0
pgsteal_direct_dma32 0
pgsteal_direct_normal 0
pgsteal_direct_movable 0
pgscan_kswapd_dma 0
pgscan_kswapd_dma32 0
pgscan_kswapd_normal 0
pgscan_kswapd_movable 0
pgscan_direct_dma 0
pgscan_direct_dma32 0
pgscan_direct_normal 0
pgscan_direct_movable 0
oom_kill 0
""".trimStart()
                )
            }
            return dir
        } catch (_: Exception) {
            return null
        }
    }

    private fun getAndroidDnsServers(context: Context): List<String> =
        DnsHelper.getAndroidDnsServers(context)

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

}

data class ProotCommand(
    val prootBin: String,
    val args: Array<String>,
    val env: Array<String>
)
