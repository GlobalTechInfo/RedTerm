package com.redtermapp.distro

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installing a rootfs must leave the modes the archive recorded.
 *
 * The extraction this covers used to `chmod` every directory to 0755 as it created it and
 * reduce every file to "executable or not", so the mode in the header was read and then
 * discarded. Nothing detected it, because the published images were correct throughout and
 * the damage was identical on every entry — a tree that is uniformly wrong passes any check
 * that only asks "is it readable".
 *
 * It showed up on device as a pacman transaction on every package:
 *
 *   warning: directory permissions differ on /tmp/
 *   filesystem: 755  package: 1777
 *
 * and the fix attempted first — walking the tree afterwards and chmod-ing again — could
 * only ever agree with the flattening, because it had no other mode to work from.
 *
 * Real `tar` builds the fixture and a real `chmod` applies each mode, because `java.io`
 * cannot set group bits at all and a sticky or setgid directory is exactly what is under
 * test here.
 */
class RootfsInstallExtractorTest {

    // Kotlin has no octal literals. Decimal, as elsewhere in this package
    // (DistroInstaller.DIR_MODE is 0755).
    private companion object {
        const val TMP_STICKY = 1023 // 01777
        const val LIB_SETGID = 1517 // 02755
        const val ROOT_OWNER_ONLY = 488 // 0750
        const val READ_ONLY = 365 // 0555
        const val SECRET = 448 // 0700
        const val EXECUTABLE = 493 // 0755
        const val PLAIN_FILE = 420 // 0644
        const val PRIVATE_FILE = 384 // 0600
        const val MODE_MASK = 0xFFF
    }

    private val tar: String? = sequenceOf("/bin/tar", "/usr/bin/tar")
        .firstOrNull { File(it).canExecute() }

    private lateinit var work: File

    /** Every mode the extractor asked for, in the order it asked. */
    private val modeCalls = mutableListOf<Pair<String, Int>>()

    init {
        // Os.chmod and Os.symlink are not mocked on a JVM classpath, so both seams point
        // at real equivalents. What is under test is which mode is requested, and when.
        RootfsInstallExtractor.changeMode = { path, mode ->
            modeCalls += File(path).name to mode
            chmod(File(path), mode and MODE_MASK)
        }
        RootfsInstallExtractor.makeSymlink = { target, path ->
            Files.createSymbolicLink(Paths.get(path), Paths.get(target))
        }
    }

    @After
    fun cleanUp() {
        if (!::work.isInitialized) return
        // A 0555 or 0700 directory cannot be deleted until it is writable again, which
        // is what these three modes are testing.
        val fix = ProcessBuilder("chmod", "-R", "u+rwX", work.absolutePath).start()
        fix.waitFor()
        work.deleteRecursively()
    }

    private fun chmod(target: File, mode: Int) {
        val octal = "0" + Integer.toOctalString(mode and MODE_MASK)
        val p = ProcessBuilder("chmod", octal, target.absolutePath).start()
        assertEquals("chmod $octal ${target.name} failed", 0, p.waitFor())
    }

    /**
     * The recorded mode, read through the filesystem rather than `PosixFilePermission`.
     *
     * `SETUID`, `SETGID` and `STICKY` were added to that enum in API 26 and are absent
     * from the android.jar stub the unit tests compile against, which is the same trap
     * `org.json` walks into. Sticky and setgid directories are precisely what this test
     * exists to check, so reading the mode is not optional here.
     */
    private fun modeOf(target: File): Int {
        assertTrue("$target does not exist", target.exists())
        val mode = (Files.getAttribute(target.toPath(), "unix:mode") as Number).toInt()
        return mode and 0xFFF
    }

