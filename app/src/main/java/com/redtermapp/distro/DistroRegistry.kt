package com.redtermapp.distro

object DistroRegistry {
    private const val RELEASE_BASE = "https://github.com/GlobalTechInfo/RedTerm/releases/download/rootfs-v1"

    val allDistros: List<Distro> = listOf(
        Distro(
            name = "alpine",
            displayName = "Alpine Linux",
            description = "Minimal (~5MB), fast, security-focused.",
            baseUrl = "$RELEASE_BASE/alpine-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 30,
            packageManager = "apk"
        ),
        Distro(
            name = "almalinux",
            displayName = "AlmaLinux 10",
            description = "Stable RHEL-compatible, bug-for-bug with CentOS.",
            baseUrl = "$RELEASE_BASE/almalinux-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
        Distro(
            name = "arch",
            displayName = "Arch Linux",
            description = "Rolling release, latest packages.",
            baseUrl = "$RELEASE_BASE/arch-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 450,
            packageManager = "pacman"
        ),
        Distro(
            name = "artix",
            displayName = "Artix Linux",
            description = "Arch without systemd (OpenRC). Rolling release.",
            baseUrl = "$RELEASE_BASE/artix-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 500,
            packageManager = "pacman"
        ),
        Distro(
            name = "debian",
            displayName = "Debian 13.7",
            description = "Stable, well-supported, large package repository.",
            baseUrl = "$RELEASE_BASE/debian-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 300,
            packageManager = "apt"
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora 44",
            description = "Modern, innovative, upstream for RHEL.",
            baseUrl = "$RELEASE_BASE/fedora-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 450,
            packageManager = "dnf"
        ),
        Distro(
            name = "kali",
            displayName = "Kali 2026.2",
            description = "Penetration testing and security research.",
            baseUrl = "$RELEASE_BASE/kali-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 500,
            packageManager = "apt"
        ),
        Distro(
            name = "manjaro",
            displayName = "Manjaro",
            description = "User-friendly Arch-based rolling release.",
            baseUrl = "$RELEASE_BASE/manjaro-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "pacman"
        ),
        Distro(
            name = "rocky",
            displayName = "Rocky Linux 10",
            description = "RHEL-compatible enterprise distro.",
            baseUrl = "$RELEASE_BASE/rocky-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu 26.04",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "$RELEASE_BASE/ubuntu-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 350,
            packageManager = "apt"
        ),
        Distro(
            name = "void",
            displayName = "Void Linux",
            description = "Rolling release, fast package manager.",
            baseUrl = "$RELEASE_BASE/void-{arch}-rootfs.tar.xz",
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 200,
            packageManager = "xbps"
        )
    )

    fun forDevice(abi: String): List<Distro> {
        val mapped = abiToProotArch(abi)
        return allDistros.filter { it.prootArchs.any { arch -> arch == mapped } }
    }
}
