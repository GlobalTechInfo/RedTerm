package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Four published images ship `/root` as `0550`: oracle, fedora, almalinux, rocky.
 *
 *     dr-xr-x---  root/
 *
 * On Android the extracted tree belongs to the app's uid, so the *owner* bits are what
 * apply, and `0550` has no owner-write bit at all. `File.writeText` then fails with
 * EACCES and, in the activity it was called from, took the process down:
 *
 *     java.io.FileNotFoundException: .../rootfs/oracle/root/.bashrc:
 *         open failed: EACCES (Permission denied)
 *
 * It looked Oracle-specific because Oracle is the first RHEL-family image one tends to
 * install. It is all four.
 */
class BashrcWriterTest {

    private val work = File(System.getProperty("java.io.tmpdir"), "rt-bashrc-${System.nanoTime()}")

    private fun chmod(mode: Int, f: File) {
        f.setReadable(mode and 256 != 0, false)   // 0400
        f.setWritable(mode and 128 != 0, false)    // 0200
        f.setExecutable(mode and 64 != 0, false)   // 0100
    }

    private fun modeOf(f: File): Int {
        var m = 0
        if (f.canRead()) m = m or 256
        if (f.canWrite()) m = m or 128
        if (f.canExecute()) m = m or 64
        return m
    }

    /** Points both seams at real java.io, since `Os` is a stub off-device. */
    private fun withRealSeams(body: () -> Unit) {
        val realChmod = BashrcWriter.chmod
        val realMode = BashrcWriter.modeOf
        BashrcWriter.chmod = { p, m -> chmod(m, File(p)) }
        BashrcWriter.modeOf = { p -> modeOf(File(p)) }
        try {
            body()
        } finally {
            BashrcWriter.chmod = realChmod
            BashrcWriter.modeOf = realMode
        }
    }

    @Test
    fun `a 0550 root is written and its mode is put back`() {
        val rootfs = work.resolve("oracle")
        val home = File(rootfs, "root").apply { mkdirs() }
        chmod(360, home)   // 0550: r-x r-x ---
        withRealSeams {
            // The shape as java.io can express it: no owner-write bit, so a plain
            // writeText cannot create the file. That is the whole bug.
            assertFalse("the fixture is writable, so this proves nothing", home.canWrite())

            val r = BashrcWriter.write(rootfs, "PS1='x'")

            assertTrue("write failed: $r", r is BashrcWriter.Result.Written)
            assertEquals("PS1='x'", File(home, ".bashrc").readText())
            assertFalse("the template was not captured as the previous content", (r as BashrcWriter.Result.Written).previous != null)
        }
        assertFalse(
            "/root was left writable, so the borrow was never returned",
            home.canWrite()
        )
    }

    @Test
    fun `the mode is returned even when the write cannot succeed`() {
        val rootfs = work.resolve("fedora")
        val home = File(rootfs, "root").apply { mkdirs() }
        chmod(360, home)   // 0550: r-x r-x ---
        withRealSeams {
            // A directory that cannot be made writable even for the owner, so the
            // chmod is refused and the write fails -- as it does on a filesystem the app
            // cannot chmod.
            BashrcWriter.chmod = { _, _ -> throw SecurityException("EPERM") }
            val r = BashrcWriter.write(rootfs, "PS1='x'")
            assertTrue("expected a reported failure, got $r", r is BashrcWriter.Result.Failed)
            assertTrue(
                "the failure names neither the directory nor the file: $r",
                (r as BashrcWriter.Result.Failed).reason.contains("/root")
            )
        }
    }

    @Test
    fun `an existing file is replaced and its previous content returned`() {
        val rootfs = work.resolve("arch")
        val home = File(rootfs, "root").apply { mkdirs() }
        chmod(488, home)   // 0750
        File(home, ".bashrc").writeText("ORIGINAL")
        withRealSeams {
            val r = BashrcWriter.write(rootfs, "NEW") as BashrcWriter.Result.Written
            assertEquals("NEW", File(home, ".bashrc").readText())
            assertEquals(
                "reading the original after the write would make restore a no-op",
                "ORIGINAL", r.previous
            )
        }
    }

    @Test
    fun `a missing root is created rather than refused`() {
        val rootfs = work.resolve("void")
        withRealSeams {
            val r = BashrcWriter.write(rootfs, "NEW")
            assertTrue("write failed: $r", r is BashrcWriter.Result.Written)
            assertEquals("NEW", File(rootfs, "root/.bashrc").readText())
        }
    }

    @org.junit.After
    fun cleanup() {
        work.walkBottomUp().forEach {
            it.setReadable(true, false); it.setWritable(true, false); it.setExecutable(true, false)
        }
        work.deleteRecursively()
    }
}