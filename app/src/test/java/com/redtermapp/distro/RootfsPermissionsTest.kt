package com.redtermapp.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission gap between the app and a rootfs.
 *
 * A distro image ships files only root can read and the app is not root, so a backup
 * silently omits them. `/etc/shadow` was one of them, and a backup without it restores
 * into a distro where login cannot work — while reporting success. The shell reaches
 * those files only because proot fakes it, which is exactly why the gap is invisible from
 * inside a session.
 */
class RootfsPermissionsTest {

    private lateinit var rootfs: File
    private val created = mutableListOf<File>()

    /**
     * Unique per test, because a previous test leaves a mode-000 directory behind that
     * `deleteRecursively` cannot remove, and a shared name would then be reused dirty.
     */
    private fun tree(): File {
        val parent = File(System.getProperty("java.io.tmpdir"), "rt-perms-${System.nanoTime()}")
        created.add(parent)
        rootfs = File(parent, "rootfs").apply { mkdirs() }
        return rootfs
    }

    @org.junit.After
    fun cleanUp() {
        for (dir in created) {
            dir.walkBottomUp().forEach { it.setReadable(true, true); it.setExecutable(true, true) }
            dir.deleteRecursively()
        }
    }

    private fun file(relative: String, content: String): File =
        File(rootfs, relative).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    /** The state a Fedora image ships in: readable by nobody, not even the owner. */
    private fun chmod000(relative: String) {
        val target = File(rootfs, relative)
        assertTrue(target.setReadable(false, false))
        assertTrue(target.setWritable(false, false))
        assertTrue(target.setExecutable(false, false))
        assertFalse("$relative should not be readable", target.canRead())
    }

    @Test
    fun `a file only root can read is made readable`() {
        tree()
        val shadow = file("etc/shadow", "root:!:19000::::::")
        chmod000("etc/shadow")
        val report = RootfsArchive.makeReadable(rootfs, emptySet())
        assertEquals(1, report.files)
        assertTrue(shadow.canRead())
        assertTrue(report.stillUnreadable.isEmpty())
    }

    @Test
    fun `a locked directory is made traversable`() {
        tree()
        file("locked/inside.txt", "content")
        val locked = File(rootfs, "locked")
        assertTrue(locked.setReadable(false, false))
        assertTrue(locked.setExecutable(false, false))
        // Nothing can be listed from here until the owner can get back in.
        assertTrue(locked.listFiles().isNullOrEmpty())

        val report = RootfsArchive.makeReadable(rootfs, emptySet())
        assertTrue("the directory was not repaired", report.directories >= 1)
        assertTrue(locked.listFiles()?.isNotEmpty() == true)
        assertTrue(File(rootfs, "locked/inside.txt").canRead())
    }

    /** Nothing is changed where it is already readable. */
    @Test
    fun `a readable tree is left alone`() {
        tree()
        file("etc/os-release", "ID=fedora")
        val report = RootfsArchive.makeReadable(rootfs, emptySet())
        assertEquals(0, report.files)
        assertEquals(0, report.directories)
    }

    /** Bound paths are proot's, not the distro's, and must not be touched. */
    @Test
    fun `bound paths are not modified`() {
        tree()
        val dev = file("dev/null-stub", "")
        chmod000("dev/null-stub")
        RootfsArchive.makeReadable(rootfs, setOf(File(rootfs, "dev").path))
        assertFalse(
            "a proot bind stub was made readable",
            dev.canRead()
        )
    }

    /**
     * The end-to-end consequence.
     *
     * Planning before the repair drops the file; planning after it keeps it. The pair is
     * what makes the ordering matter, and the ordering is the whole fix.
     */
    @Test
    fun `a locked file reaches the archive only after the repair`() {
        tree()
        file("etc/os-release", "ID=fedora")
        file("etc/shadow", "root:!:19000::::::")
        chmod000("etc/shadow")

        val before = RootfsArchive.plan(rootfs, emptySet())
        assertFalse(
            "precondition: the locked file must be skipped before the repair",
            before.entries.contains("rootfs/etc/shadow")
        )
        assertTrue(
            before.skipped.any { it.path.endsWith("etc/shadow") && it.reason == "not readable" }
        )

        RootfsArchive.makeReadable(rootfs, emptySet())

        val after = RootfsArchive.plan(rootfs, emptySet())
        assertTrue(
            "the repaired file must be archived: ${after.skipped}",
            after.entries.contains("rootfs/etc/shadow")
        )
        assertTrue(
            "nothing should be reported unreadable after the repair",
            after.skipped.none { it.reason == "not readable" }
        )
    }
}