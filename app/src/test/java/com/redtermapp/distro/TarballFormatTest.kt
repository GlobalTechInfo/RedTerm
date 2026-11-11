package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Test

class TarballFormatTest {

    // xz magic: FD 37 7A 58 5A 00
    private val xz = byteArrayOf(0xFD.toByte(), 0x37, 0x7A.toByte(), 0x58, 0x5A, 0)

    @Test
    fun `xz magic is recognised`() {
        assertEquals(TarballFormat.Compression.XZ, TarballFormat.detect(xz))
    }

    @Test
    fun `gzip magic is recognised`() {
        // gzip: 1F 8B
        assertEquals(TarballFormat.Compression.GZIP, TarballFormat.detect(byteArrayOf(0x1F, 0x8B.toByte(), 8)))
    }

    @Test
    fun `anything else is plain tar, not an error`() {
        // a tar header begins with the entry name; it has no fixed magic,
        // so "not xz and not gzip" falls through to plain and lets the
        // tar reader fail loudly on a file that is not one.
        assertEquals(TarballFormat.Compression.PLAIN, TarballFormat.detect(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an empty read is unknown, not a format`() {
        assertEquals(TarballFormat.Compression.UNKNOWN, TarballFormat.detect(ByteArray(0)))
    }

    @Test
    fun `the xz check needs all six magic bytes`() {
        // the first five xz bytes but a wrong sixth is not xz
        val near = byteArrayOf(0xFD.toByte(), 0x37, 0x7A.toByte(), 0x58, 0x5A, 1)
        assertEquals(TarballFormat.Compression.PLAIN, TarballFormat.detect(near))
    }
}
