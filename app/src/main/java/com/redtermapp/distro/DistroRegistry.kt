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
                "aarch64" to "f02e6ffce14c253adf70d8b576f890f16ef104525b0c151c124e6199f2f45163",
                "arm" to "ccfea08a06c6357ec893847da9d159c6e5e7f73e3ca16ba23e1adeea85a36793",
                "x86_64" to "ae4e3b4d34fe27a9e1c0c7902ba7ecd3a004d6140831f6716f1f2fc15d3324eb",
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
                "aarch64" to "cff608ad93ad011d466af93de2ca6f0a71fe541d9f442d11edd1573df9de02f9",
                "x86_64" to "8896646f19f40386edf41a1d18dad572e8e79fbd9ba09309425880315b0e77a9",
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
                "aarch64" to "d661ac9022a5f320b3133013902c2ba28fa913c66b38f196a3a68f35450da1ae",
                "arm" to "bf0621c7b2707fe787d8eeabfca9967d75ecd0a78ece6760601ae13eb86d9508",
                "x86_64" to "e62a7f14a24c118d19564f55138eaf77b22d9a17288ccfca60f6abcf4bed4681",
                "i686" to "859887c6d64ff749e49e624d39dbfcebd64c2638c21aea9f8893eb485064172e",
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
                "aarch64" to "edba03b7c12496108f236998c023fda664fed1c540d17571cdab96c50c6c841d",
                "arm" to "2f7f00f1a06ccfd13c97cc971e287e54e0c9398ea7571f08a022e5d3026cfff7",
                "x86_64" to "5ebf2607fc5fe7b2b96a0c90ad035314dfb04ee7495920a40d32dd5d1978bd30",
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
                "aarch64" to "c693c3cdab9b66122069f36b9ffa315928aa5fed7237598fa9003a999b489a3d",
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
                "x86_64" to "0c2a1f3b350e7a74acc8c62fd9bfd3cded3f4f030d3f5468c19f930f71c8c5da",
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
                "aarch64" to "17568f295b8493c3ec6088a6a92d2d13199fca6ef720ed79cab07f0fa36ed9b4",
                "arm" to "6f580dd6b2069f99576cc0dd98d61900e5cf8cf1dd71abb87c3f2a2fd79bdd14",
                "x86_64" to "e44f7864aa3f71a000d6f9e35f76b77c10cb9b9c4498fb096fd32a3eb5712863",
                "i686" to "63884695537fee5bd6da96e6b51331aab7078772bc11dafcab2950975a87abb3",
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
                "aarch64" to "ddc15cca554d955256bc7cff95f3da2ff139d900bc82617142ad08f5734965fa",
                "x86_64" to "bbd0176cb38eaf0a588a87b3c2dd1a6530f59eaca735f3cff4a3098a7969b717",
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
                "aarch64" to "6dd18a88c0d24fe7b9aabadc4fe2d38dd4c432bbe6d5d8b23449ec76fcbf57d9",
                "arm" to "6a724cd2489f46f0bed5440af19740c073fd5450df939ebd188fc1ca25685a8d",
                "x86_64" to "e4026673f13ce10b1c364791d705f798efe25610351ac952780b069d81f7d6c4",
                "i686" to "20013840bd82f2da44debd5ae2d725ef6df172e766cab5b6511aac6b3a5bb1df",
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
                "aarch64" to "2adff64db981c6d4d5d6d76ccb78365f59fd1ab5af3312273470be831299bc17",
                "arm" to "93b902e5373d73b1c41b043070995f7dd594586e4eb6467658c8cda699d09e25",
                "x86_64" to "6ca178272bf9041c622164ac9c40090d8a1b3a72262ff890fad40fd35c487017",
                "i686" to "b97c730b01d71eea08c98df33d9ce183ffb550a25e7e31c96ac52bd216297cef",
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
                "aarch64" to "5ea71a32b74aae2621328ec27c3463ae49d29d1c44e701fe3eefbb40d973f0af",
                "x86_64" to "3b6e7dc00e32d8de3afbafdc0c1651a38fd59d7ecdb603bab87b88e3092c09dc",
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
                "aarch64" to "ab767dda8683feafb5114e9004effb8737f29bc2188dafd614ff1ac152c1480c",
                "x86_64" to "07d9b94087ca44ede945e5dd31fe7a0985bd343e35cbcd765530298dce64bdfa",
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
                "aarch64" to "746b6e5268ed04b497d48368e08a6321eb22b407fed39c23196ca08ab40d4e64",
                "x86_64" to "03587639f550b9debc8790e7d534352cdad92f46970f377cf7a87c24e0951571",
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
                "aarch64" to "491dcf580c1ffcfa34eaf2f3eabf3cc92205f64236f051aea085471c766068dc",
                "x86_64" to "49313301f8883039a24f66a313aab6d8c82ac64139fa174fa65abcc1e82da09d",
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
                "aarch64" to "94a02922139be2bf6c3b4374d3f1a875936510d86236c2a051a9eb3f874a24d6",
                "arm" to "874991570de71ed9cc13dff84250356212694264d0f0e27646dccac47d0afb42",
                "x86_64" to "07b639020ac7a3d27c2b6ce1cd0bb07d38e7ff689133874a69f724467a245dd4",
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
                "aarch64" to "b563f1d017d61841de2a9922c470e721b7341ae8e9b367b59f8b573d16920a8c",
                "x86_64" to "7e14c9520454355a52e1801c3a4f677d2219c357802c89f991d9246c4733df97",
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
                "x86_64" to "d3669954eadf122187e6e7f63d76d7b5f1b78f495e770d9f3c5a70966cb38110",
                "i686" to "140c7d0a333abd295243c663a6f4df17681d78902313475a1fe7bde6db600bb6",
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
                "aarch64" to "e617168a4ec21174a6f95915805e5943627df23c3e7be015fc3f0f8247eac30d",
                "arm" to "08dc22128abfa6b374e32a2b83dc397be398e26675efbdf52a9d70cc2ec7df7b",
                "x86_64" to "db3d2183f5869178c312b34a2ffc4dba81b976c3d305994998586f7f5817a7c1",
                "i686" to "7f94b9aa26afd079cb28adbc2bc603ce9e728e767d7111095f7d35514b60f2af",
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
                "aarch64" to "fa0c163eda012a9702f987461bbf320d5e271db3e67db9b6df32e82790471dcb",
                "arm" to "eae2522aa3e932352904a287e87b264be613e272460444768e37216a17a34339",
                "x86_64" to "ce18b9e1fc45842c1c7f04dcf26b074836345d63fc3789b39fd8fa217a83a2fc",
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
                "x86_64" to "32a6c7498cf17308e4304f34c829997eaa29392d3c9579dfef181ab2c836c263",
                "i686" to "22e2e2c603272ebd6a2b664a71a5db965b4feb950e8dabe45b5d3a263ed3274c",
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
