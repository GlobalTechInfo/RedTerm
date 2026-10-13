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
        "debian", "ubuntu", "kali", "pardus", "parrot", "trisquel" -> "apt"
        // Gentoo's package manager is emerge. The package is called portage and
        // installs no /usr/bin/portage, so advertising that name produced a welcome
        // screen telling the user to run a command that does not exist.
        "gentoo" -> "emerge"
        "slackware" -> "slackpkg"
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
        // Sync only.
        //
        // -uDN @world is the documented way to update Gentoo, and it is what this used
        // to run. It is also thousands of builds from source, and builds from source
        // are exactly what fails here -- an install of an ordinary package dies in
        // dev-perl/Crypt-URandom on a toolchain that is present and working.
        //
        // So the button refreshes the portage tree and stops. That is the part that
        // is cheap and always works; it does not claim to have updated anything.
        "emerge" -> Plan("emerge", "emerge --sync")
        // -batch=on stops it waiting on the mirror selection prompt, which a
        // terminal with no input would otherwise block on forever.
        "slackpkg" -> Plan(
            "slackpkg",
            "slackpkg -batch=on -default_answer=yes update && " +
                "slackpkg -batch=on -default_answer=yes upgrade-all"
        )
        else -> Plan("unknown", "")
    }
}
