package com.redtermapp.distro

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    private fun service(vararg members: String): File {
        val dir = File(rootfs, "etc/zypp/services.d")
        dir.mkdirs()
        File(dir, "openSUSE.service").writeText(
            buildString {
                append("[openSUSE]\nenabled=1\nautorefresh=1\n")
                append("url = dir:/usr/share/zypp/local/service/openSUSE\ntype = ris\n")
                members.forEach {
                    val (n, v) = it.split('=')
                    append("repo_$n=$v\nrepo_${n}_enabled=1\nrepo_${n}_autorefresh=1\n")
                }
            }
        )
        return File(dir, "openSUSE.service")
    }

    @After
    fun cleanUp() {
        if (::rootfs.isInitialized) rootfs.deleteRecursively()
    }

    /**
     * The repository files must not be rewritten at all.
     *
     * libzypp names the cache directory after the repository alias, and a repository
     * supplied by a service is aliased `service:alias`, so the shipped index lives at
     * `/var/cache/zypp/solv/openSUSE:repo-oss`. That is the exact path in every
     * "Can't open solv-file" report.
     *
     * De-coloning the header renamed the repository to one the image has no cache for.
     * The shipped index went unused, the session built a second cache directory for
     * the invented alias, and that build failed silently because libzypp cannot report
     * its builder failing. Both aliases then appeared in every refresh, which is the
     * "every repository is fetched twice" behaviour that looked like a mirror problem.
     */
    @Test
    fun `a repository file is left byte for byte as the image ships it`() {
        tree()
        val f = repo("openSUSE:repo-oss.repo")
        val before = f.readText()
        OpenSuseRepositories.prepare(rootfs)
        assertEquals("the repository file was modified", before, f.readText())
    }

    /** Which is the whole reason: it is the path the image ships its index under. */
    @Test
    fun `a cache directory is made at the path the image ships its index under`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        OpenSuseRepositories.prepare(rootfs)
        assertTrue(
            "no cache directory at /var/cache/zypp/solv/openSUSE:repo-oss",
            File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss").isDirectory
        )
    }

    /**
     * A shipped index must survive.
     *
     * If it does not, libzypp rebuilds it -- and the rebuild is the operation that
     * cannot report failure. So a directory that already holds an index is not
     * touched, and is not reported as newly created.
     */
    @Test
    fun `a shipped index and its cookie are left alone`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        val index = File(dir, "solv").apply { writeText("x".repeat(4096)) }
        val cookie = File(dir, "cookie").apply { writeText("status") }

        val r = OpenSuseRepositories.prepare(rootfs)

        assertTrue("the shipped index was removed", index.exists())
        assertEquals(4096L, index.length())
        assertTrue("the cookie was removed, so libzypp would rebuild", cookie.exists())
        assertTrue(
            "the shipped cache directory was reported as newly created: ${r.cacheDirs}",
            // Only solv/ is under test here; raw/ and packages/ were genuinely new.
            r.cacheDirs.none { it.startsWith("var/cache/zypp/solv/openSUSE") }
        )
    }

    /** Both alias forms must exist as directories; the service one is what is used. */
    @Test
    fun `cache directories are made for the service member names`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss", "2=openSUSE:repo-openh264")
        OpenSuseRepositories.prepare(rootfs)
        for (alias in listOf("openSUSE:repo-oss", "openSUSE:repo-openh264")) {
            assertTrue("no solv directory for $alias", File(rootfs, "var/cache/zypp/solv/$alias").isDirectory)
        }
    }

    /** A service repository index names the same repos a second way. */
    @Test
    fun `cache directories are made for aliases in the service repository index`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        val idx = File(rootfs, "usr/share/zypp/local/service/openSUSE/repo")
        idx.mkdirs()
        File(idx, "opensuse-leap-repoindex.xml").writeText(
            "<repoindex disturl=\"http://cdn.opensuse.org\">\n" +
                "<repo url=\"x\" alias=\"repo-oss-debug\" enabled=\"false\"/>\n</repoindex>\n"
        )
        OpenSuseRepositories.prepare(rootfs)
        assertTrue(File(rootfs, "var/cache/zypp/solv/repo-oss-debug").isDirectory)
    }

    /**
     * Regression: `repo_1_enabled=0` sits directly beneath `repo_1=openSUSE:repo-oss`
     * and begins with `repo_` too. Matching the prefix turned them into aliases "0"
     * and "1", and `var/cache/zypp/solv/0` really appeared on a device.
     */
    @Test
    fun `enabled and autorefresh lines are not aliases`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss", "2=openSUSE:repo-openh264")
        val r = OpenSuseRepositories.prepare(rootfs)
        assertFalse("'0' was taken for an alias: ${r.aliasesAfter}", "0" in r.aliasesAfter)
        assertFalse("'1' was taken for an alias: ${r.aliasesAfter}", "1" in r.aliasesAfter)
        assertFalse(File(rootfs, "var/cache/zypp/solv/0").exists())
        assertFalse(File(rootfs, "var/cache/zypp/solv/1").exists())
    }

    /** An alias becomes a directory name, so it must not escape the cache. */
    @Test
    fun `an alias cannot escape the cache directory`() {
        tree()
        repo("evil.repo", "[../../escape]\nname=x\nbaseurl=https://example.invalid/\n")
        OpenSuseRepositories.prepare(rootfs)
        assertFalse(
            "a directory was created outside the cache",
            File(rootfs, "var/cache/escape").exists() || File(rootfs, "escape").exists()
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

    /** Running twice must be harmless; setup runs on every session start. */
    @Test
    fun `running twice is harmless`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        OpenSuseRepositories.prepare(rootfs)
        val again = OpenSuseRepositories.prepare(rootfs)
        assertTrue("the second run reported a failure: ${again.unreadable}", again.unreadable.isEmpty())
        assertTrue(File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss").isDirectory)
    }

    /**
     * The app must say whether the shipped index arrived.
     *
     * Whether it does has been assumed one way and then the other for a week, and
     * every conclusion drawn from the assumption was wrong. The device log is the only
     * place that can settle it, so it has to state it.
     */
    @Test
    fun `a shipped index is reported as present with its size`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        // Real libsolv magic, so this reports a usable index rather than a
        // correctly-sized file that is not one.
        File(dir, "solv").writeText("SOLV" + "x".repeat(4092))

        val r = OpenSuseRepositories.prepare(rootfs)

        assertEquals(listOf("openSUSE:repo-oss 4096B 534f4c56 ok"), r.shippedIndex)
        assertTrue("the log does not mention the index: ${r.oneLine()}", r.oneLine().contains("4096B"))
    }

    @Test
    fun `a missing shipped index is reported as missing`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val r = OpenSuseRepositories.prepare(rootfs)
        assertEquals(listOf("openSUSE:repo-oss MISSING"), r.shippedIndex)
    }

    /** A zero-length index is worse than none: it looks like a working cache. */
    @Test
    fun `an empty shipped index is reported as empty rather than present`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        File(dir, "solv").writeText("")
        val r = OpenSuseRepositories.prepare(rootfs)
        assertTrue("an empty index was reported as usable: ${r.shippedIndex}", r.shippedIndex.single().contains("EMPTY"))
    }

    /**
     * The report must include whether the file can actually be opened, and its magic.
     *
     * `exists()` and `length()` are answered by the app's own uid; libzypp opens the
     * same path from inside the session and gets NULL. A week went on assuming those
     * two agree, with a 43 MB file reported present at exactly the right path while
     * the open still failed. So the app opens it, the way libzypp does, and says what
     * came back.
     */
    @Test
    fun `a readable index is reported with its magic`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        File(dir, "solv").writeText("SOLV" + "\u0000".repeat(100))

        val r = OpenSuseRepositories.prepare(rootfs)

        assertTrue(
            "a readable SOLV file was not reported as ok: ${r.shippedIndex}",
            r.shippedIndex.single().endsWith("ok")
        )
    }

    /** A file that is not an index is not an index, however large it is. */
    @Test
    fun `a file that is not a solv index is reported as such`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        File(dir, "solv").writeText("<html>not an index</html>")

        val r = OpenSuseRepositories.prepare(rootfs)

        assertTrue(
            "a non-SOLV file was not flagged: ${r.shippedIndex}",
            r.shippedIndex.single().contains("NOT-SOLV")
        )
    }

    @Test
    fun `a missing index is reported as missing`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val r = OpenSuseRepositories.prepare(rootfs)
        assertEquals(listOf("openSUSE:repo-oss MISSING"), r.shippedIndex)
    }

    /** A zero-length index is worse than none: it looks like a working cache. */
    @Test
    fun `an empty index is reported as empty rather than present`() {
        tree()
        repo("openSUSE:repo-oss.repo")
        service("1=openSUSE:repo-oss")
        val dir = File(rootfs, "var/cache/zypp/solv/openSUSE:repo-oss")
        assertTrue(dir.mkdirs())
        File(dir, "solv").writeText("")
        val r = OpenSuseRepositories.prepare(rootfs)
        assertEquals(listOf("openSUSE:repo-oss EMPTY"), r.shippedIndex)
    }
}

