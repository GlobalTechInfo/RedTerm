package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index is fetched from a server and decides what a device downloads and executes, so
 * these tests are mostly about the ways it can go wrong rather than the happy path.
 */
class RootfsIndexTest {

    private val good = """
        {
          "schema": 1,
          "version": "v0.5.0",
          "generatedAt": "2026-10-08T00:00:00Z",
          "images": [
            {"distro":"alpine","arch":"aarch64","upstreamVersion":"3.24.2",
             "displayName":"Alpine","file":"alpine-aarch64-rt-v0.5.0.tar.xz",
             "url":"https://example.invalid/alpine-aarch64-rt-v0.5.0.tar.xz",
             "baseUrlTemplate":"https://example.invalid/alpine-{arch}-rt-v0.5.0.tar.xz",
             "sha256":"${"a".repeat(64)}","size":4051924,"buildVersion":"v0.5.0",
             "recipe":"distro-build/alpine.sh"},
            {"distro":"alpine","arch":"x86_64","upstreamVersion":"3.24.2",
             "displayName":"Alpine","file":"alpine-x86_64-rt-v0.5.0.tar.xz",
             "url":"https://example.invalid/alpine-x86_64-rt-v0.5.0.tar.xz",
             "baseUrlTemplate":"https://example.invalid/alpine-{arch}-rt-v0.5.0.tar.xz",
             "sha256":"${"b".repeat(64)}","size":3900508,"buildVersion":"v0.5.0",
             "recipe":"distro-build/alpine.sh"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses images with their checksums and template`() {
        val index = RootfsIndex.parse(good)
        assertEquals(1, index.schema)
        assertEquals("v0.5.0", index.version)
        assertEquals(2, index.images.size)
        val a = index.resolve("alpine", "arm64-v8a")!!
        assertEquals("aarch64", a.arch)
        assertEquals("a".repeat(64), a.sha256)
        assertEquals("3.24.2", a.upstreamVersion)
    }

    /**
     * The template has to reproduce the concrete URL, because that is how the app builds a
     * download URL for an architecture the index did not list explicitly.
     */
    @Test
    fun `template substitutes back to the concrete url`() {
        val index = RootfsIndex.parse(good)
        for (e in index.images) {
            assertEquals(e.url, e.baseUrlTemplate.replace("{arch}", e.arch))
        }
    }

    @Test
    fun `resolves a device abi to the right image`() {
        val index = RootfsIndex.parse(good)
        assertEquals("x86_64", index.resolve("alpine", "x86_64")!!.arch)
        assertNull("an unlisted arch must not resolve", index.resolve("alpine", "x86"))
    }

    /**
     * The whole point of the index is that the bytes fetched are the bytes published. An
     * entry with no checksum cannot make that claim, so it must never be selected — not
     * with an empty string, and not silently.
     */
    @Test
    fun `entry without a checksum is never resolved`() {
        val noSum = """
            {"schema":1,"version":"v1","images":[
              {"distro":"alpine","arch":"aarch64",
               "url":"https://example.invalid/alpine-aarch64.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":""}
            ]}
        """.trimIndent()
        val index = RootfsIndex.parse(noSum)
        assertNull(index.resolve("alpine", "arm64-v8a"))
    }

    /** A newer build wins, so a republished tag is picked up without a code change. */
    @Test
    fun `newest build wins when several are published`() {
        // Two builds of the same distro for one architecture, so the choice has to be made.
        val two = """
            {"schema":1,"version":"v0.6.0","images":[
              {"distro":"alpine","arch":"aarch64",
               "url":"https://example.invalid/alpine-aarch64-rt-v0.5.0.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}-rt-v0.5.0.tar.xz",
               "sha256":"${"a".repeat(64)}","buildVersion":"v0.5.0"},
              {"distro":"alpine","arch":"aarch64",
               "url":"https://example.invalid/alpine-aarch64-rt-v0.6.0.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}-rt-v0.6.0.tar.xz",
               "sha256":"${"d".repeat(64)}","buildVersion":"v0.6.0"}
            ]}
        """.trimIndent()
        val index = RootfsIndex.parse(two)
        assertEquals("v0.6.0", index.resolve("alpine", "arm64-v8a")!!.buildVersion)
    }

    /**
     * A schema this build does not understand is refused outright. Half-reading a future
     * format means guessing what its fields mean, and a guessed field is a wrong download.
     */
    @Test(expected = RootfsIndex.ParseException::class)
    fun `unknown schema is refused`() {
        RootfsIndex.parse(good.replace("\"schema\": 1", "\"schema\": 2"))
    }

    @Test(expected = RootfsIndex.ParseException::class)
    fun `missing images array is refused`() {
        RootfsIndex.parse("""{"schema":1,"version":"v1"}""")
    }

    @Test(expected = RootfsIndex.ParseException::class)
    fun `not json at all is refused`() {
        RootfsIndex.parse("<html>captive portal</html>")
    }

    /** One unusable row must not cost the user every other distro in the catalogue. */
    @Test
    fun `a row missing its url is skipped, the rest survive`() {
        val broken = """
            {"schema":1,"version":"v1","images":[
              {"distro":"alpine","arch":"aarch64","url":"",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":"${"a".repeat(64)}"},
              {"distro":"alpine","arch":"x86_64",
               "url":"https://example.invalid/alpine-x86_64.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":"${"b".repeat(64)}"}
            ]}
        """.trimIndent()
        val index = RootfsIndex.parse(broken)
        assertEquals(1, index.images.size)
        assertEquals("x86_64", index.images.single().arch)
    }

    // ---- applying to the registry ----

    @Test
    fun `apply replaces url and checksums for a published distro`() {
        val alpine = DistroRegistry.builtin().first { it.name == "alpine" }
        val updated = RootfsIndex.applyTo(alpine, RootfsIndex.parse(good))
        assertEquals(
            "https://example.invalid/alpine-{arch}-rt-v0.5.0.tar.xz",
            updated.baseUrl
        )
        assertEquals("a".repeat(64), updated.sha256For("arm64-v8a"))
        assertEquals("b".repeat(64), updated.sha256For("x86_64"))
    }

    /**
     * The index is a supply of newer images, not a new platform. It must not be able to
     * offer a distribution on an architecture the app has no support for.
     */
    @Test
    fun `apply cannot widen the architectures on offer`() {
        val alpine = DistroRegistry.builtin().first { it.name == "alpine" }
        val twoArchIndex = """
            {"schema":1,"version":"v1","images":[
              {"distro":"alpine","arch":"aarch64",
               "url":"https://example.invalid/alpine-aarch64.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":"${"a".repeat(64)}"},
              {"distro":"alpine","arch":"riscv64",
               "url":"https://example.invalid/alpine-riscv64.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":"${"b".repeat(64)}"}
            ]}
        """.trimIndent()
        val updated = RootfsIndex.applyTo(alpine, RootfsIndex.parse(twoArchIndex))
        assertTrue(
            "riscv64 must not appear",
            updated.prootArchs.none { it == "riscv64" }
        )
    }

    /** Arches whose files do not share one template cannot be expressed; refuse rather than guess. */
    @Test
    fun `apply leaves the distro alone when templates disagree`() {
        // Written out rather than patched out of `good`: a string substitution that fails to
        // match silently produces the original document, so the test then asserts the
        // opposite of what it claims to.
        val split = """
            {"schema":1,"version":"v1","images":[
              {"distro":"alpine","arch":"aarch64",
               "url":"https://example.invalid/alpine-aarch64.tar.xz",
               "baseUrlTemplate":"https://example.invalid/alpine-{arch}.tar.xz",
               "sha256":"${"a".repeat(64)}"},
              {"distro":"alpine","arch":"x86_64",
               "url":"https://other.invalid/thing-x86_64.tar.xz",
               "baseUrlTemplate":"https://other.invalid/thing-{arch}.tar.xz",
               "sha256":"${"b".repeat(64)}"}
            ]}
        """.trimIndent()
        val alpine = DistroRegistry.builtin().first { it.name == "alpine" }
        assertEquals(alpine, RootfsIndex.applyTo(alpine, RootfsIndex.parse(split)))
    }

    @Test
    fun `apply leaves an unpublished distro untouched`() {
        for (d in DistroRegistry.builtin()) {
            if (d.name != "alpine") {
                assertEquals(d, RootfsIndex.applyTo(d, RootfsIndex.parse(good)))
            }
        }
    }

    @Test
    fun `apply never drops a distro from the catalogue`() {
        val before = DistroRegistry.builtin().size
        val after = RootfsIndex.apply(RootfsIndex.parse(good)).size
        assertEquals(before, after)
    }

    /**
     * Every built-in distro must still be installable offline. This is the test that would
     * fail if the index ever became mandatory rather than additive.
     */
    @Test
    fun `built-in catalogue stays complete and usable without an index`() {
        DistroRegistry.clearIndex()
        try {
            val all = DistroRegistry.allDistros
            assertFalse(all.isEmpty())
            assertFalse(DistroRegistry.usingPublishedIndex())
            for (d in all) {
                if (d.name == "kali") continue // rolling vendor image, deliberately unpinned
                assertTrue("${d.name} has no URL", d.baseUrl.isNotBlank())
                assertTrue("${d.name} has no aarch64 checksum", d.sha256For("arm64-v8a").isNotBlank())
            }
        } finally {
            DistroRegistry.clearIndex()
        }
    }

    @Test
    fun `registry override is visible through allDistros and forDevice`() {
        DistroRegistry.clearIndex()
        try {
            DistroRegistry.applyIndex(RootfsIndex.parse(good))
            assertTrue(DistroRegistry.usingPublishedIndex())
            val alpine = DistroRegistry.allDistros.first { it.name == "alpine" }
            assertEquals("https://example.invalid/alpine-{arch}-rt-v0.5.0.tar.xz", alpine.baseUrl)
            assertNotNull(DistroRegistry.forDevice("x86_64").firstOrNull { it.name == "alpine" })
        } finally {
            DistroRegistry.clearIndex()
        }
        assertFalse(DistroRegistry.usingPublishedIndex())
    }

    /**
     * An index that changes nothing must leave the registry in its built-in state rather
     * than in a half-overridden one.
     */
    @Test
    fun `applying an index that covers nothing marks the registry as not published`() {
        DistroRegistry.clearIndex()
        try {
            DistroRegistry.applyIndex(
                RootfsIndex.parse(
                    """{"schema":1,"version":"v1","images":[
                       {"distro":"nothing-here","arch":"aarch64","url":"https://x.invalid/a.tar.xz",
                        "baseUrlTemplate":"https://x.invalid/{arch}.tar.xz","sha256":"${"c".repeat(64)}"}]}"""
                )
            )
            assertFalse(DistroRegistry.usingPublishedIndex())
        } finally {
            DistroRegistry.clearIndex()
        }
    }
}