package com.redtermapp.distro

object DistroRegistry {
    /**
     * The built-in catalogue: what the app ships with, and what it falls back to.
     *
     * Every URL points at this project's own GitHub release, built by the
     * rootfs-images repository from each distribution's upstream base. They are a
     * pinned v1.0.0 fallback, so an install never depends on a network fetch
     * succeeding: [applyIndex] overrides these when a newer index is fetched.
     *
     * Ordered alphabetically by name. The list was previously in no particular order,
     * which made a 21-distro catalogue hard to scan and easy to insert in the wrong
     * place when adding one.
     *
     * FreeBSD is built and published by rootfs-images but is deliberately absent here.
     * proot is a ptrace-based Linux syscall translator; a FreeBSD userland makes
     * different syscalls, so a session exits immediately with nothing to show for it.
     * The image is sound and stays published -- the image and the catalogue are
     * different questions, and removing the entry stops offering something that
     * cannot run rather than pretending the build failed.
     */
    private val builtin: List<Distro> = listOf(
        Distro(
            name = "adelie",
            displayName = "Adelie",
            description = "Independent, musl-based, minimal.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/adelie-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "6abe87902a9b89a594b0d47cb460d69d9e046f09e27c031d5e88bc00861e79c2",
                "arm" to "1d1dcefd712ec443237d39d1f02c9ba7ff02313e69b6086d7f82b7cf3e2ef4eb",
                "x86_64" to "b46e9393e74b973c98133ab96b422a7645c34a3176e18c2359c399ca60967205"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 28,
            packageManager = "apk"
        ),
        Distro(
            name = "almalinux",
            displayName = "Alma",
            description = "Stable RHEL-compatible, bug-for-bug with CentOS.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/almalinux-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "889c9ac83f14c7fc82653ac80b45e0ca62c0352d56308c073d3dbc788e957ba7",
                "x86_64" to "a178b4dc0d9e012325ccc30e12b22f3fa1700b8437a58639ccacd769e2bee352"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 172,
            packageManager = "dnf"
        ),
        Distro(
            name = "alpine",
            displayName = "Alpine",
            description = "Minimal, fast, security-focused.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/alpine-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "9d547e19bfbcf062330abc3d3157b718141ea7ef65418e9407e085daf642e752",
                "arm" to "e13484cc20631ff8fa13ac22ef86e9c78ca5debca7281b663e5af2c9ad29e9ff",
                "i686" to "f9bf09d4d99b7995073ad096770fc087b730db5040ab68250fed5181263a0598",
                "x86_64" to "0bfcec43dae833be3d99aa8371a82cbd2b6b489327764f7b5d4e137a751daa68"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 13,
            packageManager = "apk"
        ),
        Distro(
            name = "arch",
            displayName = "Arch",
            description = "Rolling release, latest packages.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/arch-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "e773db5aefb9598696666064b44de60f26e6f4ce93f7038e2fbea7191452268f",
                "arm" to "5413599044115245669c44d368dd1cf0ff3155b655e56652366dcd52c2cf2095",
                "x86_64" to "0f47018684afbb8d244d841da9012aeb08d26d952bbd81da8de2ac36e44e8b78"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 270,
            packageManager = "pacman"
        ),
        Distro(
            name = "armtix",
            displayName = "ARMtix",
            description = "Arch-based, tuned for ARM hardware.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/armtix-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "2a72d5a74d1b4d66b29ae94f5eb36f9a21ca5d25b4ffa370a98b44c3f8919b9d"
            ),
            prootArchs = listOf("aarch64"),
            installSizeMb = 841,
            packageManager = "pacman"
        ),
        Distro(
            name = "chimera",
            displayName = "Chimera",
            description = "Independent, lightweight, Gentoo-inspired.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/chimera-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "43672f94146140d749c3e86dee48aedca8bf9435db22e46382ad17a6172c4f5a",
                "x86_64" to "0c2a1f3b350e7a74acc8c62fd9bfd3cded3f4f030d3f5468c19f930f71c8c5da"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 21,
            packageManager = "apk"
        ),
        Distro(
            name = "debian",
            displayName = "Debian",
            description = "Stable, well-supported, large package repository.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/trixie-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "facb8f117ef04fb04eb1daa7cc33f29021f14ed8c58c6f9f132d42bf5df964ea",
                "arm" to "39d39b7e6b5676a6f8f43e0b8294123f9a0ec27b27da434e159bef98ac8f5b6f",
                "i686" to "8019a62fd5c995d58e7f602d104fd39b1d8794cecb613855c86c76aa7becedfa",
                "x86_64" to "29158a793f2120dc49b6cb4e841c660a61239c4d373abc5c08bd22c7fb8964bb"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 148,
            packageManager = "apt"
        ),
        Distro(
            name = "fedora",
            displayName = "Fedora",
            description = "Modern, innovative, upstream-first.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/fedora-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "554540b60f2020f929d1c9d771cc2c9aa6116c8e2c4bd0df1d3e65bc824c38a6",
                "x86_64" to "8695221dc9f73cd6f604217f162eeda1b91701f99112acfe38bdf3aba019f0f5"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 341,
            packageManager = "dnf"
        ),
        Distro(
            name = "gentoo",
            displayName = "Gentoo",
            description = "Source-based, minimal, highly customisable.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/gentoo-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "d2bf38ec9fb56755a2db1105fd78020a573b9b52b48e53daf536ee374ed22921",
                "arm" to "6a724cd2489f46f0bed5440af19740c073fd5450df939ebd188fc1ca25685a8d",
                "i686" to "25abb63a9315357b9991ff370678817cd294c8094abd23a42805173d39fd1ef4",
                "x86_64" to "e91ad6b3e1c140da341a2948c30dded719f1bfbd7897321d666bc8639477aefb"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 740,
            packageManager = "emerge"
        ),
        Distro(
            name = "kali",
            displayName = "Kali",
            description = "Penetration testing and security research.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/kali-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "a11475c3e72c966867b6fe7ab1e8d64a15021a642dfb3d8ec3fe73f523655a85",
                "arm" to "8dc9b81fa84eaa6cfe2018e91578e034edcc7d19977faa6193b58240aec28f57",
                "i686" to "34cdd98bc87a60cee8e6051e581ab87c38392b255a5be0a9d4461baa128a8b86",
                "x86_64" to "8fc04bf942dc7abf32b3523eff2cc5dd15c42695115c381da94b32c7eb8d2594"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 401,
            packageManager = "apt"
        ),
        Distro(
            name = "manjaro",
            displayName = "Manjaro",
            description = "User-friendly Arch-based rolling release.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/manjaro-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "297ad50ea63ef37d9625bce6729b15d3966d2de14c24801829134e3d9ad6194c",
                "x86_64" to "1c82487eb0770edab56610b7bb47be79418d57e3455e01073993d5227a1cbb1d"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 608,
            packageManager = "pacman"
        ),
        Distro(
            name = "opensuse",
            displayName = "openSUSE",
            description = "Robust, flexible, enterprise-grade Linux.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/opensuse-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "6f0f718aa9ff2072e0992631ec0fd145d812b8defd42402d607ba1231d784908",
                "x86_64" to "a3ea1e11b8839029b3356c972219743e2a7ffd6710829f7069e39fe9398d41ac"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 87,
            packageManager = "zypper"
        ),
        Distro(
            name = "oracle",
            displayName = "Oracle",
            description = "Enterprise-grade, RHEL-compatible, long support.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/oracle-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "0249dfbd5dc2cd0287098902413930b5bc0d25599febcc277f8715ea36aa5221",
                "x86_64" to "5411d8163b6e8b4738a0dc70b8479ece06345c4ad9cd5316c77ac995767a8d62"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 191,
            packageManager = "dnf"
        ),
        Distro(
            name = "pardus",
            displayName = "Pardus",
            description = "Turkish government distribution, desktop oriented.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/pardus-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "dfb010f1b1949d7dce396c302f2d1ad54d68e90387a944daf927587bc8b44d4b",
                "x86_64" to "893edc033af9cc9a132260813f143ff17a98f3642b8658f0ecdb4b9cea09edb2"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 144,
            packageManager = "apt"
        ),
        Distro(
            name = "parrot",
            displayName = "Parrot",
            description = "Security and privacy, Debian-based.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/parrot-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "c1d2f02092d4c8b9d2c3382b3b2a1a1409cd2441b265064dae00956f9f5fad99",
                "arm" to "674accc22e7532692c8903720fc7a9b77ba9fb11bb73427879c1ae5bb5df624a",
                "x86_64" to "07a20e3ae4c4f9766c730d7634b2f57b0d02fa75e8105712e3a7b86315850f21"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 139,
            packageManager = "apt"
        ),
        Distro(
            name = "rocky",
            displayName = "Rocky",
            description = "Community RHEL-compatible, bug-for-bug.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/rocky-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "6ef8fd602e18607daecf044b5accef66c6e1cc66aeb70827f2b7e214b7cf66c4",
                "x86_64" to "c4024dab202b569eae5b840b55f77ebd5c4dcc639cc2b3485631ab59fbf581ab"
            ),
            prootArchs = listOf("aarch64", "x86_64"),
            installSizeMb = 154,
            packageManager = "dnf"
        ),
        Distro(
            name = "slackware",
            displayName = "Slackware",
            description = "One of the oldest, simple and minimal.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/slackware-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "bb220e425af3d8f80a6b8a194434cc28ea3e8c3894456724fe58cfa2b631a63f",
                "i686" to "140c7d0a333abd295243c663a6f4df17681d78902313475a1fe7bde6db600bb6",
                "x86_64" to "d3669954eadf122187e6e7f63d76d7b5f1b78f495e770d9f3c5a70966cb38110"
            ),
            prootArchs = listOf("aarch64", "x86_64", "i686"),
            installSizeMb = 154,
            packageManager = "slackpkg"
        ),
        Distro(
            name = "trisquel",
            displayName = "Trisquel",
            description = "Ubuntu-based, free software focused.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/trisquel-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "aad466cd0aa414e849b8e2eb48aac6b919f99eeceee9d78f9a8711d906a76e61",
                "arm" to "8ce7a4f9041acba70e604d6ad7ee69da54647d9b7e643cc21a1f6505ee0e671d",
                "i686" to "556b747cec445a681b9fec59337824efe1a755f94d356557ae2e7be9d98ab8b9",
                "x86_64" to "3f2acbd5b82ed5999eb8b548dbd48d9c648abe6e4f3e2fc3f3b7316640d3069f"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 231,
            packageManager = "apt"
        ),
        Distro(
            name = "ubuntu",
            displayName = "Ubuntu",
            description = "User-friendly, great community, latest packages.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/resolute-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "1abc51ef29c08abc47b46db148317325d0c18646bf8f5a1c6e7b105a8f2b379b",
                "arm" to "f89f7f01ede427bc7f8ac87e80e6588acfab6c95740a148f0efc8c8cb7ad2b58",
                "x86_64" to "0c7eefadc323dd45b1d90f820fbadade329a346b1c03866d7c53ae1047f8b02c"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64"),
            installSizeMb = 93,
            packageManager = "apt"
        ),
        Distro(
            name = "void",
            displayName = "Void",
            description = "Rolling release, fast package manager.",
            baseUrl = "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v1.0.0/void-{arch}-rt-v1.0.0.tar.xz",
            sha256 = mapOf(
                "aarch64" to "2486885ce946bf48acf5fd48c808ac6967a19fbb4afe26a459a600b710c4119b",
                "arm" to "ab5f2005ed52626fdba0c75e591bf4629e64c8d1480a41e847a28f0c9de42696",
                "i686" to "22e2e2c603272ebd6a2b664a71a5db965b4feb950e8dabe45b5d3a263ed3274c",
                "x86_64" to "32a6c7498cf17308e4304f34c829997eaa29392d3c9579dfef181ab2c836c263"
            ),
            prootArchs = listOf("aarch64", "arm", "x86_64", "i686"),
            installSizeMb = 130,
            packageManager = "xbps"
        ),
    )

    /**
     * The effective catalogue.
     *
     * Overridden by [applyIndex] when a published index has been fetched, and the
     * built-in list otherwise. Read through this property rather than [builtin] so every
     * caller — the welcome screen, the installer, the update checker, the backup screen —
     * sees the same thing.
     */
    @Volatile
    private var published: List<Distro>? = null

    val allDistros: List<Distro> get() = published ?: builtin

    /**
     * Applies a published index, keeping the built-in list for anything it does not cover.
     *
     * Null is stored when the index changes nothing, so a fetched-but-useless index leaves
     * the app exactly as it was instead of in a half-overridden state.
     */
    fun applyIndex(index: RootfsIndex.Index) {
        val updated = RootfsIndex.apply(index, builtin)
        published = updated.takeIf { it != builtin }
    }

    /** Drops any published override. Used when a cached index fails to parse. */
    fun clearIndex() {
        published = null
    }

    /** True when the catalogue is coming from a published index rather than the APK. */
    fun usingPublishedIndex(): Boolean = published != null

    fun forDevice(abi: String): List<Distro> {
        val mapped = abiToProotArch(abi)
        return allDistros.filter { it.prootArchs.any { arch -> arch == mapped } }
    }

    /** The built-in catalogue, regardless of any published override. For tests and diagnostics. */
    fun builtin(): List<Distro> = builtin
}
