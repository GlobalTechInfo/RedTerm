package com.redtermapp.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifWriterTest {

    // ------------------------------------------------------------------
    // Golden LZW vectors, byte-for-byte, from an independent reference
    // encoder. These pin the container + bit-packing + code-size rules:
    // a regression in any of them changes these bytes.
    // ------------------------------------------------------------------

    @Test
    fun `lzw of an empty frame is clear plus end`() {
        assertArrayEquals(
            byteArrayOf(0, 3, 2),
            GifWriter.Lzw.encode(8, IntArray(0))
        )
    }

    @Test
    fun `lzw of a single pixel`() {
        assertArrayEquals(
            byteArrayOf(0, 7, 4, 4),
            GifWriter.Lzw.encode(8, intArrayOf(3))
        )
    }

    @Test
    fun `lzw of a few distinct pixels`() {
        assertArrayEquals(
            byteArrayOf(0, 1, 4, 16, 16, 16),
            GifWriter.Lzw.encode(8, intArrayOf(0, 1, 2))
        )
    }

    @Test
    fun `lzw of a repeated run grows the table`() {
        assertArrayEquals(
            byteArrayOf(0, 11, 8, 20, 24, 16),
            GifWriter.Lzw.encode(8, intArrayOf(5, 5, 5, 5, 5))
        )
    }

    // ------------------------------------------------------------------
    // Round trip through the standard GIF decoder, which (per the spec)
    // grows its code size one code earlier than the encoder.
    // ------------------------------------------------------------------

    @Test
    fun `lzw round trips a uniform stream`() {
        val codes = IntArray(10_000) { 5 }
        assertArrayEquals(codes, decode(8, GifWriter.Lzw.encode(8, codes)))
    }

    @Test
    fun `lzw round trips a high-entropy stream`() {
        var seed = 12345L
        val codes = IntArray(20_000) {
            seed = seed * 1103515245 + 12345
            (Math.floorMod(seed, 256L)).toInt()
        }
        assertArrayEquals(codes, decode(8, GifWriter.Lzw.encode(8, codes)))
    }

    @Test
    fun `lzw round trips a stream that forces growth and a clear`() {
        // Enough distinct pairs to cross the 9->10->11->12 boundaries and
        // fill the table to the point of a mid-stream clear.
        val codes = IntArray(60_000) { (it * 7919) % 256 }
        assertArrayEquals(codes, decode(8, GifWriter.Lzw.encode(8, codes)))
    }

    /**
     * The standard GIF LZW decoder.
     *
     * It reads codes little-endian and, per the GIF spec, increments its
     * code size one code earlier than the encoder does — the decoder's
     * table is built one step behind, so its width grows a step earlier.
     * Getting this wrong garbles every frame after the first code-size
     * boundary, which is exactly the failure mode the tests above guard.
     */
    private fun decode(minCodeSize: Int, packed: ByteArray): IntArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        var nextCodeNo = end + 1

        var bitBuf = 0
        var bitCnt = 0
        var pos = 0
        fun nextCode(): Int {
            while (bitCnt < codeSize) {
                if (pos >= packed.size) return end
                bitBuf = bitBuf or ((packed[pos].toInt() and 0xFF) shl bitCnt)
                pos++
                bitCnt += 8
            }
            val c = bitBuf and ((1 shl codeSize) - 1)
            bitBuf = bitBuf shr codeSize
            bitCnt -= codeSize
            return c
        }

        // Dictionary indexed by code: dict[code] is that code's string.
        // A fixed array (not a growing list) so the index always equals the
        // code value — codes 256/257 are reserved and never table entries.
        val dict = Array<IntArray?>(4096) { null }
        for (c in 0 until clear) dict[c] = intArrayOf(c)
        val out = ArrayList<Int>()
        var prev: IntArray? = null

        fun concat(a: IntArray, b: IntArray): IntArray {
            val r = a.copyOf(a.size + b.size)
            System.arraycopy(b, 0, r, a.size, b.size)
            return r
        }

        while (true) {
            val code = nextCode()
            when {
                code == clear -> {
                    nextCodeNo = end + 1
                    codeSize = minCodeSize + 1
                    for (c in 0 until clear) dict[c] = intArrayOf(c)
                    val first = nextCode()
                    if (first == end) break
                    prev = intArrayOf(first)
                    out.add(first)
                }
                code == end -> break
                else -> {
                    val p = prev ?: intArrayOf()
                    val entry = dict[code] ?: concat(p, intArrayOf(p.first()))
                    for (e in entry) out.add(e)
                    if (nextCodeNo < 4096 && prev != null) {
                        dict[nextCodeNo] = concat(prev, intArrayOf(entry.first()))
                        nextCodeNo++
                        // The decoder widens one code earlier than the encoder.
                        if (nextCodeNo >= (1 shl codeSize) - 1 && codeSize < 12) codeSize++
                    }
                    prev = entry
                }
            }
        }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------
    // Palette and quantisation.
    // ------------------------------------------------------------------

    @Test
    fun `palette puts background and foreground first, ansi colours next`() {
        val table = GifWriter.palette(0x112233, 0xABCDEF, GifWriter.ANSI16)
        assertEquals(0x112233, table[0])
        assertEquals(0xABCDEF, table[1])
        for (i in 0 until 16) assertEquals(GifWriter.ANSI16[i], table[2 + i])
        assertEquals(0x112233, table[18])
        assertEquals(0x112233, table[255])
        assertEquals(256, table.size)
    }

    @Test
    fun `nearest index picks the closest entry`() {
        val table = GifWriter.palette(0x000000, 0xFFFFFF, GifWriter.ANSI16)
        assertEquals(0, GifWriter.nearestIndex(0x000000, table))
        assertEquals(1, GifWriter.nearestIndex(0xFFFFFF, table))
        // a dark red is the red at table slot 3 (ANSI16's red is index 1)
        assertEquals(3, GifWriter.nearestIndex(0xC00000, table))
        // an off-white is the plain white; index 1 comes first of the two
        // equidistant whites, and nearest keeps the first minimum
        assertEquals(1, GifWriter.nearestIndex(0xF8F8F8, table))
    }

    // ------------------------------------------------------------------
    // Container shape: a header and trailer any viewer can parse.
    // ------------------------------------------------------------------

    @Test
    fun `a written file begins as gif89a and ends with the trailer`() {
        val out = ByteArrayOutputStream()
        val g = GifWriter(out)
        g.start(4, 1, GifWriter.palette(0, 0xFF, GifWriter.ANSI16))
        g.frame(IntArray(4) { it }, 20)
        g.finish()
        val bytes = out.toByteArray()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes[bytes.size - 1].toInt())
        // the Netscape looping extension is in there, after the global table
        val marker = "Netscape".toByteArray(Charsets.US_ASCII)
        val at = (0 until bytes.size - marker.size).first {
            bytes.copyOfRange(it, it + marker.size).contentEquals(marker)
        }
        assertEquals(0x21, bytes[at - 2].toInt())
        assertEquals(0xFF, bytes[at - 1].toInt() and 0xFF)
    }

    @Test
    fun `frames must match the declared size`() {
        val out = ByteArrayOutputStream()
        val g = GifWriter(out)
        g.start(2, 2, GifWriter.palette(0, 0xFF, GifWriter.ANSI16))
        try {
            g.frame(intArrayOf(0, 0, 0), 10)
            throw AssertionError("an undersized frame must be refused")
        } catch (e: IllegalArgumentException) {
            // require() refuses it
        }
    }
}