    /**
     * The modes a real distribution ships, each chosen because flattening it to 0755
     * destroys it: `/tmp` is sticky, `/root` is not world-readable, `/usr/lib` is setgid,
     * and `/opt/readonly` has no write bit for anybody.
     */
    private fun buildSource(): File {
        val rootfs = File(work, "src/rootfs")
        rootfs.mkdirs()

        fun dir(mode: Int, vararg path: String): File {
            val d = path.fold(rootfs) { acc, seg -> File(acc, seg).apply { mkdirs() } }
            chmod(d, mode)
            return d
        }

        fun file(mode: Int, path: String, content: String): File {
            val f = File(rootfs, path)
            f.parentFile.mkdirs()
            f.writeText(content)
            chmod(f, mode)
            return f
        }

        dir(TMP_STICKY, "tmp")
        dir(ROOT_OWNER_ONLY, "root")
        dir(LIB_SETGID, "usr", "lib")
        dir(SECRET, "etc", "secret")
        file(EXECUTABLE, "bin/tool", "#!/bin/sh")
        file(PLAIN_FILE, "etc/passwd", "root:x:0:0")
        file(PRIVATE_FILE, "etc/shadow", "root:!:19000")
        // A child inside a directory with no write bit at all, so that applying the
        // parent's mode on arrival would leave nowhere to write it.
        file(PLAIN_FILE, "opt/readonly/keep.txt", "hello")
        dir(READ_ONLY, "opt", "readonly")

        File(rootfs, "var").mkdirs()
        Files.createSymbolicLink(
            Paths.get(File(rootfs, "var/lock").absolutePath),
            Paths.get("../run/lock")
        )
        return rootfs
    }

    private fun pack(rootfs: File): File {
        val out = File(work, "rootfs.tar")
        val p = ProcessBuilder(tar!!, "-cf", out.absolutePath, "-C", rootfs.parentFile.absolutePath, rootfs.name).start()
        assertEquals("tar failed", 0, p.waitFor())
        return out
    }

    private fun extract(archive: File, into: File): Int {
        into.mkdirs()
        FileInputStream(archive).use { fis ->
            TarArchiveInputStream(fis).use { tarIn ->
                return RootfsInstallExtractor.extract(tarIn, into)
            }
        }
    }

    private fun tree(): File {
        work = File(System.getProperty("java.io.tmpdir"), "rt-install-${System.nanoTime()}")
        work.mkdirs()
        return work
    }

    @Test
    fun `the modes the archive recorded survive extraction`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        assertEquals("sticky /tmp was flattened", TMP_STICKY, modeOf(File(out, "tmp")))
        assertEquals("/root was loosened", ROOT_OWNER_ONLY, modeOf(File(out, "root")))
        assertEquals("setgid dropped from /usr/lib", LIB_SETGID, modeOf(File(out, "usr/lib")))
        assertEquals("read-only dir gained a write bit", READ_ONLY, modeOf(File(out, "opt/readonly")))
        assertEquals("a 0700 directory was widened", SECRET, modeOf(File(out, "etc/secret")))
    }

    @Test
    fun `file modes are the recorded ones, not an executable guess`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        assertEquals(EXECUTABLE, modeOf(File(out, "bin/tool")))
        assertEquals(PLAIN_FILE, modeOf(File(out, "etc/passwd")))
        assertEquals(PRIVATE_FILE, modeOf(File(out, "etc/shadow")))
    }

    @Test
    fun `a file inside a read-only directory is still written`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        val keep = File(out, "opt/readonly/keep.txt")
        assertTrue("the child of a 0555 directory was not extracted", keep.exists())
        assertEquals("hello", keep.readText())
        assertEquals(PLAIN_FILE, modeOf(keep))
    }

    @Test
    fun `a symlink stays a symlink`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        val link = File(out, "var/lock")
        assertTrue("var/lock is not a symlink", Files.isSymbolicLink(link.toPath()))
        assertEquals("../run/lock", Files.readSymbolicLink(link.toPath()).toString())
    }

    /**
     * The ordering, asserted rather than assumed.
     *
     * Directory modes are applied after the contents, deepest first. A parent chmod-ed
     * while its children were still to come would stop them being created at all.
     */
    @Test
    fun `directory modes are applied after the tree, not on arrival`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        val childIndex = modeCalls.indexOfLast { it.first == "keep.txt" }
        val parentIndex = modeCalls.indexOfFirst { it.first == "readonly" }
        assertTrue("the child was never chmod-ed", childIndex >= 0)
        assertTrue("the directory mode was never applied", parentIndex >= 0)
        assertTrue(
            "the directory was chmod-ed at $parentIndex before its child at $childIndex; " +
                "a read-only parent applied on arrival blocks everything below it",
            parentIndex > childIndex
        )
    }

    /** Setuid, setgid and sticky must survive the mask; only the file-type nibble goes. */
    @Test
    fun `the mask keeps special bits and drops the file type`() {
        tree()
        val out = File(work, "out")
        extract(pack(buildSource()), out)

        val requested = modeCalls.associate { it.first to it.second }
        assertEquals("sticky was dropped from /tmp", TMP_STICKY, requested["tmp"])
        assertEquals("setgid was dropped from /usr/lib", LIB_SETGID, requested["lib"])
    }
}