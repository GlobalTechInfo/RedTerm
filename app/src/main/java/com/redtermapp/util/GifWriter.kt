package com.redtermapp.util

import java.io.OutputStream

/**
 * A GIF89a writer with a 256-colour global palette and LZW frame compression.
 *
 * Only what a terminal recording needs: one global colour table, a looping
 * image list, and frames whose pixels are already palette indices — the
 * quantising from terminal colours into those indices is the caller's
 * problem, and it is pure and testable here in [palette] and [nearestIndex].
 *
 * The encoder is byte-exact to the GIF89a spec so the output is a file
 * any viewer can open; the LZW step is round-tripped by a decoder in the
 * unit tests, because a compression bug does not produce an error, it
 * produces a garbled image nobody can name.
 */
class GifWriter(private val out: OutputStream) {

    /** One frame: [pixels] are indices into the global palette, [delayCs] in centiseconds. */
    data class Frame(val pixels: IntArray, val delayCs: Int)

    private var width = 0
    private var height = 0
    private var palette: IntArray = IntArray(256)

    fun start(width: Int, height: Int, palette: IntArray) {
        require(palette.size == 256) { "a GIF global colour table has exactly 256 entries" }
        this.width = width
        this.height = height
        this.palette = palette
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor: size, global table (256 = size field 7),
        // 7-bit colour resolution, background index 0, no aspect ratio.
        writeLE16(width)
        writeLE16(height)
        out.write(0xF7)
        out.write(0)
        out.write(0)
        for (i in 0 until 256) {
            out.write(palette[i] and 0xFF)
            out.write((palette[i] shr 8) and 0xFF)
            out.write((palette[i] shr 16) and 0xFF)
        }
        // Netscape looping extension: loop forever.
        out.write(0x21)
        out.write(0xFF)
        out.write("Netscape".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x32, 0x2E, 0x30, 0x03, 0x01, 0x00, 0x00, 0x00))
    }

    fun frame(pixels: IntArray, delayCs: Int) {
        require(pixels.size == width * height) { "a frame holds width*height pixels" }
        // Graphic control: no disposal, delay in centiseconds, no transparent index.
        out.write(0x21)
        out.write(0xF9)
        out.write(0x00)
        out.write(delayCs and 0xFF)
        out.write((delayCs shr 8) and 0xFF)
        out.write(0)
        // Image descriptor at the top-left corner, no local colour table.
        out.write(0x2C)
        writeLE16(0)
        writeLE16(0)
        writeLE16(width)
        writeLE16(height)
        out.write(0)
        // LZW minimum code size: the global table holds 256 colours.
        out.write(8)
        val compressed = Lzw.encode(8, pixels)
        // Sub-blocks of at most 255 bytes, then the zero block.
        var i = 0
        while (i < compressed.size) {
            val n = minOf(255, compressed.size - i)
            out.write(n)
            out.write(compressed, i, n)
            i += n
        }
        out.write(0)
    }

    private fun writeLE16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    companion object {
        /** The sixteen basic colours, xterm's values: the hues a terminal means. */
        val ANSI16 = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
            0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
            0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
        )

        /**
         * The 256-entry table a terminal recording quantises into: background,
         * foreground, the sixteen basic colours, and background again for the
         * rest — a terminal frame is almost entirely one of those few colours,
         * so the spare indices are dead weight.
         *
         * [fg]/[bg] are 0xRRGGBB; [ansi16] is exactly sixteen 0xRRGGBB values.
         */
        fun palette(bg: Int, fg: Int, ansi16: IntArray): IntArray {
            val table = IntArray(256) { bg }
            table[0] = bg
            table[1] = fg
            ansi16.forEachIndexed { i, c -> table[2 + i] = c }
            return table
        }

        /**
         * The nearest palette entry to an 0xRRGGBB colour.
         *
         * Nearest in squared RGB distance; 256 candidates is cheap enough to
         * do per pixel, and it keeps the exported GIF in the recording's own
         * colours instead of a dithered caricature of them.
         */
        fun nearestIndex(rgb: Int, table: IntArray): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in table.indices) {
                val dr = r - (table[i] shr 16 and 0xFF)
                val dg = g - (table[i] shr 8 and 0xFF)
                val db = b - (table[i] and 0xFF)
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            return best
        }
    }

    /**
     * GIF-style LZW, little-endian bit packing.
     *
     * [minCodeSize] is the code size the colour table dictates (eight for
     * 256 colours); the clear and end codes sit just above it, and the code
     * size grows one bit at a time up to twelve.
     *
     * The string table is open-addressed on (parent, first character), so a
     * lookup is constant time — the naive scan over the table would make an
     * export take minutes on a phone. The allocation order (and therefore
     * the codes) never depends on the hash, so a decoder that walks the
     * codes in order reconstructs the same strings.
     */
    object Lzw {

        private const val HASH_SIZE = 8192

        fun encode(minCodeSize: Int, codes: IntArray): ByteArray {
            val clear = 1 shl minCodeSize
            val end = clear + 1
            var codeSize = minCodeSize + 1
            var next = end + 1
            val firstFree = next

            // The string table is open-addressed on (parent, first character);
            // the slot a code occupies is the code itself, so a decoder that
            // allocates in the same order rebuilds the same strings. Slots
            // clear and end are never strings.
            val hKey = IntArray(HASH_SIZE) { -1 }
            val hCode = IntArray(HASH_SIZE)
            val hUsed = IntArray(4096) // the hash slots a live entry occupies
            var hUsedCount = 0

            fun hashSlot(parent: Int, c: Int): Int =
                (((parent.toLong() * 2654435761L) xor (c.toLong() * 40503L)) and 8191L).toInt()

            fun lookup(parent: Int, c: Int): Int {
                var h = hashSlot(parent, c)
                while (true) {
                    val k = hKey[h]
                    if (k == -1) return -1
                    val code = hCode[h]
                    if (k == parent * 256 + c && code in firstFree..4095) return code
                    h = (h + 1) and (HASH_SIZE - 1)
                }
            }

            fun insert(parent: Int, c: Int, slot: Int) {
                val key = parent * 256 + c
                var h = hashSlot(parent, c)
                while (true) {
                    val k = hKey[h]
                    if (k == -1) {
                        hKey[h] = key
                        hCode[h] = slot
                        hUsed[hUsedCount++] = h
                        return
                    }
                    if (k == key && hCode[h] in firstFree..4095) {
                        // The entry already exists under this key.
                        return
                    }
                    h = (h + 1) and (HASH_SIZE - 1)
                }
            }

            fun clearTable() {
                for (i in 0 until hUsedCount) {
                    val h = hUsed[i]
                    if (h >= 0) hKey[h] = -1
                }
                hUsedCount = 0
            }

            var acc = 0
            var bits = 0
            val out = java.io.ByteArrayOutputStream()

            fun emit(code: Int) {
                acc = acc or (code shl bits)
                bits += codeSize
                while (bits >= 8) {
                    // The low byte goes out first: GIF packs each code's
                    // least-significant bits into the least-significant bits
                    // of the stream, and the reader relies on the same order.
                    out.write(acc and 0xFF)
                    acc = acc ushr 8
                    bits -= 8
                }
            }

            emit(clear)
            var prev = -1
            for (c in codes) {
                if (prev < 0) {
                    prev = c
                    continue
                }
                val found = lookup(prev, c)
                if (found >= 0) {
                    prev = found
                } else {
                    emit(prev)
                    if (next < 4096) {
                        insert(prev, c, next)
                        next++
                        if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
                    }
                    if (next >= 4096) {
                        emit(clear)
                        clearTable()
                        next = firstFree
                        codeSize = minCodeSize + 1
                    }
                    prev = c
                }
            }
            if (prev >= 0) emit(prev)
            emit(end)
            if (bits > 0) out.write(acc and ((1 shl bits) - 1))
            return out.toByteArray()
        }
    }
}
