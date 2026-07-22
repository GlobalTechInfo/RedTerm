package com.redtermapp.proot

import android.content.Context
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

        val shell = detectShell(rootfsPath)

        val androidEnv = mapOf(
            "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
            "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
            "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
            "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
            "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
            "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
            "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
            "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
            "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE"),
            "LD_LIBRARY_PATH" to nativeLibDir,
        )

        val envVars = buildList {
            add("PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
            add("PROOT_LOADER=$nativeLibDir/libproot-loader.so")
            val loader32 = File("$nativeLibDir/libproot-loader32.so")
            if (loader32.exists()) {
                add("PROOT_LOADER_32=$nativeLibDir/libproot-loader32.so")
            }
            add("HOME=/root")
            add("PATH=${System.getenv("PATH")}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("TMPDIR=${tmpDir.absolutePath}")
            add("SHELL=$shell")
            androidEnv.forEach { (key, value) ->
                if (value != null) add("$key=$value")
            }
        }

        val args = mutableListOf<String>().apply {
            add("-0")
            add("-r")
            add(rootfsPath)
            add("-w")
            add("/root")
            add("--link2symlink")
            // Bind mount Android system partitions (from ReTerminal init-host.sh)
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