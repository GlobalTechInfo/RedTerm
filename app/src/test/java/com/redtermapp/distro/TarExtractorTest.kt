package com.redtermapp.distro

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restoring an archive, without `tar` doing the hard links.
 *
 * The bug this covers only ever showed up on Arch, because Arch is the distro with four
 * hundred hard links in `/etc/ca-certificates/extracted/cadir` and Void has none there.
 * Every member of that group was recorded as a link, every `link()` failed with EPERM, and
 * the restore stopped with no file to show for it.
 */
class TarExtractorTest {

    private val tar: String? = sequenceOf("/bin/tar", "/usr/bin/tar")
        .firstOrNull { File(it).canExecute() }

    private lateinit var work: File

    init {
        // Os.symlink and Os.chmod are not mocked on a JVM test classpath, so both seams
        // are pointed at JDK equivalents. What is under test is the handling around them:
        // which mode the header asked for, and when it is applied.
        TarExtractor.makeSymlink = { target, path ->
            java.nio.file.Files.createSymbolicLink(
                java.nio.file.Paths.get(path), java.nio.file.Paths.get(target)
            )
        }
        TarExtractor.changeMode = { path, mode ->
            chmodCalls.add(File(path).name to mode)
            chmodOwnerBits(File(path), mode)
        }
    }

    private val chmodCalls: MutableList<Pair<String, Int>> = mutableListOf()

    /**
     * java.io cannot set group bits, so this applies owner bits only.
     *
     * Enough to assert what broke: an executable file coming back non-executable. The
     * group and other bits go through `Os.chmod` on device.
     */
    private fun chmod(mode: String, target: File) {
        val p = ProcessBuilder("chmod", mode, target.absolutePath).start()
        assertEquals(0, p.waitFor())
    }

    private fun chmodOwnerBits(target: File, mode: Int) {
        target.setReadable(mode and 256 != 0, true)
        target.setWritable(mode and 128 != 0, true)
        target.setExecutable(mode and 64 != 0, true)
    }

    private fun tree(): File {
        work = File(System.getProperty("java.io.tmpdir"), "rt-extract-${System.nanoTime()}")
        work.deleteRecursively()
        work.mkdirs()
        return work
    }

