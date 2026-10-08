package com.redtermapp.distro

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Extracts a rootfs archive without relying on the device's `tar` for the hard-link case.
 *
 * **Why this exists.** A rootfs is full of real hard links — Arch's
 * `etc/ca-certificates/extracted/cadir` is ~400 of them — and `tar` records the second and
 * later occurrences as type `1` rather than repeating the content. On restore that becomes
 * `link()`, and on this filesystem it fails:
 *
 * ```
 * tar: can't link 'etc/.../cadir/002c0b4f.0' -> 'GlobalSign_Root_R46.pem': Permission denied
 * ```
 *
 * which is why Arch restored while Void did not: Void simply has no hard links there. And
 * even where `link()` works, proot-distro deliberately does not create real hard links,
 * because the on-disk rootfs uses proot's link2symlink emulation and a host-level hard
 * link would alias inodes the guest treats as separate. So a hard link is **materialised
 * as an independent copy** here, which is both the portable answer and the correct one.
 *
 * The rest is a plain ustar reader. Doing it here rather than shelling out also removes
 * the reliance on how a given `tar` treats `-C` and `--strip-components`, which is the
 * kind of difference that makes a restore succeed on one distro and fail on another.
 */
object TarExtractor {

    private const val BLOCK = 512

    /**
     * Creates a symlink. `android.system.Os.symlink`, which is the only option at API 24,
     * behind a seam so the logic around it — the temporary name, the ordering, the
     * dangling case — can be tested on a JVM, where `Os` is not mocked.
     */
    internal var makeSymlink: (target: String, path: String) -> Unit =
        { target, path -> android.system.Os.symlink(target, path) }

    /**
     * Applies a tar mode to a path.
     *
     * `Os.chmod` rather than `java.io.File`, which is API 1 and cannot set the group bits
     * at all: `setReadable(bit, ownerOnly)` addresses the owner or *others* and there is no
     * group. A rootfs has group permissions that matter, so the three setters silently
     * dropping the middle nine bits is not a rounding error.
     *
     * A seam so the logic can be exercised off-device, where `Os` is not mocked.
     */
    internal var changeMode: (path: String, mode: Int) -> Unit =
        { path, mode -> android.system.Os.chmod(path, mode) }

    private class DirectoryMode(val target: File, val mode: Int, val mtime: Long) {
        val path: String get() = target.absolutePath
    }

    private fun applyMode(target: File, mode: Int) {
        try {
            // Only the permission bits; the file-type nibble is not a chmod argument.
            changeMode(target.absolutePath, mode and 0xFFF)
        } catch (_: Exception) {
            // A path that cannot be chmod-ed is left as it is: the content is what matters,
            // and a failure here must not abandon the rest of the restore.
        }
    }

