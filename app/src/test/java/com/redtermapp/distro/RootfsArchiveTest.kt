package com.redtermapp.distro

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a backup is allowed to contain, and the archive it produces.
 *
 * The bug this covers could not be found by reading the code. `tar: unknown file type
 * '140000'` is toybox refusing a unix socket — `0140000` is `S_IFSOCK` masked out of the
 * mode — and toybox does not name the offending file, so no amount of inspecting a failed
 * backup says which path caused it. The tests therefore build a tree containing one and
 * check that the archiver is never handed it.
 */
class RootfsArchiveTest {

    private lateinit var parent: File
    private lateinit var rootfs: File

    private fun tree(): File {
        parent = File(System.getProperty("java.io.tmpdir"), "rt-archive-${counter++}")
        parent.deleteRecursively()
        rootfs = File(parent, "rootfs").apply { mkdirs() }
        return rootfs
    }

    private var counter = 0

    private fun file(relative: String, content: String = "x"): File =
        File(rootfs, relative).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    private fun plan(excluded: Set<String> = emptySet()) =
        RootfsArchive.plan(rootfs, excluded)



    /** A real fifo: unrepresentable by every tar that cannot store one, like a socket. */
    private fun fifo(relative: String) {
        val path = File(rootfs, relative).apply { parentFile.mkdirs() }
        val result = Runtime.getRuntime().exec(arrayOf("mkfifo", path.path)).waitFor()
        assertEquals("mkfifo failed for $relative", 0, result)
        assertFalse("mkfifo did not create anything", path.isFile && !path.exists())
    }

    private val tar: String? = sequenceOf("/bin/tar", "/usr/bin/tar").firstOrNull {
        File(it).canExecute()
    }

    // ------------------------------------------------------------- what is included

    @Test
    fun `regular files are archived`() {
        tree()
        file("etc/os-release", "ID=debian")
        val entries = plan().entries
        assertTrue(entries.contains("rootfs/etc/os-release"))
        assertTrue(entries.contains("rootfs/etc"))
    }

    @Test
    fun `directories come before what they contain`() {
        tree()
        file("a/b/c/deep.txt")
        val entries = plan().entries
        // An extraction creates each entry as it is read, so a child listed before its
        // parent fails or lands in the wrong place.
        for (parentPath in listOf("rootfs", "rootfs/a", "rootfs/a/b", "rootfs/a/b/c")) {
            assertTrue(
                "$parentPath must precede its contents",
                entries.indexOf(parentPath) < entries.indexOf("rootfs/a/b/c/deep.txt")
            )
        }
    }

    @Test
    fun `symlinks are archived as symlinks and never followed`() {
        tree()
        val target = file("real/target.txt", "content")
        Files.createSymbolicLink(File(rootfs, "link").toPath(), Paths.get("real/target.txt"))
        // A link pointing at a directory must not turn into a directory entry.
        Files.createSymbolicLink(File(rootfs, "dirlink").toPath(), Paths.get("real"))
        val entries = plan().entries
        assertTrue(entries.contains("rootfs/link"))
        assertTrue(entries.contains("rootfs/dirlink"))
        assertEquals(
            "following a symlink would archive a second copy of the target",
            entries.count { it.endsWith("target.txt") },
            1
        )
        assertTrue(target.exists())
    }

