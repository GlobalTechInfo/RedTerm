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

    private fun repo(name: String, body: String = leapRepo, alias: String? = null): File {
        val text = if (alias != null) body.replace("[openSUSE:repo-oss]", "[$alias]") else body
        val f = File(rootfs, "etc/zypp/repos.d/$name")
        f.writeText(text)
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
     * The whole bug, in one assertion.
     *
     * Measured on the device: repo2solv parsed the raw metadata and then failed at
     * `freopen` with ENOENT, because the directory it was told to write into did not
     * exist. libzypp did not create it.
     */
    @Test
    fun `a solv cache directory is created for every alias`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        repo("openSUSE:repo-openh264.repo", alias = "openSUSE:repo-openh264")

        OpenSuseRepositories.prepare(rootfs)

        for (half in listOf("solv", "raw")) {
            for (alias in listOf("repo-oss", "repo-openh264")) {
                val d = File(rootfs, "var/cache/zypp/$half/$alias")
                assertTrue("no $half cache directory for $alias", d.isDirectory)
            }
        }
    }

    /** The alias is read from the header, so the header drives the directory name. */
    @Test
    fun `the directory follows the de-coloned header not the file name`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        assertTrue(File(rootfs, "var/cache/zypp/solv/repo-oss").isDirectory)
        assertFalse(
            "a directory named after the file was created",
            File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss").exists()
        )
    }

    /** Idempotent: a second session must not fail on directories that already exist. */
    @Test
    fun `running twice is harmless`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        OpenSuseRepositories.prepare(rootfs)
        val again = OpenSuseRepositories.prepare(rootfs)
        assertTrue("the second run reported a failure: ${again.unreadable}", again.unreadable.isEmpty())
        assertTrue(File(rootfs, "var/cache/zypp/solv/repo-oss").isDirectory)
    }

    /**
     * A repository file is data, and `[../../etc/shadow]` is a legal section header in
     * one. Turning it into a `mkdir -p` is a path traversal out of the cache.
     */
    @Test
    fun `an alias cannot escape the cache directory`() {
        tree()
        repo("evil.repo", "[../../escape]\nname=x\nbaseurl=https://example.invalid/\n")

        OpenSuseRepositories.prepare(rootfs)
        assertFalse(
            "a directory was created outside the cache",
            File(rootfs, "var/cache/escape").exists() ||
                File(rootfs, "escape").exists() ||
                File(rootfs.parentFile, "escape").exists()
        )
    }

    /** @System is libzypp's installed-package index, not a repository cache. */
    @Test
    fun `@System is never created or removed`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        val sys = File(rootfs, "var/cache/zypp/solv/@System")
        assertTrue(sys.mkdirs())
        OpenSuseRepositories.prepare(rootfs)
        assertTrue("@System was removed", sys.exists())
    }

    /**
     * The bug, in the form the device reports it.
     *
     *   Can't open solv-file: /var/cache/zypp/solv/openSUSE:repo-oss/solv
     *
     * That alias comes from `repo_4=openSUSE:repo-oss` in the service file, not from
     * any section header. Creating `solv/repo-oss` instead does not help.
     */
    @Test
    fun `a directory is created for the service member name too`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        File(rootfs, "etc/zypp/services.d").mkdirs()
        File(rootfs, "etc/zypp/services.d/openSUSE.service").writeText(
            "[openSUSE]\nenabled=1\nautorefresh=1\n" +
                "url = dir:/usr/share/zypp/local/service/openSUSE\ntype = ris\n" +
                "repo_1=openSUSE:repo-oss\nrepo_1_enabled=1\n" +
                "repo_2=openSUSE:repo-openh264\nrepo_2_enabled=1\n"
        )

        OpenSuseRepositories.prepare(rootfs)

        for (alias in listOf("openSUSE:repo-oss", "openSUSE:repo-openh264")) {
            assertTrue(
                "no cache directory for $alias",
                File(rootfs, "var/cache/zypp/solv/$alias").isDirectory
            )
        }
        // and the de-coloned header form still gets one
        assertTrue(File(rootfs, "var/cache/zypp/solv/repo-oss").isDirectory)
    }

    /** The service's repository index names the same repos a second way. */
    @Test
    fun `a directory is created for every alias in the service repository index`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        val idx = File(rootfs, "usr/share/zypp/local/service/openSUSE/repo")
        idx.mkdirs()
        File(idx, "opensuse-leap-repoindex.xml").writeText(
            "<repoindex disturl=\"http://cdn.opensuse.org\">\n" +
                "<repo url=\"x\" alias=\"repo-oss-debug\" enabled=\"false\"/>\n" +
                "</repoindex>\n"
        )

        OpenSuseRepositories.prepare(rootfs)

        assertTrue(
            File(rootfs, "var/cache/zypp/solv/repo-oss-debug").isDirectory
        )
    }

}

