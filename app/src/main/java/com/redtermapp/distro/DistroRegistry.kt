package com.redtermapp.distro

object DistroRegistry {
    private const val TERMUX_BASE = "https://github.com/termux/proot-distro/releases/download"

    val allDistros: List<Distro> = listOf(
        Distro(
            name = "alpine",
            displayName = "Alpine",
            description = "Minimal, fast, security-focused.",
            baseUrl = "$TERMUX_BASE/v4.30.1/alpine-{arch}-pd-v4.30.1.tar.xz",
            sha256 = mapOf(
                "aarch64" to "bb23e51cd5b5ae56bf946a34992876902de1bb2ecc0f639d59c702c6371adc62",
                "x86_64" to "0890920f83becc1c3529ca53fc71d7516a01d3de4139fbe936c8c60c6c32f8d1",
                "arm" to "ca1039d26481b63a412cd39d699c7f559c40ed5c532573c720e00218b5af0fd4",
                "i686" to "11ae8bbd14f789b260eb5b0c89d267e427d57e9adb1e1f71458a911c4891ecae"
            ),
            prootArchs = listOf("aarch64", "x86_64", "arm", "i686"),
            installSizeMb = 30,
            packageManager = "apk"
        ),
        Distro(
            name = "arch",
            displayName = "Arch",
            description = "Rolling release, latest packages.",
            baseUrl = "$TERMUX_BASE/v4.34.2/archlinux-{arch}-pd-v4.34.2.tar.xz",
            sha256 = mapOf(
                "aarch64" to "dabc2382ddcb725969cf7b9e2f3b102ec862ea6e0294198a30c71e9a4b837f81",
                "x86_64" to "5829c102ff1789d0e026ede65685221433e0b5c18002e70471a52b752c761be2",
                "arm" to "811bc341419c08b68f3c9ee68af546f8195f82660d9a11c54d916c1c353b8d90",
                "i686" to "a81ba4e23e7ea4aa1dfb097f6068927e417ce7757025bb03619ec9874bbfbfc7"
            ),
            prootArchs = listOf("aarch64", "x86_64", "arm", "i686"),
            installSizeMb = 450,
            packageManager = "pacman"
        ),
        Distro(
            name = "debian",
            displayName = "Debian",
            description = "Stable, well-supported, large package repository.",
            baseUrl = "$TERMUX_BASE/v4.29.0/debian-trixie-{arch}-pd-v4.29.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                "x86_64" to "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                "arm" to "99bcba87d8d1c66c0de06259ac0a270eb0a20f8b4af39beb0705d28846d78b90",
                "i686" to "a388a0531301b033ef5509ab6a50cc886f7f90e7ec9cac02569b45af1900229a"
            ),
            prootArchs = listOf("aarch64", "x86_64", "arm", "i686"),
            installSizeMb = 300,
            packageManager = "apt"
        ),
        Distro(
            name = "void",
            displayName = "Void",
            description = "Rolling release, fast package manager.",
            baseUrl = "$TERMUX_BASE/v4.29.0/void-{arch}-pd-v4.29.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "7a7c449b3efe504749e40f556d13812010bccc930a820a56973a0f5fc2f16997",
                "x86_64" to "2853b9433b9051aa2512e7376a71736196fb3241eb90ba11110c6e867854c666",
                "arm" to "5cb87c0ca8ee91047f3634789314920be6d914ce4f196157cb3949706ce18d03",
                "i686" to "0ad014426c1e0dc7a0cfe8175157b28b2a8cb75b83d7f44b7bbc35420125a269"
            ),
            prootArchs = listOf("aarch64", "x86_64", "arm", "i686"),
            installSizeMb = 200,
            packageManager = "xbps"
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "$TERMUX_BASE/v4.30.1/ubuntu-questing-{arch}-pd-v4.30.1.tar.xz",
            sha256 = mapOf(
                "aarch64" to "5ab35b90cd9a9f180656261ba400a135c4c01c2da4b74522118342f985c2d328",
                "x86_64" to "74f7c8492a2f3e720d5aa89de6572cbb90b14c4b21dee87ab33416b6fb1088c3",
                "arm" to "b074efe535b565f426219f20b35af0c4a7b3d0bc18ebd4fa11ccbd7370315b53"
            ),
            prootArchs = listOf("aarch64", "x86_64", "arm"),
            installSizeMb = 350,
            packageManager = "apt"
        ),
        Distro(
            name = "almalinux",
            displayName = "Alma",
            description = "Stable RHEL-compatible, bug-for-bug with CentOS.",
            baseUrl = "$TERMUX_BASE/v4.31.0/almalinux-{arch}-pd-v4.31.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "c01b1e2a19c4ff4cc927f625cac0c8814241fcae0d630250246b1891eff41ef5",
                "x86_64" to "60580889a052a2d1ccef4d478830a88b716b81bc934fc3ae27478dc9e13a3a55"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
        Distro(
            name = "rocky",
            displayName = "Rocky",
            description = "RHEL-compatible enterprise distro.",
            baseUrl = "$TERMUX_BASE/v4.29.0/rocky-{arch}-pd-v4.29.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "dc8389b3a1d3b8c8cc2448b49f8214463d4ec8b62eae15200d90a603485d43c1",
                "x86_64" to "85ff3e809544c8f4c619ccf2fde19f9d8e3b6405041a87c99d60317b402ae0dc"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "dnf"
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora",
            description = "Modern, innovative, upstream for RHEL.",
            baseUrl = "$TERMUX_BASE/v4.31.0/fedora-{arch}-pd-v4.31.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "e3c0aca71572ed343a29c6f41c6150583bf840f903047bb97b1ec45cefe95865",
                "x86_64" to "f8d43b3b82be1131a1a61e7bb2bfe7170a232e643855ee4fe3ac07b4c110ab97"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 450,
            packageManager = "dnf"
        ),
        Distro(
            name = "manjaro",
            displayName = "Manjaro",
            description = "User-friendly Arch-based rolling release.",
            baseUrl = "$TERMUX_BASE/v4.34.2/manjaro-{arch}-pd-v4.34.2.tar.xz",
            sha256 = mapOf(
                "aarch64" to "9778ecfc6efb623a20b441b901da433d5703d570546245ee8179dcede2921544"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 400,
            packageManager = "pacman"
        ),
        Distro(
            name = "opensuse",
            displayName = "openSUSE",
            description = "Robust, flexible, enterprise-grade Linux.",
            baseUrl = "$TERMUX_BASE/v4.34.2/opensuse-{arch}-pd-v4.34.2.tar.xz",
            sha256 = mapOf(
                "aarch64" to "ac161ca81deb7a0418f382d66c2110802b90481cfe0f925b02b69a5642674379",
                "x86_64" to "6e817d9f188f4a3a173b7a928b45c0b250189ba87c2eb74a953235c8c14a7f58"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 400,
            packageManager = "zypper"
        )
    )

    fun forDevice(abi: String): List<Distro> {
        val mapped = abiToProotArch(abi)
        return allDistros.filter { it.prootArchs.any { arch -> arch == mapped } }
    }
}
