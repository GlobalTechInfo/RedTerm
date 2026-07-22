package com.redtermapp.distro

object DistroRegistry {
    val allDistros: List<Distro> = listOf(
        Distro(
            name = "alpine",
            displayName = "Alpine Linux",
            description = "Minimal (~5MB), fast, security-focused. Recommended for low disk space.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.6.0/alpine-{arch}-pd-v4.6.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "bffe6373dea84dce6a25c94f225ccdaec96c825710d655aa1f4cae79333edea6",
                "arm" to "c89482950298b4ea1e6f2b2bab9eb00697b8fefa313d56585c4f38e8e4ff2860",
                "i686" to "f002d1a02efdd5f7e6c81df69c99a74d0022eb56ab7558fa35ac4c5fe0d7d43b",
                "x86_64" to "7b3d51714226cfe1bc1a115316e9d1d9ebc3ac2eb92389bc0e5b5f01ac04ee0b"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 30
        ),
        Distro(
            name = "debian",
            displayName = "Debian 12-LTS",
            description = "Stable, well-supported, large package repository.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-{arch}-pd-v4.7.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8",
                "arm" to "0eba2cb93261d6e73c2f3c32ed7ebe9de408ceef584c5e0c0b7e237d294f7a8d",
                "i686" to "7425f5fe7f34c718428f235b9155adb782c29ce6347f704f4a93a9da195b9aa3",
                "x86_64" to "164932ab77a0b94a8e355c9b68158a5b76d5abef89ada509488c44ff54655d61"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 300
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu 24.04",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.11.0/ubuntu-noble-{arch}-pd-v4.11.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "",
                "arm" to "",
                "x86_64" to ""
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 350
        ),
        Distro(
            name = "archlinux",
            displayName = "Arch Linux",
            description = "Rolling release, cutting-edge packages.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.6.0/archlinux-{arch}-pd-v4.6.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "7e87d551845aedae5a111d1fdcc2f5a69b0805f365244f3fab3fe67cd4114f00",
                "arm" to "9edc60150ffdeae42b05fdcffdf06226641c442673f66b64af369504abe83a4b"
            ),
            prootArchs = listOf("aarch64", "arm"),
            installSizeMb = 400
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora 39",
            description = "Modern, innovative, upstream for RHEL. 64-bit only.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.6.0/fedora-{arch}-pd-v4.6.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "920caf3290ddaf9347de51ccadb0b6391c0244286072a6664fb1600eee360b9c",
                "x86_64" to "49ffa79c24db6a2ee664b2e29268e534c11e1a984b694f8c56551ddb12dde8b3"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 450
        ),
        Distro(
            name = "kali",
            displayName = "Kali Linux",
            description = "Penetration testing and security research. ARM only.",
            baseUrl = "https://images.kali.org/nethunter/rootfs/kali-nethunter-rootfs-minimal-armhf.tar.xz",
            sha256 = mapOf(
                "arm" to ""
            ),
            prootArchs = listOf("arm"),
            installSizeMb = 500
        )
    )

    fun forDevice(abi: String): List<Distro> {
        val mapped = abiToProotArch(abi)
        return allDistros.filter { it.prootArchs.any { arch -> arch == mapped } }
    }
}