    /**
     * Dangling symlinks, which is what Arch is full of.
     *
     * `/var/lock` -> `../run/lock` and every `libfoo.so` -> `libfoo.so.1.6.0` are links
     * whose targets are not in the image. `getCanonicalPath` cannot resolve one, so it
     * returns the path unchanged: the entry reports as not a link, not a file, not a
     * directory and not readable — as if it were nothing at all. Treating that as a lost
     * file refused a backup of a multi-gigabyte Manjaro over five entries.
     *
     * None of this loses anything: a link has no content, so restoring it verbatim is
     * exactly right, and proot creates `/run/lock` at launch.
     */
    @Test
    fun `dangling symlinks are archived as links, not treated as lost files`() {
        tree()
        file("etc/os-release", "ID=arch")
        // Exactly the shapes named in the failure.
        File(rootfs, "var").mkdirs()
        Files.createSymbolicLink(File(rootfs, "var/lock").toPath(), Paths.get("../run/lock"))
        File(rootfs, "usr/lib").mkdirs()
        Files.createSymbolicLink(
            File(rootfs, "usr/lib/libkeyutils.so").toPath(),
            Paths.get("libkeyutils.so.1.6.0")
        )
        File(rootfs, "usr/share/licenses/xz").mkdirs()
        Files.createSymbolicLink(
            File(rootfs, "usr/share/licenses/xz/COPYING").toPath(),
            Paths.get("../../licenses/shared/xz-COPYING")
        )

        val result = plan()
        assertTrue(
            "a dangling symlink must be archived, not skipped: ${result.skipped}",
            result.entries.contains("rootfs/var/lock")
        )
        assertTrue(result.entries.contains("rootfs/usr/lib/libkeyutils.so"))
        assertTrue(result.entries.contains("rootfs/usr/share/licenses/xz/COPYING"))
        assertTrue(
            "nothing should be reported unreadable: ${result.skipped}",
            result.skipped.none { it.reason == "not readable" }
        )
    }

    /** And it must survive a real round trip, since restoring it verbatim is the point. */
    @Test
    fun `a dangling symlink comes back as a dangling symlink`() {
        val tarBin = tar ?: return
        tree()
        file("etc/os-release", "ID=arch")
        File(rootfs, "var").mkdirs()
        Files.createSymbolicLink(File(rootfs, "var/lock").toPath(), Paths.get("../run/lock"))
        val result = plan()
        val list = File(parent, "list.txt").apply {
            writeText(result.entries.joinToString("\n") { it + "\n" })
        }
        val archive = File(parent, "out.tar.gz")
        ProcessBuilder(
            tarBin, "--no-recursion", "-czf", archive.absolutePath,
            "-C", parent.absolutePath, "-T", list.absolutePath
        ).redirectErrorStream(true).start().waitFor()

        val restored = File(parent, "restored").apply { mkdirs() }
        ProcessBuilder(
            tarBin, "-xzf", archive.absolutePath,
            "-C", restored.absolutePath, "--strip-components=1"
        ).redirectErrorStream(true).start().waitFor()

        val link = File(restored, "var/lock").toPath()
        assertTrue("the link must come back as a link", Files.isSymbolicLink(link))
        assertEquals("../run/lock", Files.readSymbolicLink(link).toString())
    }

    /**
     * The regression, and the reason a count check did not catch it.
     *
     * On Android the rootfs path always sits under `/data/data`, which is a symlink to
     * `/data/user/0`. Comparing a child's canonical path against its absolute path
     * therefore answered true for *every* entry: nothing was walked, each child was
     * archived as a symlink instead, and the backup came out as a tree of links in a few
     * milliseconds. The archive then passed verification, because the member count
     * matched the plan — both were the same wrong number.
     *
     * Reproduced here with a rootfs reached through a symlink, which is the same shape.
     */
    @Test
    fun `a rootfs reached through a symlinked parent is still walked`() {
        tree()
        val real = File(parent, "real-rootfs")
        File(real, "etc").apply { mkdirs() }.resolve("os-release").writeText("ID=arch")
        File(real, "usr/bin").apply { mkdirs() }.resolve("bash").writeText("x".repeat(500))
        // `arch` points at the real tree, exactly as /data/data -> /data/user/0 does.
        val viaLink = Files.createSymbolicLink(File(parent, "arch").toPath(), Paths.get(real.name)).toFile()

        val result = RootfsArchive.plan(viaLink, emptySet())
        assertTrue(
            "the tree below a symlinked parent must still be walked: ${result.entries}",
            result.entries.any { it.endsWith("etc/os-release") }
        )
        assertTrue(
            "file contents must be counted, not archived as links",
            result.contentBytes >= 500
        )
        // And nothing may be reported as a symlink.
        assertEquals(
            "entries were misread as symlinks: ${result.skipped}",
            0,
            result.skipped.count { it.path.contains("os-release") }
        )
    }

