package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroOsReleaseTest {

    private val debian = """
        # /etc/os-release
        NAME="Debian GNU/Linux"
        VERSION="13 (trixie)"
        ID=debian
        VERSION_ID="13"
        PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
    """.trimIndent()

    @Test
    fun `parses quoted and bare keys and skips comments`() {
        val m = DistroOsRelease.parse(debian)
        assertEquals("debian", m["ID"])
        assertEquals("13", m["VERSION_ID"])
        assertEquals("Debian GNU/Linux", m["NAME"])
        assertEquals("Debian GNU/Linux 13 (trixie)", m["PRETTY_NAME"])
        assertTrue(m.containsKey("VERSION"))
    }

    @Test
    fun `version prefers VERSION_ID over VERSION`() {
        val m = DistroOsRelease.parse(debian)
        assertEquals("13", DistroOsRelease.versionOf(m))
    }

    @Test
    fun `version falls back to VERSION when VERSION_ID is absent`() {
        val m = DistroOsRelease.parse("NAME=alpine\nVERSION=3.20\nID=alpine\n")
        assertEquals("3.20", DistroOsRelease.versionOf(m))
    }

    @Test
    fun `empty text is an empty map`() {
        assertTrue(DistroOsRelease.parse("").isEmpty())
        assertEquals("", DistroOsRelease.versionOf(emptyMap()))
    }

    @Test
    fun `single-quoted values are unquoted too`() {
        val m = DistroOsRelease.parse("VERSION='12.4'\n")
        assertEquals("12.4", m["VERSION"])
    }
}
