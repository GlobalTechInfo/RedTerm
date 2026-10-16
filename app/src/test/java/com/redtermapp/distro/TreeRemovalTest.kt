package com.redtermapp.distro

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rootfs is full of directories an archive deliberately records read-only, and
 * `deleteRecursively()` cannot unlink anything inside one of those.
 *
 * Arch's /etc/ca-certificates/extracted/cadir is mode 0555, so the first install writes
 * a tree the second install cannot clear -- and the return value was ignored, so
 * extraction continued into the old tree and reported:
 *
 *     .../cadir/ACCVRAIZ1.pem: open failed: EACCES (Permission denied)
 */
class TreeRemovalTest {

    @Test
    fun `a read-only directory is removed rather than half-cleared`() {
        val work = File(System.getProperty("java.io.tmpdir"), "rt-rm-${System.nanoTime()}")
        try {
            // The shape the archive records: a directory with no write bit, holding a
            // file with no write bit, which is what FileOutputStream would refuse.
            // `android.system.Os` is a stub on the JVM classpath, so the real seam is
            // pointed at a real chmod for the duration of the test.
            val realChmod = TreeRemoval.chmod
            TreeRemoval.chmod = { p, m -> chmod(File(p), m) }
            try {
            val cadir = File(work, "etc/ca-certificates/extracted/cadir")
            cadir.mkdirs()
            val pem = File(cadir, "ACCVRAIZ1.pem").apply { writeText("CERT") }
            chmod(cadir, 365)   // 0555
            chmod(pem, 292)     // 0444

            // What java.io does with it, which is the bug:
            work.deleteRecursively()
            assertTrue(
                "the read-only tree survived deleteRecursively(), so this no longer reproduces",
                File(work, "etc").exists()
            )

            // And what the install path now does instead:
            TreeRemoval.remove(work)
            assertTrue("removeTree left the tree behind", !work.exists())
            } finally { TreeRemoval.chmod = realChmod }
        } finally {
            chmodTree(work)
            work.deleteRecursively()
        }
    }

    @Test
    fun `removeTree reports failure rather than pretending`() {
        val work = File(System.getProperty("java.io.tmpdir"), "rt-rm-${System.nanoTime()}")
        work.mkdirs()
        try {
            assertTrue("removeTree claimed failure for a tree that is not there",
                TreeRemoval.remove(File(work, "absent")))
        } finally {
            work.deleteRecursively()
        }
    }

    // owner read 0o400, write 0o200, execute 0o100. Getting this wrong is how the first
    // version of this test passed while testing nothing: the directory was made
    // *writable* and deleteRecursively() removed it for that reason.
    private fun chmod(f: File, mode: Int) {
        f.setReadable(mode and 0x100 != 0, false)
        f.setWritable(mode and 0x80 != 0, false)
        f.setExecutable(mode and 0x40 != 0, false)
    }

    private fun chmodTree(f: File) {
        f.walkBottomUp().forEach { runCatching { it.setWritable(true, false); it.setExecutable(true, false) } }
    }
}