    /** A symlink *inside* the tree is still archived as a link, and not descended into. */
    @Test
    fun `a symlink inside the tree is still archived as a link`() {
        tree()
        file("real/one.txt", "a")
        file("real/two.txt", "b")
        Files.createSymbolicLink(File(rootfs, "link").toPath(), Paths.get("real"))
        val result = plan()
        assertTrue(result.entries.contains("rootfs/link"))
        assertEquals(
            "following the link would archive the target a second time",
            result.entries.count { it.startsWith("rootfs/real/") },
            2
        )
    }

    @Test
    fun `hard links are both archived`() {
        tree()
        val original = file("usr/bin/tool", "binary")
        // A hard link is a regular file to the archiver and stays one. Only sockets,
        // fifos and devices have no representation.
        Runtime.getRuntime().exec(arrayOf("ln", original.path, File(rootfs, "usr/bin/alias").path))
            .waitFor()
        val entries = plan().entries
        assertTrue(entries.contains("rootfs/usr/bin/tool"))
        assertTrue(entries.contains("rootfs/usr/bin/alias"))
    }

    // --------------------------------------------------------- what is left out

    /**
     * The regression.
     *
     * Anything that is not a regular file, directory or symlink aborted the whole
     * archive, and toybox's message names no file, so nothing pointed at the cause. A
     * unix socket is the common case — `/run` and `/var/run` fill with them once a
     * distro has run — and a fifo takes the identical path through our planner, so the
     * test creates the one this machine can.
     */
    @Test
    fun `an unrepresentable file type is excluded rather than handed to the archiver`() {
        tree()
        file("etc/os-release", "ID=alpine")
        fifo("run/pipe")
        val result = plan()
        assertFalse(
            "an unrepresentable type reached the archiver: ${result.entries}",
            result.entries.any { it.endsWith("run/pipe") }
        )
        assertTrue(
            "it must be reported, not silently dropped",
            result.skipped.any { it.path.endsWith("run/pipe") }
        )
        assertTrue("the rest of the tree must still be archived", result.entries.contains("rootfs/etc/os-release"))
    }

    @Test
    fun `fifos are excluded`() {
        tree()
        file("etc/os-release", "ID=debian")
        fifo("run/pipe")
        val result = plan()
        assertFalse(result.entries.any { it.endsWith("run/pipe") })
        assertTrue(
            result.skipped.any { it.path.endsWith("run/pipe") }
        )
    }

    @Test
    fun `proot bind paths are excluded whole`() {
        tree()
        file("etc/os-release", "ID=debian")
        file("dev/a-stub")
        file("storage/real-file")
        val result = plan(setOf(File(rootfs, "dev").path))
        assertFalse(result.entries.any { it.startsWith("rootfs/dev/") })
        assertTrue(
            "a bind must not take real content with it",
            result.entries.contains("rootfs/storage/real-file")
        )
    }

    @Test
    fun `an unreadable path is skipped and reported rather than failing`() {
        tree()
        file("etc/os-release", "ID=debian")
        val locked = file("root/locked")
        locked.setReadable(false, false)
        try {
            val result = plan()
            assertTrue(result.entries.contains("rootfs/etc/os-release"))
            assertTrue(result.skipped.any { it.path.contains("locked") })
        } finally {
            locked.setReadable(true, false)
        }
    }

