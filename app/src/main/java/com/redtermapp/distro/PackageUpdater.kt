package com.redtermapp.distro

/**
 * Maps a distro to the command that updates its packages non-interactively.
 *
 * Every command here must never prompt, because it runs without a terminal on
 * stdin: an unexpected prompt would hang until the timeout.
 */
object PackageUpdater {

    data class Plan(
        val packageManager: String,
        val updateCommand: String,
        val listUpgradableCommand: String? = null
    )

    fun planFor(distroName: String, rootfsDir: java.io.File): Plan {
        val osRelease = try {
            java.io.File(rootfsDir, "etc/os-release").readText()
        } catch (_: Exception) {
            ""
        }
        val pm = when {
            java.io.File(rootfsDir, "etc/apk/world").exists() &&
                osRelease.contains("Alpine") -> "apk"
            java.io.File(rootfsDir, "usr/bin/pacman").exists() -> "pacman"
            java.io.File(rootfsDir, "usr/bin/dnf").exists() -> "dnf"
            java.io.File(rootfsDir, "usr/bin/zypper").exists() -> "zypper"
            java.io.File(rootfsDir, "usr/bin/xbps-install").exists() -> "xbps"
            java.io.File(rootfsDir, "usr/bin/apt-get").exists() -> "apt"
            else -> packageManagerFromName(distroName)
        }
        return planForManager(pm)
    }

    private fun packageManagerFromName(name: String): String = when (name.lowercase()) {
        "alpine" -> "apk"
        "arch", "manjaro" -> "pacman"
        "fedora", "rocky", "almalinux" -> "dnf"
        "opensuse" -> "zypper"
        "void" -> "xbps"
        "debian", "ubuntu", "kali" -> "apt"
        else -> "unknown"
    }

    fun planForManager(pm: String): Plan = when (pm) {
        // DEBIAN_FRONTEND stops apt from asking which config file to keep.
        "apt" -> Plan(
            "apt",
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -y && apt-get upgrade -y && apt-get autoremove -y"
        )
        "dnf" -> Plan("dnf", "dnf -y upgrade")
        "pacman" -> Plan("pacman", "pacman -Syu --noconfirm")
        // --non-interactive stops zypper from waiting on a licence prompt.
        "zypper" -> Plan("zypper", "zypper --non-interactive dup")
        "apk" -> Plan("apk", "apk update && apk upgrade")
        "xbps" -> Plan("xbps", "xbps-install -Syu")
        else -> Plan("unknown", "")
    }
}
