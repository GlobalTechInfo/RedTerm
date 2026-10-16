package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's shape, rather than one distribution's URL.
 *
 * These entries are all served by this project's own GitHub release now, built by
 * rootfs-images from each distribution's upstream base. That replaced a mixed set
 * where most distros came from Termux and Kali came from NetHunter under Debian-style
 * architecture names, and the tests that guarded Kali's vendor mapping described a
 * situation that no longer exists.
 *
 * What replaced them are the invariants that the new arrangement can break: a distro
 * that declares an architecture with no checksum, a URL that does not use {arch}, a
 * name that has picked up capitals and will no longer match an index entry, and a
 * catalogue that is not in the order it claims to be.
 */
class DistroRegistryTest {

    private val distros = DistroRegistry.allDistros

    @Test
    fun `every distro is served by this project's own release`() {
        for (distro in distros) {
            assertTrue(
                "${distro.name} does not come from the rootfs-images release: ${distro.baseUrl}",
                distro.baseUrl.startsWith("https://github.com/GlobalTechInfo/rootfs-images/releases/")
            )
        }
    }

    @Test
    fun `every url is a template this project can substitute`() {
        for (distro in distros) {
            assertTrue(
                "${distro.name} has no architecture placeholder: ${distro.baseUrl}",
                distro.baseUrl.contains("{arch}")
            )
            assertTrue(
                "${distro.name} url does not end in .tar.xz",
                distro.baseUrl.endsWith(".tar.xz")
            )
        }
    }

    @Test
    fun `every declared architecture has a checksum`() {
        for (distro in distros) {
            assertTrue("${distro.name} declares no architectures", distro.prootArchs.isNotEmpty())
            for (arch in distro.prootArchs) {
                val sum = distro.sha256[arch]
                assertTrue(
                    "${distro.name}/${arch} has no sha256, so the download cannot be verified",
                    !sum.isNullOrBlank()
                )
                assertEquals("${distro.name}/${arch} sha256 is not a digest", 64, sum!!.length)
                assertTrue(
                    "${distro.name}/${arch} sha256 is not hex",
                    sum.all { it in "0123456789abcdef" }
                )
            }
        }
    }

    /**
     * The archOverride map existed only for Kali, whose NetHunter assets are named
     * arm64/amd64/armhf/i386 while the rest of the catalogue uses proot's names.
     * Serving everything ourselves removed the need, and an override left in place
     * would rewrite our own filenames into names that do not exist.
     */
    @Test
    fun `no distro needs an architecture override`() {
        for (distro in distros) {
            assertTrue(
                "${distro.name} overrides architecture names: ${distro.archOverride}",
                distro.archOverride.isEmpty()
            )
        }
    }

    @Test
    fun `names are lower case so an index entry can match them`() {
        for (distro in distros) {
            assertEquals(
                "${distro.name} is not lower case, so a published index entry would not match it",
                distro.name.lowercase(), distro.name
            )
        }
    }

    @Test
    fun `names are in alphabetical order`() {
        assertEquals(
            "the catalogue is not sorted, which is what a-z ordering is for",
            distros.map { it.name }.sorted(), distros.map { it.name }
        )
    }

    @Test
    fun `distro names are unique`() {
        val names = distros.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every registry entry resolves to a real url for a real abi`() {
        for (distro in distros) {
            val abi = distro.prootArchs.first().let { prootToAbi(it) }
            val url = distro.tarballUrlFor(abi)
            assertFalse("${distro.name} has an unresolved placeholder", url.contains("{arch}"))
            assertTrue("${distro.name} url is not https", url.startsWith("https://"))
        }
    }

    @Test
    fun `a distro is offered on every device architecture it declares`() {
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            val offered = DistroRegistry.forDevice(abi).map { it.name }.toSet()
            for (distro in distros) {
                val proot = abiToProotArch(abi)
                if (proot in distro.prootArchs) {
                    assertTrue("${distro.name} not offered on $abi", distro.name in offered)
                }
            }
        }
    }

    private fun prootToAbi(prootArch: String): String = when (prootArch) {
        "aarch64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "i686" -> "x86"
        else -> "arm64-v8a"
    }
}