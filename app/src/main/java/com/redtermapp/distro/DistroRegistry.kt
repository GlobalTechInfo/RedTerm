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
                "aarch64" to "00b239c7cfa24bdb6e10ba4f4127dccc6d076d779ef10132f219f22da41e3bd9",
                "arm" to "53808e53b86addbf5bfe3dfeeb2e33fc8673cd1ec078700da85f061e73217db3",
                "x86_64" to "fd5db8803d83637f1d97a161dbb83bf3087832836bad9db866c10854b75f8a63",
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
                "aarch64" to "59cb6652ee23b19605fe8d0f92233def228a8d5c1b4fe2a784fd4e8f4dc35b22",
                "x86_64" to "db80f266c0bb36de1f43447cab19ea254f74743e525d72974c355c4ce22a0000",
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
                "aarch64" to "8a5e332ab3b922619b231c2cdd6c386a2044dcad437f455c4c4a17038c2b1bcd",
                "arm" to "9b42632e61e040e7223c785c5b8d6f4e2338afdde8b6fe3b580ec9d3ba98b9d0",
                "x86_64" to "dfe417e574f610a68aab0047ee151bd95ef7d3c34309385d902b61b88b1a12b1",
                "i686" to "220d93eccd4595df5f778473816a0bb5359facba49f245a6ddb7a5a299d8aedb",
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
                "aarch64" to "6418ae7e1c9f5c25e6a2b08f79a4f2bbc78417a16caff701537513ea73492016",
                "arm" to "fcd78a5f3cb2b1acc27364a9ae04ec7082d9e1ff759bdebe94ab7b1df37d604e",
                "x86_64" to "a0a44206a178c2acd90303518e5e98fedd4e7b7271dc4a013434a880d16269d0",
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
                "aarch64" to "2a72d5a74d1b4d66b29ae94f5eb36f9a21ca5d25b4ffa370a98b44c3f8919b9d",
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
                "aarch64" to "f1ff84c29765a5952b7597c52e214e3d305b30ed40467c85efaf683658f6ed18",
                "x86_64" to "96cbb0f8f623287e4a0115de6b62107ec97d5d282342d8ee955df68f6315444c",
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
                "aarch64" to "02fae9830ec8a69868640c208866c1fb1467ea4d89ee3dc99bd95803fe9f48b1",
                "arm" to "cd13043aa0b6835c4cb822e953cbdbb065f6cb604924a3f180a76b28566d10d2",
                "x86_64" to "d3282952ae7b1defd9851d72f167e3ecb00a652c9e97b793e89bba1917c712f0",
                "i686" to "5ff9d7aace3f0e8360572d5b8079ed012fce81b6d2b50a60af3e327211b43e0d",
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
                "aarch64" to "6c40a3600e7d6ceb778b9c68435d942cd28e788dbeb42dd743b550f014718045",
                "x86_64" to "c6416c42824c12198348b8ad507502e889d9b32d6e329d6a88e26f0774b7a574",
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
                "x86_64" to "e91ad6b3e1c140da341a2948c30dded719f1bfbd7897321d666bc8639477aefb",
                "i686" to "25abb63a9315357b9991ff370678817cd294c8094abd23a42805173d39fd1ef4",
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
                "aarch64" to "bedb428d2bbf716fdf6122333abea32bda8fb399bc7b9ccb27e036a2a6f83381",
                "arm" to "7884edd41923df083c3b793a09b9e519f4479de7d3576317441835672676c478",
                "x86_64" to "fac5e01f8dc1512e0c986cd02e3607b82872f910e512dde3c02a10be84726199",
                "i686" to "576db5545ded56b4b47fcd4d27d52fc203e25552790e374df5516d03657c12b5",
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
                "aarch64" to "3705c38fe3d45df836d371dd66e1b37292641c1b1c40ae1554a03b3c4c474a34",
                "x86_64" to "fa6c6c19bef72e70ff3617c7f48d6d2346678a944c23d14b194fa07dd9f688c5",
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
                "aarch64" to "1ac646479821c70d0641e9d8484712b15c9acf3f991c678496d670dbdba4194f",
                "x86_64" to "1bb9b9ab62f25dc07ccba85778e7b3dbb6c28386298ffdd7117f9bb6af1e6706",
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
                "aarch64" to "b993c854b5a18afcfc55dceb31759b9ea5d37706a9b4217348b1c7ef31dab7aa",
                "x86_64" to "34c1dc63c51ee73c1e6033e1cee05716f831ad2786c1f6dff9376861a7c6d413",
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
                "aarch64" to "813329c6a0ee96562cc4fed67614dce64658e3adc74718d70a43b0def4cc4465",
                "x86_64" to "53e6a0ddcb3bbe63566ef86b88aa4fba5b1964eec4aaafb2b561357d383c56aa",
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
                "aarch64" to "3bf6dff98f70c9301aaebce0e71411a72eb257baf901613f41b46a93b5b681be",
                "arm" to "7b12c5a9e52d8efab2b41aecfe9b99dba81c636f6f59dab666f4cc780b613b74",
                "x86_64" to "c3b1123815b182f5789bba8d0096a249a0ff14225fb051e033dfdc0a8cca0210",
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
                "aarch64" to "03bc2d34131c0658cf8c838f419d96cd6cb4b73d343ea18eb8a97e67097241ee",
                "x86_64" to "e3ec5d466900ef4990f1c053171b331e6eca03299f320669d02a7007d50278d4",
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
                "aarch64" to "f570a3dd08cef932e2e1a79010c7513a7ef60665462a30378d65bb4cfd876a9c",
                "x86_64" to "ab59f47d360030844bfab30b35966397cc7db08cce8a373940c02432f28302b3",
                "i686" to "593e28aaf08cc138ae9cf0682b683e9d7fa5c935501bc65df73abf132265a69d",
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
                "aarch64" to "42910cb8ca2090a1219fbdf974d4e1385d2bcfa8bf0c2246670a0ddceea38b0e",
                "arm" to "9bd58e06baf92a700c00764b066fc4bd1265647b0e2aa43de36d89a3158a18a2",
                "x86_64" to "9e22b1e90ac9d9309adfaccfa43a785fda6ed6ace9313f6ca84b7447e5732bb5",
                "i686" to "2a4280c33769fbe3aaef89735c706d8a603616bc17eeefa61a8576c147d70eba",
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
                "x86_64" to "0c7eefadc323dd45b1d90f820fbadade329a346b1c03866d7c53ae1047f8b02c",
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
                "aarch64" to "7e5043558f4ff8cbed4aef8990734a01ee50922801b0dcda676241d48f4e60e9",
                "arm" to "b463e42c1a70fb52f4da6253a800bd02ab99ffb6fb8e450363bcbd28afa09651",
                "x86_64" to "7fc08fff57ed7e06b353244d99c23a2e16204e997d1267e348cfdd614eb3e05a",
                "i686" to "5d37207f71c62fff16245488eefa8988cbe328a145814455429ec5b845db505d",
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
