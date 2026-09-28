package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kali is the one distro not served by Termux proot-distro: it comes from
 * NetHunter and its asset names use Debian-style architecture names, so the
 * override mapping is easy to get wrong and would only fail on device.
 */
class DistroRegistryTest {

    private val kali = DistroRegistry.allDistros.first { it.name == "kali" }

    @Test
    fun `kali uses the nethunter rootfs url with vendor architecture names`() {
        assertEquals(
            "https://kali.download/nethunter-images/current/rootfs/" +
                "kali-nethunter-rootfs-minimal-arm64.tar.xz",
            kali.tarballUrlFor("arm64-v8a")
        )
        assertEquals(
            "https://kali.download/nethunter-images/current/rootfs/" +
                "kali-nethunter-rootfs-minimal-amd64.tar.xz",
            kali.tarballUrlFor("x86_64")
        )
        assertEquals(
            "https://kali.download/nethunter-images/current/rootfs/" +
                "kali-nethunter-rootfs-minimal-armhf.tar.xz",
            kali.tarballUrlFor("armeabi-v7a")
        )
        assertEquals(
            "https://kali.download/nethunter-images/current/rootfs/" +
                "kali-nethunter-rootfs-minimal-i386.tar.xz",
            kali.tarballUrlFor("x86")
        )
    }

    @Test
    fun `every proot arch kali declares maps to a real published image`() {
        val expected = mapOf(
            "aarch64" to "arm64",
            "arm" to "armhf",
            "x86_64" to "amd64",
            "i686" to "i386"
        )
        assertEquals(expected.keys, kali.prootArchs.toSet())
        for ((prootArch, vendorArch) in expected) {
            val url = kali.baseUrl.replace("{arch}", vendorArch)
            assertTrue(
                "unmapped arch $prootArch",
                url.contains("-$vendorArch.tar.xz") && !url.contains("{arch}")
            )
        }
    }

    @Test
    fun `kali is offered on every supported device architecture`() {
        for (abi in listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")) {
            assertTrue(
                "kali missing for $abi",
                DistroRegistry.forDevice(abi).any { it.name == "kali" }
            )
        }
    }

    @Test
    fun `kali pins no checksum because the vendor path is a rolling release`() {
        assertTrue(
            "a pinned hash would break the install when kali.download refreshes",
            kali.sha256.isEmpty()
        )
    }

    @Test
    fun `every registry entry has a url without unresolved placeholders for a real abi`() {
        for (distro in DistroRegistry.allDistros) {
            val abi = distro.prootArchs.firstOrNull()?.let { prootToAbi(it) } ?: continue
            val url = distro.tarballUrlFor(abi)
            assertFalse("${distro.name} has an unresolved placeholder", url.contains("{arch}"))
            assertTrue("${distro.name} url is not https", url.startsWith("https://"))
            assertTrue("${distro.name} url does not end in .tar.xz", url.endsWith(".tar.xz"))
        }
    }

    @Test
    fun `distro names are unique`() {
        val names = DistroRegistry.allDistros.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    private fun prootToAbi(prootArch: String): String = when (prootArch) {
        "aarch64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "i686" -> "x86"
        else -> "arm64-v8a"
    }
}
