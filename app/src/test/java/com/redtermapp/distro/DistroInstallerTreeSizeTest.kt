package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The distro card's size is [DistroInstaller.treeSize]: it sums a tree's real regular
 * files without following links, which is what stops a distro from reporting 3.26 GB
 * when it actually occupies 590 MB.
 *
 * The link-skipping itself runs through `Os.lstat`, which is stubbed off the device, so
 * a unit test cannot forge a real symlink; this locks the summation that sits under it.
 * The link handling is the verbatim algorithm the "Disk" screen already used to produce
 * the 590 MB the user trusts, now shared by both screens so they cannot disagree.
 */
class DistroInstallerTreeSizeTest {

    private fun tree(): File =
        File(System.getProperty("java.io.tmpdir"), "rt-treesize-${System.nanoTime()}")
            .apply { deleteRecursively(); mkdirs() }

    @Test
    fun `an empty tree measures zero`() {
        assertEquals(0L, DistroInstaller.treeSize(tree()))
    }

    @Test
    fun `it sums every regular file, nested directories included`() {
        val root = tree()
        File(root, "a").writeText("12345")                 // 5 bytes
        File(root, "b").writeText("12345678")              // 8
        File(root, "sub").mkdirs()
        File(File(root, "sub"), "c").writeText("1234567")  // 7
        assertEquals(20L, DistroInstaller.treeSize(root))
    }

    @Test
    fun `a binary file is counted by its real byte length`() {
        val root = tree()
        File(root, "big").outputStream().use { it.write(ByteArray(1024)) }
        assertEquals(1024L, DistroInstaller.treeSize(root))
    }
}
