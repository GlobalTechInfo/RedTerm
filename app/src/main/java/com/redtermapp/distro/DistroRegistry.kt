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
                "aarch64" to "a8883244a7031559a2bd8dc16b7d8afc947930b611819d8a28a09545097a6ba5",
                "arm" to "dc5478e96f648e868d68c15c400338460088255d5d964bdfa33e5456ceea54ae",
                "x86_64" to "f024b1e17413737d8b385d22736d2e3eb2af9ba665fdbda1277bcca8f397e5a2"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 350
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
            installSizeMb = 450
        ),
        Distro(
            name = "void",
            displayName = "Void Linux",
            description = "Rolling release, fast package manager (xbps).",
            baseUrl = "https://easycli.sh/proot-distro/void-{arch}-pd-v4.29.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "7a7c449b3efe504749e40f556d13812010bccc930a820a56973a0f5fc2f16997",
                "arm" to "5cb87c0ca8ee91047f3634789314920be6d914ce4f196157cb3949706ce18d03",
                "i686" to "0ad014426c1e0dc7a0cfe8175157b28b2a8cb75b83d7f44b7bbc35420125a269",
                "x86_64" to "2853b9433b9051aa2512e7376a71736196fb3241eb90ba11110c6e867854c666"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 200
        ),
        Distro(
            name = "opensuse",
            displayName = "openSUSE 16.0",
            description = "Stable, enterprise-grade. 64-bit only.",
            baseUrl = "https://easycli.sh/proot-distro/opensuse-{arch}-pd-v4.37.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "812bbed638f43b81846520bf4283c18da08e19f14714e56fffdc9ccad3c65d7a",
                "x86_64" to "56cd4b5bb298da2ad25d66ec5f180c0f577c7f70358f323c62c318f8b8530ff7"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 500
        ),
        Distro(
            name = "kali",
            displayName = "Kali Linux",
            description = "Penetration testing and security research. ARM only.",
            baseUrl = "https://images.kali.org/nethunter/rootfs/kali-nethunter-rootfs-minimal-armhf.tar.xz",
            sha256 = mapOf(
                "arm" to "19e3fbf28228257a2d9ec8e0bd05f0c931168941c006a313722084859dd11d1b"
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