    private fun pack(name: String = "a.tar.gz"): File {
        File(work, "src/rootfs").mkdirs()
        val archive = File(work, name)
        // Packed the way the app packs: the rootfs is a directory *named* rootfs inside a
        // parent, so the archive members are "rootfs/..." and --strip-components=1 is what
        // removes the prefix.
        val parent = File(work, "src")
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        val process = ProcessBuilder(
            tar!!, "-czf", archive.absolutePath, "-C", parent.absolutePath, "rootfs"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("tar failed: $output", 0, process.waitFor())
        return archive
    }

    private fun build(name: String, populate: (File) -> Unit): File {
        File(work, "src/rootfs").mkdirs()
        populate(File(work, "src/rootfs"))
        return pack(name)
    }

    // -------------------------------------------------------------- hard links

    /**
     * The regression, in the shape Arch has it.
     *
     * Four hundred files that are hard links to each other, as
     * `/etc/ca-certificates/extracted/cadir` is. `tar` records all but the first as type
     * `1`, and `link()` on this filesystem fails — so these must be materialised as
     * independent copies holding the same content.
     */
    @Test
    fun `hard links are restored as independent copies`() {
        val tarBin = tar ?: return
        tree()
        build("hard.tar.gz") { root ->
            val dir = File(root, "etc/ca-certificates/extracted/cadir")
            dir.mkdirs()
            val first = File(dir, "ACCVRAIZ1.pem").apply { writeText("certificate") }
            for (i in 1 until 40) {
                val name = "%08x.0".format(i)
                val process = ProcessBuilder("ln", first.path, File(dir, name).path).start()
                assertEquals(0, process.waitFor())
            }
        }
        // The archive really does contain link entries, or this proves nothing.
        val listing = ProcessBuilder(tarBin, "-tvzf", File(work, "hard.tar.gz").absolutePath)
            .redirectErrorStream(true).start()
        val verbose = listing.inputStream.bufferedReader().readText()
        assertEquals(0, listing.waitFor())
        assertTrue("expected hard links in the archive", verbose.contains("link to"))

        val into = File(work, "into")
        val result = TarExtractor.extract(File(work, "hard.tar.gz"), into, stripComponents = 1)

        assertTrue("extraction failed: ${result.failed}", result.ok)
        assertTrue(
            "the links should have been materialised as copies",
            result.hardLinksCopied >= 39
        )
        val dir = File(into, "etc/ca-certificates/extracted/cadir")
        assertEquals("certificate", File(dir, "ACCVRAIZ1.pem").readText())
        assertEquals("certificate", File(dir, "0000000f.0").readText())
        // A copy, not a shared inode. Overwriting one must leave the other alone, which
        // is exactly what proot needs: it treats them as separate files, so aliasing them
        // behind its back is what a real hard link would break.
        File(dir, "ACCVRAIZ1.pem").writeText("changed")
        assertEquals(
            "the two paths share an inode, so they were linked rather than copied",
            "certificate",
            File(dir, "0000000f.0").readText()
        )
    }

    // ---------------------------------------------------------------- ordinary members

    @Test
    fun `regular files, directories and symlinks all come back`() {
        if (tar == null) return
        tree()
        val archive = build("plain.tar.gz") { root ->
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=arch\n")
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/tool").writeText("x".repeat(5000))
            Files.createSymbolicLink(File(root, "usr/bin/link").toPath(), Paths.get("tool"))
        }
        val into = File(work, "into")
        val result = TarExtractor.extract(archive, into, stripComponents = 1)
        assertTrue("extraction failed: ${result.failed}", result.ok)
        assertEquals("ID=arch\n", File(into, "etc/os-release").readText())
        assertEquals(5000, File(into, "usr/bin/tool").length().toInt())
        assertTrue(Files.isSymbolicLink(File(into, "usr/bin/link").toPath()))
        assertEquals("tool", Files.readSymbolicLink(File(into, "usr/bin/link").toPath()).toString())
    }

    /** A dangling link is a link, and restoring it verbatim is the whole point. */
    @Test
    fun `a dangling symlink comes back dangling`() {
        if (tar == null) return
        tree()
        val archive = build("dangle.tar.gz") { root ->
            File(root, "var").mkdirs()
            Files.createSymbolicLink(File(root, "var/lock").toPath(), Paths.get("../run/lock"))
        }
        val into = File(work, "into")
        assertTrue(TarExtractor.extract(archive, into, stripComponents = 1).ok)
        val link = File(into, "var/lock").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("../run/lock", Files.readSymbolicLink(link).toString())
        assertFalse("a dangling link must not exist()", File(into, "var/lock").exists())
    }

    /** Arch and Manjaro are full of paths longer than a ustar header's 100 bytes. */
    /**
     * The regression, and the one that made every restored distro unusable.
     *
     * `FileOutputStream` creates a file 0644 whatever the archive recorded, and this
     * extractor used to write every file that way and then move on. The result was a
     * restored rootfs in which nothing could be executed — reported by the user as
     * `/bin/bash: Permission denied`, which is just the first thing the startup script
     * tries to run.
     */
    @Test
    fun `permissions are restored, so a script is still executable`() {
        if (tar == null) return
        tree()
        val archive = build("modes.tar.gz") { root ->
            File(root, "bin").mkdirs()
            val tool = File(root, "bin/tool").apply { writeText("#!/bin/sh\necho hi\n") }
            File(root, "etc").mkdirs()
            File(root, "etc/plain.conf").writeText("nothing special")
            // A second directory, so the directory path is asserted on a name of its own
            // rather than being inferred from the file case.
            File(root, "srv").mkdirs()
            // Set through chmod rather than java.io: `setExecutable(bit, false)` addresses
            // *others*, not the owner, and the JVM's umask decides what a new file gets.
            // Real distro files have ordinary modes, so the test states them outright.
            listOf(tool, File(root, "etc/plain.conf"), File(root, "srv")).forEachIndexed { i, f ->
                chmod(if (i == 0) "755" else if (i == 1) "644" else "755", f)
            }
        }
        // The archive really does record the modes, or this proves nothing.
        val listing = ProcessBuilder(tar!!, "-tvzf", archive.absolutePath)
            .redirectErrorStream(true).start()
        val verbose = listing.inputStream.bufferedReader().readText()
        assertEquals(0, listing.waitFor())
        assertTrue(verbose.contains("rwxr-xr-x"))

        val into = File(work, "into")
        assertTrue(TarExtractor.extract(archive, into, stripComponents = 1).ok)

        assertTrue(
            "an executable came back non-executable: the whole distro would be unrunnable",
            File(into, "bin/tool").canExecute()
        )
        assertTrue(File(into, "etc/plain.conf").canRead())
        assertFalse(
            "a data file must not gain the execute bit",
            File(into, "etc/plain.conf").canExecute()
        )
        // The recorded modes themselves, which is what the decode has to get right.
        val asked = chmodCalls.toMap()
        assertEquals(
            "the script's recorded mode was not applied",
            0x1ED,
            asked["tool"]
        )
        assertEquals(
            "a data file gained the execute bit",
            0x1A4,
            asked["plain.conf"]
        )
        assertEquals(
            "a directory's recorded mode was not applied",
            0x1ED,
            asked["srv"]
        )
    }

    /**
     * Modification times.
     *
     * A package manager reading a restored rootfs sees every file as brand new without
     * these, and will happily reinstall the world.
     */
    @Test
    fun `modification times survive`() {
        if (tar == null) return
        tree()
        val stamp = 1_600_000_000_000L
        val archive = build("times.tar.gz") { root ->
            val file = File(root, "etc/old.conf").apply {
                parentFile.mkdirs()
                writeText("older")
            }
            assertTrue(file.setLastModified(stamp))
        }
        val into = File(work, "into")
        assertTrue(TarExtractor.extract(archive, into, stripComponents = 1).ok)
        val restored = File(into, "etc/old.conf")
        assertEquals(stamp, restored.lastModified())
    }

    /**
     * A directory whose recorded mode forbids writing must not stop the files inside it
     * being written.
     *
     * This is why directory modes are deferred to the end of the restore: applied as each
     * directory was created, a read-only one would refuse the rest of its own contents.
     */
    @Test
    fun `a directory recorded read-only still receives its contents`() {
        if (tar == null) return
        tree()
        val archive = build("ro.tar.gz") { root ->
            val dir = File(root, "etc").apply { mkdirs() }
            File(root, "etc/conf").writeText("inside a read-only directory")
            // Readable and traversable, so tar can pack it, but not writable.
            chmod("555", dir)
        }
        val into = File(work, "into")
        val result = TarExtractor.extract(archive, into, stripComponents = 1)
        assertTrue("extraction failed: ${result.failed}", result.ok)
        assertEquals("inside a read-only directory", File(into, "etc/conf").readText())
    }

    @Test
    fun `long paths survive`() {
        if (tar == null) return
        tree()
        val deep = "usr/lib/" + List(12) { "a-fairly-long-directory-name-$it" }.joinToString("/") +
            "/and-a-long-file-name-at-the-end.txt"
        val archive = build("long.tar.gz") { root ->
            File(root, deep).apply {
                parentFile.mkdirs()
                writeText("long path content")
            }
        }
        val into = File(work, "into")
        assertTrue(TarExtractor.extract(archive, into, stripComponents = 1).ok)
        assertEquals("long path content", File(into, deep).readText())
    }

    // ------------------------------------------------------------------- integrity

    /** A cut-short archive must fail rather than produce a plausible half-tree. */
    @Test
    fun `a truncated archive reports failure`() {
        if (tar == null) return
        tree()
        val archive = build("cut.tar.gz") { root ->
            File(root, "big.bin").writeText("z".repeat(300_000))
            File(root, "small.txt").writeText("s")
        }
        val bytes = archive.readBytes()
        File(work, "half.tar.gz").writeBytes(bytes.copyOf(bytes.size / 2))

        val into = File(work, "into")
        val result = TarExtractor.extract(File(work, "half.tar.gz"), into, stripComponents = 1)
        assertFalse("a truncated archive must not report success", result.ok)
    }

    /**
     * A member that tries to escape the restore directory is refused.
     *
     * A tar is untrusted input: `../../` is the standard way to write outside the
     * directory being restored into, and a restore that honoured it would be arbitrary
     * file creation on the device.
     */
    @Test
    fun `a member escaping the destination is refused`() {
        if (tar == null) return
        tree()
        val archive = build("escape.tar.gz") { root ->
            File(root, "innocent.txt").writeText("fine")
        }
        val into = File(work, "into")
        assertTrue(TarExtractor.extract(archive, into, stripComponents = 1).ok)
        assertTrue(File(into, "innocent.txt").isFile)

        // The check itself, against the member names a hostile archive actually uses.
        val dest = File(work, "dest").apply { mkdirs() }
        for (member in listOf(
            "../escaped.txt",
            "../../escaped.txt",
            "rootfs/../../escaped.txt",
            "a/../../../escaped.txt"
        )) {
            assertEquals(
                "a traversing member must not resolve: $member",
                null,
                TarExtractor.resolve(dest, member)
            )
        }
        // An absolute member name is contained rather than refused: File treats a leading
        // separator as relative, so it lands inside the destination, which is the safe
        // outcome and the one tar's own --strip-components produces.
        assertEquals(
            dest.canonicalPath + "/etc/escaped.txt",
            TarExtractor.resolve(dest, "/etc/escaped.txt")?.path
        )
        assertEquals(
            null,
            TarExtractor.resolve(dest, "..")
        )
    }

    @Test
    fun `strip components drops exactly what it is told`() {
        assertEquals("etc/os-release", TarExtractor.stripPrefix("arch/etc/os-release", 1))
        assertEquals("os-release", TarExtractor.stripPrefix("arch/etc/os-release", 2))
        assertEquals("arch/etc/os-release", TarExtractor.stripPrefix("arch/etc/os-release", 0))
        assertEquals("", TarExtractor.stripPrefix("arch", 1))
    }
}