package com.redtermapp.distro

object DistroRegistry {
    val allDistros: List<Distro> = listOf(
        Distro(
            name = "almalinux",
            displayName = "AlmaLinux 9",
            description = "Stable RHEL-compatible, bug-for-bug with CentOS. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/almalinux-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "3e58affaf2b8c7c4999bb1f17bd773fe9447c6b7f8f2213caae82289b371a224"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
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
            installSizeMb = 30,
            packageManager = "apk"
        ),
        Distro(
            name = "arch",
            displayName = "Arch Linux",
            description = "Rolling release, latest packages. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/archlinux-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 450,
            packageManager = "pacman"
        ),
        Distro(
            name = "artix",
            displayName = "Artix Linux",
            description = "Arch without systemd (OpenRC/Runit). Rolling release.",
            baseUrl = "https://easycli.sh/proot-distro/artix-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "fe499e00903db5342969ea2d87a97349c78b43e4cb53f0388cec5ad8cc35e92c"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 500,
            packageManager = "pacman"
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
            installSizeMb = 300,
            packageManager = "apt"
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora 43",
            description = "Modern, innovative, upstream for RHEL. 64-bit only.",
            baseUrl = "https://easycli.sh/proot-distro/fedora-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "eb86202ef9887dc315e93c627bef3b6a825da871129ab3de91466ab2c2e06019",
                "x86_64" to "0daac2fe47dbfcdbcc89e8e92c7a59db4a3c78b3c226e4b4a04e6c2ec582bfd4"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 450,
            packageManager = "dnf"
        ),
        Distro(
            name = "kali",
            displayName = "Kali Linux",
            description = "Penetration testing and security research.",
            baseUrl = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-{arch}.tar.xz",
            sha256 = mapOf(
                "aarch64" to "",
                "arm" to "19e3fbf28228257a2d9ec8e0bd05f0c931168941c006a313722084859dd11d1b"
            ),
            prootArchs = listOf("aarch64", "arm"),
            installSizeMb = 500,
            packageManager = "apt",
            archOverride = mapOf("aarch64" to "arm64", "arm" to "armhf")
        ),
        Distro(
            name = "manjaro",
            displayName = "Manjaro",
            description = "User-friendly Arch-based rolling release. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/manjaro-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "90fd86130d440b6d6ed6408b21306189eb41fe07d0026aab836ae203a1c419a4"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 400,
            packageManager = "pacman"
        ),
        Distro(
            name = "rocky",
            displayName = "Rocky Linux 10",
            description = "RHEL-compatible enterprise distro. 64-bit ARM only.",
            baseUrl = "https://easycli.sh/proot-distro/rocky-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "0282a82a75e0b17aa0f72622847ee0bfda85fa84bb6cf49bc72c5515816c47f0"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu 24.04",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "https://github.com/termux/proot-distro/releases/download/v4.11.0/ubuntu-noble-{arch}-pd-v4.11.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "a8883244a7031559a2bd8dc16b7d8afc947930b611819d8a28a09545097a6ba5",
                "arm" to "dc5478e96f648e868d68c15c400338460088255d5d964bdfa33e5456ceea54ae",
                "x86_64" to "f024b1e17413737d8b385d22736d2e3eb2af9ba665fdbda1277bcca8f397e5a2"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 350,
            packageManager = "apt"
        ),
        Distro(
            name = "void",
            displayName = "Void Linux",
            description = "Rolling release, fast package manager.",
            baseUrl = "https://easycli.sh/proot-distro/void-{arch}-pd-v4.29.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "7a7c449b3efe504749e40f556d13812010bccc930a820a56973a0f5fc2f16997",
                "arm" to "5cb87c0ca8ee91047f3634789314920be6d914ce4f196157cb3949706ce18d03",
                "i686" to "0ad014426c1e0dc7a0cfe8175157b28b2a8cb75b83d7f44b7bbc35420125a269",
                "x86_64" to "2853b9433b9051aa2512e7376a71736196fb3241eb90ba11110c6e867854c666"
            ),
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
