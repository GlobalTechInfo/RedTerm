package com.redtermapp.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * openSUSE's repositories, fixed from the app rather than from `.startup`.
 *
 * The previous version was a shell block and did nothing at all, on any device, for
 * several releases. It reported `Setup complete.` every session while every repository
 * line was byte-identical afterwards.
 *
 * These tests use the file contents openSUSE Leap 16.0 actually ships, because the
 * whole failure turns on three things in that file: the colon in the section header,
 * the trailing `service=openSUSE`, and the http baseurl.
 */
class OpenSuseRepositoriesTest {

    private lateinit var rootfs: File

    /** Verbatim shape of a Leap repo file, comments and all. */
    private val leapRepo = """
        # Repository 'openSUSE:repo-oss' is maintained by the 'openSUSE' service.
        # Manual changes may be overwritten by a service refresh.
        # See also 'man zypper', section 'Services'.
        [openSUSE:repo-oss]
        name=repo-oss (${'$'}{releasever})
        enabled=1
        autorefresh=1
        baseurl=http://cdn.opensuse.org/distribution/leap/${'$'}{releasever}/repo/oss/${'$'}{basearch}
        gpgkey=http://cdn.opensuse.org/distribution/leap/${'$'}{releasever}/repo/oss/${'$'}{basearch}/repodata/repomd.xml.key
        service=openSUSE
    """.trimIndent() + "\n"

    private fun tree(): File {
        rootfs = File(System.getProperty("java.io.tmpdir"), "rt-zypp-${System.nanoTime()}")
        File(rootfs, "etc/zypp/repos.d").mkdirs()
        File(rootfs, "var/cache/zypp").mkdirs()
        return rootfs
    }

    private fun repo(name: String, body: String = leapRepo): File {
        val f = File(rootfs, "etc/zypp/repos.d/$name")
        f.writeText(body)
        return f
    }

    @org.junit.After
    fun cleanUp() {
        if (::rootfs.isInitialized) rootfs.deleteRecursively()
    }