    data class Result(
        val written: Int,
        val hardLinksCopied: Int,
        val failed: List<String>,
        val totalMembers: Int
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /**
     * Extracts [archive] into [into], dropping [stripComponents] leading path elements.
     *
     * [onProgress] is called with the number of members written so far.
     */
    fun extract(
        archive: File,
        into: File,
        stripComponents: Int = 1,
        onProgress: ((Int) -> Unit)? = null
    ): Result {
        var written = 0
        var hardLinksCopied = 0
        val failed = ArrayList<String>()
        val deferred = ArrayList<DirectoryMode>()
        var total = 0

        // Resolved hard-link targets by archive path, so a link to a link still works.
        val copied = HashMap<String, File>()
        val streams = openArchive(archive)
        val input = BufferedInputStream(streams.first, 1 shl 16)
        var pendingName: String? = null
        var pendingLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null

        try {
            streams.second.use { _ ->
                val header = ByteArray(BLOCK)
                while (true) {
                    if (!readFully(input, header)) break
                    // Two consecutive zero blocks end the archive.
                    if (header.all { it.toInt() == 0 }) break

                    val type = header[156].toInt().toChar()
                    val size = readOctal(header, 124, 12)
                    // Mode and mtime were read by every other tar and dropped here. Both
                    // matter: without the mode an entire restored distro is a tree of
                    // non-executables and `/bin/bash` cannot be run, and without the mtime
                    // a package manager sees every file as freshly created.
                    val mode = readOctal(header, 100, 8).toInt()
                    val mtime = readOctal(header, 136, 12) * 1000L
                    // Read through a local and cleared in the branch that uses it: `?:`
                    // evaluates only one side, so clearing it with `also` on the fallback
                    // never happened — and a long name read once then leaked into every
                    // member after it, which wrote the same path over and over.
                    val carriedName = pendingName
                    pendingName = null
                    val name = carriedName ?: stripPrefix(readString(header, 0, 100), stripComponents)
                    val carriedLink = pendingLink
                    pendingLink = null
                    val link = carriedLink ?: readString(header, 157, 100)
                    total++

                    when (type) {
                        // GNU long name / long link: the payload replaces the next header's.
                        'L', 'K' -> {
                            val payload = readPayload(input, size)
                            if (type == 'L') pendingName = stripPrefix(payload, stripComponents)
                            else pendingLink = payload
                            continue
                        }
                        // pax extended headers: the path lives in the payload.
                        'x', 'g' -> {
                            val payload = readPayload(input, size)
                            paxPath = paxValue(payload, "path")
                            paxLink = paxValue(payload, "linkpath")
                            continue
                        }
                    }

                    val finalName = paxPath ?: name
                    val finalLink = paxLink ?: link
                    paxPath = null
                    paxLink = null

                    // The archive's own top directory strips down to nothing, and that
                    // is not a member: it is the directory already made. Treating it as
                    // an escape put a spurious failure on every single archive.
                    if (finalName.isEmpty() || finalName == "/") {
                        skip(input, size)
                        continue
                    }

                    // A member that resolves outside the destination is refused outright.
                    // A tar is untrusted input and `../../` is the standard way to write
                    // outside the directory being restored into.
                    val target = resolve(into, finalName)
                    if (target == null) {
                        failed += "$finalName (outside the restore directory)"
                        skip(input, size)
                        continue
                    }

                    try {
                        when (type) {
                            '0', '\u0000', '7' -> {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    copy(input, out, size)
                                }
                                // FileOutputStream creates the file 0644 whatever the
                                // archive says, so the mode is applied here or never.
                                applyMode(target, mode)
                                if (mtime > 0) target.setLastModified(mtime)
                                written++
                            }
                            '5' -> {
                                target.mkdirs()
                                // Deferred to the end: a directory recorded as read-only
                                // would otherwise stop the files inside it being written,
                                // which is exactly why tar applies directory modes last.
                                deferred.add(DirectoryMode(target, mode, mtime))
                                written++
                            }
                            '2' -> {
                                target.parentFile?.mkdirs()
                                // Written to a temporary name and moved, because a symlink
                                // whose target does not exist cannot be created under a name
                                // that already exists as a dangling link.
                                val tmp = File(target.parentFile, ".link-${target.name}")
                                tmp.delete()
                                makeSymlink(finalLink, tmp.absolutePath)
                                tmp.renameTo(target)
                                // A symlink's own permission bits are meaningless on
                                // Linux, and chmod would follow the link and change the
                                // target, so only the time is set. java.io only:
                                // java.nio.file.Files.setLastModifiedTime is API 26, and
                                // a `catch (Exception)` around it would not even catch the
                                // NoSuchMethodError it throws on API 24.
                                if (mtime > 0) {
                                    try {
                                        target.setLastModified(mtime)
                                    } catch (_: Exception) {
                                    }
                                }
                                written++
                            }
                            '1' -> {
                                // The target is stored relative to the archive root, like
                                // the name, so it needs the same strip — otherwise the
                                // lookup is against a key that does not exist and every
                                // link falls through to "target missing".
                                val strippedTarget = stripPrefix(finalLink, stripComponents)
                                val source = copied[strippedTarget]
                                    ?: resolve(into, strippedTarget)
                                if (source != null && source.isFile) {
                                    // A copy, not link(). See the class comment.
                                    target.parentFile?.mkdirs()
                                    source.copyTo(target, overwrite = true)
                                    copied[finalName] = target
                                    hardLinksCopied++
                                    written++
                                } else {
                                    failed += "$finalName (hard link target ${finalLink} missing)"
                                }
                                skip(input, size)
                            }
                            'S' -> {
                                // Legacy sparse: the payload holds real data with holes
                                // described in the header. Writing it as a dense file is
                                // correct, just larger.
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> copy(input, out, size) }
                                written++
                            }
                            '3', '4', '6' -> {
                                // Device nodes and fifos cannot be created without root and
                                // are runtime artifacts, exactly as on the way in.
                                skip(input, size)
                            }
                            else -> skip(input, size)
                        }
                    } catch (e: Exception) {
                        skip(input, size)
                        failed += "$finalName: ${e.message ?: e.javaClass.simpleName}"
                    }
                    if (onProgress != null && (written and 0x3F) == 0) onProgress(written)
                }
            }
        } catch (e: IOException) {
            failed += "archive: ${e.message ?: "could not be read"}"
        }
        // Deepest first, so tightening a parent cannot stop its children being chmod-ed.
        for (dir in deferred.sortedByDescending { it.path.length }) {
            applyMode(dir.target, dir.mode)
            if (dir.mtime > 0) dir.target.setLastModified(dir.mtime)
        }
        onProgress?.invoke(written)
        return Result(written, hardLinksCopied, failed, total)
    }

    private fun openArchive(archive: File): Pair<java.io.InputStream, java.io.Closeable> {
        val raw = BufferedInputStream(archive.inputStream(), 1 shl 16)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) {
            val gz = GZIPInputStream(raw, 1 shl 16)
            gz to gz
        } else {
            raw to raw
        }
    }

    /**
     * Resolves an archive member to a path inside [into], or null when it escapes.
     *
     * `File(..).canonicalFile` is what makes `..` and an absolute member name resolve
     * against the destination rather than being trusted, so the check is on the resolved
     * path and not on the string.
     */
    internal fun resolve(into: File, member: String): File? {
        if (member.isEmpty() || member.contains('\u0000')) return null
        val root = into.canonicalFile
        val candidate = File(root, member).canonicalFile
        return if (candidate == root || candidate.path.startsWith(root.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    /** Drops [count] leading components, which is how `--strip-components=1` works. */
    internal fun stripPrefix(member: String, count: Int): String {
        if (count <= 0) return member
        var rest = member
        var dropped = 0
        while (dropped < count) {
            val slash = rest.indexOf('/')
            if (slash < 0) return ""
            rest = rest.substring(slash + 1)
            dropped++
        }
        return rest
    }

    private fun paxValue(payload: String, key: String): String? {
        var i = 0
        while (i < payload.length) {
            val space = payload.indexOf(' ', i)
            if (space < 0) break
            val length = payload.substring(i, space).toIntOrNull() ?: break
            if (length <= 0 || i + length > payload.length) break
            val record = payload.substring(space + 1, i + length).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == key) return record.substring(eq + 1)
            i += length
        }
        return null
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        // Base-256 for values too large for octal, which is how a >8 GB file records its size.
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) {
                value = (value shl 8) or (header[i].toInt() and 0xFF).toLong()
            }
            return value
        }
        var value = 0L
        for (i in offset until offset + length) {
            val c = header[i].toInt() - '0'.code
            if (c in 0..7) value = value * 8 + c
        }
        return value
    }

    private fun readPayload(input: java.io.InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        // Only the padding: the payload has already been read. Skipping the whole member
        // as well lands past the next header, which is why a long path produced an empty
        // file and every member after it read as garbage.
        skipPadding(input, size)
        return String(bytes, 0, read, Charsets.UTF_8).trimEnd('\u0000')
    }

    /**
     * Skips a whole member: its payload plus the padding to the next 512-byte record.
     *
     * For a member whose payload has *already* been read, this is wrong — use
     * [skipPadding] instead.
     */
    private fun skip(input: java.io.InputStream, size: Long) {
        var left = size + padding(size)
        drain(input, left)
    }

    /**
     * Skips only the padding after a payload that has been read.
     *
     * Every tar record is padded out to 512 bytes. Reading a member's bytes and then
     * skipping `size` more desynchronises the whole stream — every member after the
     * first regular file is read as garbage, which looks like files simply not arriving.
     */
    private fun skipPadding(input: java.io.InputStream, size: Long) =
        drain(input, padding(size))

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun drain(input: java.io.InputStream, bytes: Long) {
        var left = bytes
        val scratch = ByteArray(1 shl 16)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        skipPadding(input, size)
    }

    private fun readFully(input: java.io.InputStream, header: ByteArray): Boolean {
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}