    /**
     * A fifo is never opened for reading.
     *
     * Reading one blocks until a writer appears, so a backup that opened it would hang
     * rather than fail. `access(R_OK)` answers true for a fifo, so readability is not what
     * excludes it — being neither a file nor a directory is, and that is the branch under
     * test.
     */
    @Test
    fun `a fifo is skipped rather than read`() {
        tree()
        file("etc/os-release", "ID=alpine")
        fifo("run/blocking")
        val result = plan()
        assertFalse(result.entries.any { it.endsWith("run/blocking") })
        assertTrue(
            "a fifo must be reported as skipped",
            result.skipped.any { it.path.endsWith("run/blocking") }
        )
    }

    @Test
    fun `the skip summary names the counts and gives examples`() {
        tree()
        file("etc/os-release", "ID=alpine")
        fifo("run/one.pipe")
        fifo("run/two.pipe")
        val summary = RootfsArchive.describeSkipped(plan().skipped)
        assertTrue("summary was $summary", summary!!.contains("2 \u00d7 "))
        assertTrue(summary.contains("run/one.pipe"))
    }

    @Test
    fun `no skips reports nothing`() {
        tree()
        file("etc/os-release", "ID=debian")
        assertEquals(null, RootfsArchive.describeSkipped(plan().skipped))
    }

    @Test
    fun `the content size covers regular files only`() {
        tree()
        file("etc/os-release", "0123456789")
        file("usr/bin/tool", "01234")
        val result = plan()
        assertEquals(15L, result.contentBytes)
    }

    // ------------------------------------------- the archive tar is actually given

    /**
     * End to end through a real tar: the exact command the backup runs, on a tree
     * containing a socket.
     *
     * This is the test that says the invocation is right, rather than that the plan is
     * right — a plan can exclude the entry perfectly and still be handed to tar in a way
     * that re-descends into it and fails the same way as before, which is exactly what
     * `--no-recursion` rules out.
     */
    @Test
    fun `the archive command succeeds on a tree containing an unrepresentable type`() {
        val tarBin = tar ?: return
        tree()
        file("etc/os-release", "ID=arch\nNAME=Arch")
        file("usr/bin/big", "z".repeat(2048))
        val link = File(rootfs, "usr/bin/link")
        Files.createSymbolicLink(link.toPath(), Paths.get("big"))
        fifo("var/run/agent.pipe")
        run {
            val result = plan()
            val list = File(parent, "list.txt")
            list.writeText(result.entries.joinToString("\n") { it + "\n" })

            val archive = File(parent, "out.tar.gz")
            val process = ProcessBuilder(
                tarBin, "--no-recursion", "-czf", archive.absolutePath,
                "-C", parent.absolutePath, "-T", list.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()

            assertEquals("tar failed: $output", 0, code)
            assertTrue(archive.length() > 0)

            // And it must extract back into something that looks like the original.
            val restored = File(parent, "restored").apply { mkdirs() }
            val restore = ProcessBuilder(
                tarBin, "-xzf", archive.absolutePath,
                "-C", restored.absolutePath, "--strip-components=1"
            ).redirectErrorStream(true).start()
            val restoreOutput = restore.inputStream.bufferedReader().readText().trim()
            assertEquals("extract failed: $restoreOutput", 0, restore.waitFor())

            assertEquals(
                File(rootfs, "etc/os-release").readText(),
                File(restored, "etc/os-release").readText()
            )
            assertEquals(
                File(rootfs, "usr/bin/big").readText(),
                File(restored, "usr/bin/big").readText()
            )
            val restoredLink = File(restored, "usr/bin/link").toPath()
            assertTrue(
                "the symlink came back as a regular file",
                Files.isSymbolicLink(restoredLink)
            )
            assertEquals("big", Files.readSymbolicLink(restoredLink).toString())
            assertFalse(
                "the fifo should not have been archived",
                File(restored, "var/run/agent.pipe").exists()
            )
        }
    }

    /**
     * The check that makes a truncated archive a refusal rather than a backup.
     *
     * A non-empty file that opens is not a usable backup: cutting one short still leaves
     * something on disk, and swapping it in replaces a good backup with a broken one.
     */
    @Test
    fun `a truncated archive fails to list`() {
        val tarBin = tar ?: return
        tree()
        file("etc/os-release", "ID=arch")
        file("usr/share/doc/big", "y".repeat(64 * 1024))
        val result = plan()
        val list = File(parent, "list.txt").apply {
            writeText(result.entries.joinToString("\n") { it + "\n" })
        }
        val archive = File(parent, "out.tar.gz")
        ProcessBuilder(
            tarBin, "--no-recursion", "-czf", archive.absolutePath,
            "-C", parent.absolutePath, "-T", list.absolutePath
        ).redirectErrorStream(true).start().waitFor()

        val full = ProcessBuilder(tarBin, "-tzf", archive.absolutePath)
            .redirectErrorStream(false).start()
        val fullCount = full.inputStream.bufferedReader().readLines().count { it.isNotEmpty() }
        assertEquals(0, full.waitFor())
        assertTrue("expected a non-empty archive", fullCount > 0)

        // Cut the gzip stream in half.
        val bytes = archive.readBytes()
        val cut = File(parent, "cut.tar.gz").apply {
            writeBytes(bytes.copyOf(bytes.size / 2))
        }
        assertTrue("the cut archive is not empty", cut.length() > 0)

        val broken = ProcessBuilder(tarBin, "-tzf", cut.absolutePath)
            .redirectErrorStream(false).start()
        broken.inputStream.bufferedReader().readLines()
        assertTrue(
            "a truncated archive must fail to list, or it passes for a good backup",
            broken.waitFor() != 0
        )
    }
}
/**
 * Listing an archive back to count it.
 *
 * Written after a hang, where nothing had gone wrong: `tar` was blocked writing to a
 * stderr pipe nobody was reading, so it looked stuck and produced no message at all. The
 * test drives the same shape — a `tar` that says a great deal on stderr while its stdout
 * is being counted.
 */
class TarMembersTest {