    @Test
    fun `the colon is removed from the alias`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val text = File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        assertTrue("the alias still contains a colon:\n$text", text.contains("[repo-oss]"))
        assertFalse(
            "a colon is left in a section header:\n$text",
            Regex("""(?m)^\[[^]]*:[^]]*]""").containsMatchIn(text)
        )
    }

    /**
     * Without this the colon comes straight back, and the fix looks inert.
     *
     * `service=openSUSE` makes libzypp namespace a serviced repository as
     * `service:alias` and rewrite the headers on every invocation.
     */
    @Test
    fun `the service line goes, so nothing puts the colon back`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val text = File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        assertFalse("service= is still set:\n$text", Regex("(?m)^\\s*service\\s*=").containsMatchIn(text))
    }

    @Test
    fun `the baseurl becomes https`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val text = File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        assertTrue("baseurl is still http:\n$text", text.contains("baseurl=https://download.opensuse.org"))
        assertTrue("gpgkey is still http:\n$text", text.contains("gpgkey=https://download.opensuse.org"))
    }

    /** The alias is the section header. The name= line is only a display label. */
    @Test
    fun `the display name is left alone`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val text = File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        assertTrue("the name= line was altered:\n$text", text.contains("""name=repo-oss (${'$'}{releasever})"""))
    }

    /**
     * What an earlier attempt did across three releases, and what cost the user
     * distributions they could install from.
     */
    @Test
    fun `no repository is removed and no file is renamed`() {
        tree()
        val oss = repo("openSUSE:repo-oss.repo")
        repo("openSUSE:repo-openh264.repo", "[openSUSE:repo-openh264]\nname=repo-openh264\n")
        repo("openSUSE:repo-oss-source.repo", "[openSUSE:repo-oss-source]\nname=repo-oss-source\n")
        repo("openSUSE:repo-oss-debug.repo", "[openSUSE:repo-oss-debug]\nname=repo-oss-debug\n")
        OpenSuseRepositories.prepare(rootfs)

        val dir = File(rootfs, "etc/zypp/repos.d").listFiles().orEmpty()
        assertEquals("a repository was removed", 4, dir.size)
        assertTrue("a repository file was renamed", oss.exists())
        // Every repository survives; they are de-coloned, not dropped.
        assertEquals(
            "not every repository survived",
            listOf("repo-oss", "repo-oss-debug", "repo-oss-source", "repo-openh264").sorted(),
            OpenSuseRepositories.prepare(rootfs).aliasesAfter.distinct().sorted()
        )
    }

    /** Nothing that is not openSUSE is touched. */
    @Test
    fun `a repository with no colon is left exactly as it is`() {
        tree()
        val plain = "[repo-non-oss]\nname=repo-non-oss\nservice=openSUSE\n"
        val f = repo("repo-non-oss.repo", plain)
        OpenSuseRepositories.prepare(rootfs)
        // The service line is still removed, because it would re-add a colon to a
        // repository that gets one later; everything else is untouched.
        assertFalse("service= was not removed", f.readText().contains("service="))
        assertTrue("the alias was changed", f.readText().contains("[repo-non-oss]"))
    }

    /** Running twice must be a no-op, so the cache is not rebuilt every session. */
    @Test
    fun `a second run changes nothing`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val after = File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        val second = OpenSuseRepositories.prepare(rootfs)
        assertEquals(
            "the second run rewrote the file",
            after,
            File(rootfs, "etc/zypp/repos.d/openSUSE:repo-oss.repo").readText()
        )
        assertTrue("the second run reported changes it did not make", second.changed.isEmpty())
    }

    /** The point of moving this out of the shell: it must be able to report failure. */
    @Test
    fun `a repository that cannot be read is reported, not counted as correct`() {
        tree()
        val f = repo("openSUSE:repo-oss.repo")
        f.delete()
        f.mkdirs() // a directory where a file is expected: readText throws
        val result = OpenSuseRepositories.prepare(rootfs)
        assertTrue(
            "an unreadable repository was silently skipped: ${result.oneLine()}",
            result.unreadable.isNotEmpty()
        )
        assertTrue(result.oneLine().contains("could not read"))
    }

    @Test
    fun `the cache directories exist and are writable`() {
        tree()
        val result = OpenSuseRepositories.prepare(rootfs)
        for (name in listOf("solv", "raw", "packages")) {
            val d = File(rootfs, "var/cache/zypp/$name")
            assertTrue("$name was not created", d.isDirectory)
        }
        assertEquals("the run reported a different number of created directories", 3, result.cacheDirs.size)
    }

    @Test
    fun `a missing cache tree is created`() {
        val parent = File(System.getProperty("java.io.tmpdir"), "rt-zypp-${System.nanoTime()}")
        rootfs = parent
        try {
            // No var/cache/zypp at all, which is what the image ships.
            val result = OpenSuseRepositories.prepare(rootfs)
            assertTrue(File(rootfs, "var/cache/zypp/solv").isDirectory)
            assertEquals(3, result.cacheDirs.size)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a non-opensuse rootfs is left alone`() {
        tree()
        File(rootfs, "etc/os-release").writeText("""ID=debian""")
        val result = OpenSuseRepositories.prepare(rootfs)
        assertEquals("repositories were touched on a non-openSUSE rootfs", 0, result.reposInspected)
    }

    /**
     * The cache directory a failed build leaves behind.
     *
     * This is what made the symptom immune to every configuration change: the first
     * failure left `solv/repo-oss/` populated with part of the result, and from then on
     * zypper tried to read that instead of rebuilding it, so it failed the same way
     * forever. zypper names its own remedy in the error:
     *   Try 'zypper refresh', or even 'zypper clean -m' before doing so.
     */
    @Test
    fun `a cache directory with no index is removed`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val half = File(rootfs, "var/cache/zypp/solv/repo-oss")
        assertTrue("precondition: the directory was not created", half.mkdirs())
        File(half, "cookie").writeText("partial")

        val dropped = OpenSuseRepositories.dropHalfBuiltCaches(rootfs)
        assertTrue("the half-built cache survived: $dropped", dropped.contains("var/cache/zypp/solv/repo-oss"))
        assertFalse("the directory is still there", half.exists())
    }

    /** An empty index file is as useless as no index file. */
    @Test
    fun `a zero length index is treated as half built`() {
        tree()
        val half = File(rootfs, "var/cache/zypp/solv/repo-oss")
        assertTrue(half.mkdirs())
        File(half, "solv").writeText("")
        OpenSuseRepositories.dropHalfBuiltCaches(rootfs)
        assertFalse("an empty index was kept", half.exists())
    }

    /** A working cache must never be thrown away and re-downloaded. */
    @Test
    fun `a complete cache is left alone`() {
        tree()
        val half = File(rootfs, "var/cache/zypp/solv/repo-oss")
        assertTrue(half.mkdirs())
        File(half, "solv").writeText("x".repeat(4096))
        File(half, "solv.idx").writeText("y".repeat(64))
        val dropped = OpenSuseRepositories.dropHalfBuiltCaches(rootfs)
        assertTrue("a working cache was discarded: $dropped", dropped.isEmpty())
        assertTrue("a working cache was deleted", half.exists())
        assertTrue("its index was deleted", File(half, "solv").exists())
    }

    /** @System is libzypp's own directory of installed packages, not a repo cache. */
    @Test
    fun `the @System directory is never removed`() {
        tree()
        val sys = File(rootfs, "var/cache/zypp/solv/@System")
        assertTrue(sys.mkdirs())
        OpenSuseRepositories.dropHalfBuiltCaches(rootfs)
        assertTrue("@System was removed", sys.exists())
    }

    /** A file where a cache directory should be is not a cache directory. */
    @Test
    fun `stray files are left alone`() {
        tree()
        File(rootfs, "var/cache/zypp/solv").mkdirs()
        val f = File(rootfs, "var/cache/zypp/solv/notes")
        f.writeText("hello")
        OpenSuseRepositories.dropHalfBuiltCaches(rootfs)
        assertTrue("a stray file was removed", f.exists())
    }
}
