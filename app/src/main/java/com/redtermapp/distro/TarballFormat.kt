package com.redtermapp.distro

/**
 * The three ways a distro tarball can be compressed, told apart by the first
 * bytes of the file rather than its name.
 *
 * A local tarball's extension is whatever its maker chose — or nothing — so the
 * only reliable answer to "how do I open this?" is the magic number: xz starts
 * with `0xFD 37 7A 58 5A 00`, gzip with `0x1F 0x8B`, and a plain tar with the
 * 512-byte header and neither. Everything else is not a tarball we can open.
 *
 * Pure, so the detection is unit-tested with real magic bytes: a tarball that
 * is really gzip in a `.tar.xz` file must be opened as gzip or every entry
 * reads as garbage.
 */
object TarballFormat {

    enum class Compression { XZ, GZIP, PLAIN, UNKNOWN }

    /** Inspect the first bytes of the file; needs at most six. */
    fun detect(magic: ByteArray): Compression = when {
        // xz: FD 37 7A 58 5A 00
        magic.size >= 6 &&
            (magic[0].toInt() and 0xFF) == 0xFD && (magic[1].toInt() and 0xFF) == 0x37 &&
            (magic[2].toInt() and 0xFF) == 0x7A && (magic[3].toInt() and 0xFF) == 0x58 &&
            (magic[4].toInt() and 0xFF) == 0x5A && (magic[5].toInt() and 0xFF) == 0x00 -> Compression.XZ

        // gzip: 1F 8B
        magic.size >= 2 &&
            (magic[0].toInt() and 0xFF) == 0x1F && (magic[1].toInt() and 0xFF) == 0x8B -> Compression.GZIP

        // A tar header is 512 bytes; the very first byte of a real archive is
        // part of a name and never 0, but we cannot tell tar from random data
        // by magic alone. Anything not xz or gzip is treated as plain tar and
        // let the tar reader fail loudly on a file that is not one.
        magic.isNotEmpty() -> Compression.PLAIN

        else -> Compression.UNKNOWN
    }

    /** Read the magic bytes of [file] without loading it. */
    fun readMagic(file: java.io.File, n: Int = 6): ByteArray {
        if (!file.isFile) return ByteArray(0)
        return java.io.FileInputStream(file).use { input ->
            val buf = ByteArray(n)
            val got = input.read(buf)
            if (got <= 0) ByteArray(0) else buf.copyOf(got)
        }
    }
}