    private val tar: String? = sequenceOf("/bin/tar", "/usr/bin/tar").firstOrNull {
        File(it).canExecute()
    }

    private fun archiveWith(files: Int): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rt-members-${System.nanoTime()}")
        dir.deleteRecursively()
        File(dir, "rootfs").apply { mkdirs() }
        for (i in 0 until files) File(dir, "rootfs/f$i").writeText("content $i")
        val archive = File(dir, "a.tar.gz")
        ProcessBuilder(tar!!, "-czf", archive.absolutePath, "-C", dir.absolutePath, "rootfs")
            .redirectErrorStream(true).start().waitFor()
        return archive
    }

    @Test
    fun `a whole archive is counted`() {
        if (tar == null) return
        val archive = archiveWith(250)
        val count = TarMembers.count(archive)
        assertTrue("counted $count", count > 250)
    }

    @Test
    fun `a missing archive is reported as unreadable rather than as zero`() {
        assertEquals(-1, TarMembers.count(File("/tmp/definitely-not-here-9f2a.tar.gz")))
    }

    /**
     * A truncated archive must not count as a short one.
     *
     * Zero and "cut short" are different answers and only one of them is a usable backup,
     * so the two are never allowed to be the same number.
     */
    @Test
    fun `a truncated archive is not counted`() {
        if (tar == null) return
        val archive = archiveWith(400)
        val bytes = archive.readBytes()
        val cut = File(archive.parentFile, "cut.tar.gz")
            .apply { writeBytes(bytes.copyOf(bytes.size / 3)) }
        assertEquals(-1, TarMembers.count(cut))
    }

    /** Bounded: a tar that never exits must not block the caller for ever. */
    @Test
    fun `counting finishes on an archive tar cannot finish reading`() {
        if (tar == null) return
        val dir = File(System.getProperty("java.io.tmpdir"), "rt-members-bad-${System.nanoTime()}")
        dir.mkdirs()
        val junk = File(dir, "junk.tar.gz")
        junk.writeBytes(ByteArray(200_000) { 0x41 })
        assertEquals(-1, TarMembers.count(junk))
    }
}
