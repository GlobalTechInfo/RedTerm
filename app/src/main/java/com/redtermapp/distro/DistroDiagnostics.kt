package com.redtermapp.distro

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Read-only health report for an installed rootfs. Every check is cheap and
 * safe to run on a live distro; nothing here modifies the filesystem.
 */
object DistroDiagnostics {

    data class Check(
        val name: String,
        val status: Status,
        val detail: String
    )

    enum class Status { OK, WARN, FAIL }

    data class Report(
        val distro: String,
        val arch: String,
        val rootfsPath: String,
        val sizeBytes: Long,
        val freeBytes: Long,
        val checks: List<Check>
    ) {
        val failures: Int get() = checks.count { it.status == Status.FAIL }
        val warnings: Int get() = checks.count { it.status == Status.WARN }
    }

    private fun ok(name: String, detail: String) = Check(name, Status.OK, detail)
    private fun warn(name: String, detail: String) = Check(name, Status.WARN, detail)
    private fun fail(name: String, detail: String) = Check(name, Status.FAIL, detail)

    fun inspect(context: Context, distroName: String, rootfs: File): Report {
        val uid = Process.myUid()
        val checks = mutableListOf<Check>()

        checks += if (rootfs.isDirectory) {
            ok("Rootfs", rootfs.absolutePath)
        } else {
            fail("Rootfs", "Missing at ${rootfs.absolutePath}")
        }

        // /etc/passwd must map the app uid to root, otherwise proot looks like
        // an unknown user and package managers refuse to work.
        val passwd = File(rootfs, "etc/passwd")
        checks += when {
            !passwd.isFile -> fail("User mapping", "No /etc/passwd")
            passwd.readText().contains(":$uid:") -> ok("User mapping", "uid $uid mapped to root")
            else -> fail("User mapping", "uid $uid is not present in /etc/passwd")
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        val nameservers = if (resolv.isFile) {
            resolv.readLines().count { it.trim().startsWith("nameserver") }
        } else {
            0
        }
        checks += when {
            nameservers > 0 -> ok("DNS", "$nameservers nameserver(s) configured")
            else -> fail("DNS", "No nameserver entries in /etc/resolv.conf")
        }

        val bash = File(rootfs, "bin/bash")
        val bashUsr = File(rootfs, "usr/bin/bash")
        checks += when {
            bashUsr.canExecute() || bash.canExecute() -> ok("Shell", "bash present")
            else -> fail("Shell", "bash is not installed in the rootfs")
        }

        // A missing busybox (or an unexecutable /bin/sh) breaks Alpine and the
        // busybox-based images outright.
        val busybox = File(rootfs, "bin/busybox")
        val sh = File(rootfs, "bin/sh")
        checks += when {
            !busybox.exists() -> warn("busybox", "Not present (normal for glibc distros)")
            busybox.canExecute() -> ok("busybox", "Present and executable")
            else -> fail("busybox", "Present but not executable")
        }
        checks += when {
            !sh.exists() -> warn("/bin/sh", "Missing")
            sh.canExecute() -> ok("/bin/sh", "Present and executable")
            else -> fail("/bin/sh", "Present but not executable")
        }

        // Wrong directory modes are what produced "directory permissions
        // differ on /usr" from pacman and friends.
        val usrMode = modeOf(File(rootfs, "usr"))
        checks += when {
            usrMode == null -> warn("Directory modes", "Could not read /usr permissions")
            (usrMode and 292) == 292 -> ok("Directory modes", "/usr is ${permString(usrMode)}")
            else -> fail("Directory modes", "/usr is ${permString(usrMode)}, expected at least r--r--r--")
        }

        val tmp = File(rootfs, "tmp")
        checks += if (tmp.isDirectory && tmp.canWrite()) {
            ok("/tmp", "Writable")
        } else {
            fail("/tmp", "Missing or not writable")
        }

        val dev = File(rootfs, "dev")
        val devNodes = listOf("null", "zero", "urandom", "random").count { File(dev, it).exists() }
        checks += when {
            !dev.isDirectory -> warn("/dev", "Directory missing")
            devNodes == 4 -> ok("/dev", "Core device nodes present")
            else -> warn("/dev", "$devNodes/4 device nodes present")
        }

        val sudo = File(rootfs, "usr/bin/sudo")
        checks += if (sudo.exists()) {
            ok("sudo", "Available")
        } else {
            warn("sudo", "Not installed (not required, proot already runs as root)")
        }

        val home = File(rootfs, "root")
        checks += if (home.isDirectory) {
            ok("Home", "/root present")
        } else {
            warn("Home", "/root missing")
        }

        val size = context.let {
            File(it.filesDir, "sizes/$distroName").takeIf { f -> f.isFile }
                ?.readText()?.trim()?.toLongOrNull() ?: -1L
        }
        val free = DistroInstaller(context).freeBytesAt(rootfs)

        return Report(
            distro = distroName,
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            rootfsPath = rootfs.absolutePath,
            sizeBytes = size,
            freeBytes = free,
            checks = checks
        )
    }

    private fun modeOf(file: File): Int? = try {
        if (file.exists()) {
            val canonical = file.canonicalPath
            android.system.Os.stat(canonical).st_mode and 511
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun permString(mode: Int): String {
        val sb = StringBuilder(9)
        for (group in 0..2) {
            val triple = (mode shr (6 - group * 3)) and 7
            sb.append(if (triple and 4 != 0) 'r' else '-')
            sb.append(if (triple and 2 != 0) 'w' else '-')
            sb.append(if (triple and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }
}
