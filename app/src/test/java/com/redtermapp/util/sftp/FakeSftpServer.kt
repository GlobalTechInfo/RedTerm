package com.redtermapp.util.sftp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-process SFTP server, for testing the client.
 *
 * The point is to exercise the real encoding on both sides: the client writes
 * bytes and parses whatever this produces, so a wrong length prefix, a wrong
 * field order or a missing flag shows up as a failing test rather than as a
 * transfer that silently stops halfway on a phone.
 *
 * Backed by an in-memory filesystem so a test can assert on what actually landed.
 */
class FakeSftpServer(
    private val version: Int = Sftp.VERSION,
    /** Set to refuse the INIT/VERSION exchange, the way a bad key does. */
    val refuseHandshake: Boolean = false
) {

    private val files = LinkedHashMap<String, ByteArray>()

    /** Names treated as directories. */
    private val directories = LinkedHashSet<String>()

    /** Set to make the next operation fail with this status. */
    var failWith: Pair<Int, String>? = null

    /** Every request type the server saw, in order. */
    val requests = mutableListOf<Int>()

    /** Permissions set by SETSTAT, keyed by path. */
    val permissions = LinkedHashMap<String, Long>()

    /** Symlink targets, keyed by the link's own path. */
    val links = LinkedHashMap<String, String>()

    /** Off to answer statvfs with "unsupported", the way a server without it does. */
    var statvfsSupported = true
    var blockSize = 4096L
    var blockCount = 1024L
    var freeBlocks = 256L

    fun addFile(path: String, content: ByteArray): FakeSftpServer = apply {
        files[normalise(path)] = content
    }

    fun addFile(path: String, content: String): FakeSftpServer =
        addFile(path, content.toByteArray(Charsets.UTF_8))

    fun addDirectory(path: String): FakeSftpServer = apply {
        directories.add(normalise(path))
    }

    fun contentOf(path: String): ByteArray? = files[normalise(path)]

    /** Serves [client] on background threads and returns once it is closed. */
    fun serve(clientInput: InputStream, clientOutput: OutputStream) {
        val input = clientInput
        val output = clientOutput
        Thread({
            try {
                while (true) {
                    val length = readIntOrNull(input) ?: break
                    if (length < 1 || length > 1 shl 24) break
                    val payload = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(payload, read, length - read)
                        if (n <= 0) return@Thread
                        read += n
                    }
                    val reader = SftpReader(payload)
                    val type = reader.byte()
                    val id = reader.uint32().toInt()
                    handle(type, id, reader, output)
                    output.flush()
                }
            } catch (_: Exception) {
                // The client hung up.
            }
        }, "fake-sftp-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(type: Int, id: Int, reader: SftpReader, out: OutputStream) {
        requests.add(type)
        if (type == Sftp.FXP_INIT) {
            // A refused connection closes the stream rather than going quiet,
            // which is what ssh actually does; a silent server would instead
            // leave the client waiting until its deadline.
            if (refuseHandshake) {
                runCatching { out.close() }
                return
            }
            reply(out, SftpWriter().apply {
                byte(Sftp.FXP_VERSION)
                uint32(id.toLong())
                uint32(version.toLong())
                string("posix-rename@openssh.com")
                string("1")
            })
            return
        }
        failWith?.let { (code, message) ->
            failWith = null
            status(out, id, code, message)
            return
        }
        when (type) {
            Sftp.FXP_REALPATH -> {
                val requested = reader.string()
                val resolved = if (requested == "." || requested == "~") "/home/tester" else requested
                name(out, id, listOf(SftpName(resolved, "realpath", dirAttrs())))
            }
            Sftp.FXP_STAT, Sftp.FXP_LSTAT -> {
                val path = normalise(reader.string())
                val attrs = when {
                    files.containsKey(path) -> fileAttrs(files[path]!!.size)
                    directories.contains(path) -> dirAttrs()
                    else -> null
                }
                if (attrs == null) status(out, id, Sftp.NO_SUCH_FILE, "No such file")
                else reply(out, SftpWriter().apply {
                    byte(Sftp.FXP_ATTRS)
                    uint32(id.toLong())
                    attrs(attrs)
                })
            }
            Sftp.FXP_OPENDIR -> {
                val path = normalise(reader.string())
                if (!directories.contains(path)) {
                    status(out, id, Sftp.NO_SUCH_FILE, "No such directory")
                    return
                }
                reply(out, SftpWriter().apply {
                    byte(Sftp.FXP_HANDLE)
                    uint32(id.toLong())
                    string("dir-handle")
                })
            }
            Sftp.FXP_READDIR -> {
                if (reader.remaining == 0) {
                    status(out, id, Sftp.EOF, "End of directory")
                    return
                }
                val handle = reader.string()
                if (handle != "dir-handle") {
                    status(out, id, Sftp.FAILURE, "bad handle")
                    return
                }
                val entries = pendingEntries
                if (entries == null) {
                    status(out, id, Sftp.EOF, "End of directory")
                    return
                }
                name(out, id, entries)
                pendingEntries = null
            }
            Sftp.FXP_OPEN -> {
                val path = normalise(reader.string())
                val flags = reader.uint32().toInt()
                reader.attrs()
                val writing = flags and Sftp.FXF_WRITE != 0
                val creating = flags and Sftp.FXF_CREAT != 0
                if (writing && !creating && !files.containsKey(path)) {
                    status(out, id, Sftp.NO_SUCH_FILE, "No such file")
                    return
                }
                if (writing && creating && !files.containsKey(path)) files[path] = ByteArray(0)
                if (flags and Sftp.FXF_TRUNC != 0 && writing) files[path] = ByteArray(0)
                val handleName = "file-${handleCounter++}"
                openHandles[handleName] = path
                reply(out, SftpWriter().apply {
                    byte(Sftp.FXP_HANDLE)
                    uint32(id.toLong())
                    string(handleName)
                })
            }
            Sftp.FXP_READ -> {
                val handle = reader.string()
                val offset = reader.uint64()
                val length = reader.uint32().toInt()
                val path = openHandles[handle] ?: return status(out, id, Sftp.FAILURE, "bad handle")
                val data = files[path] ?: ByteArray(0)
                if (offset >= data.size) {
                    status(out, id, Sftp.EOF, "End of file")
                    return
                }
                val end = minOf((offset + length).toInt(), data.size)
                val slice = data.copyOfRange(offset.toInt(), end)
                reply(out, SftpWriter().apply {
                    byte(Sftp.FXP_DATA)
                    uint32(id.toLong())
                    uint32(slice.size.toLong())
                    raw(slice)
                })
            }
            Sftp.FXP_WRITE -> {
                val handle = reader.string()
                val offset = reader.uint64()
                val data = reader.byteString()
                val path = openHandles[handle] ?: return status(out, id, Sftp.FAILURE, "bad handle")
                val existing = files[path] ?: ByteArray(0)
                val at = offset.toInt()
                val needed = at + data.size
                val target = if (needed > existing.size) existing.copyOf(needed) else existing
                data.copyInto(target, at)
                files[path] = target
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_CLOSE -> {
                val handle = reader.string()
                openHandles.remove(handle)
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_MKDIR -> {
                val path = normalise(reader.string())
                reader.attrs()
                directories.add(path)
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_RMDIR -> {
                directories.remove(normalise(reader.string()))
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_REMOVE -> {
                val path = normalise(reader.string())
                if (files.remove(path) == null) {
                    status(out, id, Sftp.NO_SUCH_FILE, "No such file")
                } else {
                    status(out, id, Sftp.OK, "OK")
                }
            }
            Sftp.FXP_RENAME -> {
                val from = normalise(reader.string())
                val to = normalise(reader.string())
                val moved = files.remove(from)
                if (moved == null) {
                    status(out, id, Sftp.NO_SUCH_FILE, "No such file")
                } else {
                    files[to] = moved
                    status(out, id, Sftp.OK, "OK")
                }
            }
            Sftp.FXP_SETSTAT -> {
                val path = normalise(reader.string())
                val attrs = reader.attrs()
                val mode = attrs.permissions
                if (mode != null) permissions[path] = mode
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_SYMLINK -> {
                val linkPath = normalise(reader.string())
                val target = reader.string()
                links[linkPath] = target
                status(out, id, Sftp.OK, "OK")
            }
            Sftp.FXP_EXTENDED -> {
                when (reader.string()) {
                    Sftp.EXT_STATVFS -> {
                        reader.string()
                        if (statvfsSupported) {
                            // Block counts, not bytes: the client multiplies by the
                            // block size it is given, so sending bytes here would
                            // square them.
                            data(
                                out, id,
                                blockSize, blockCount, freeBlocks, freeBlocks
                            )
                        } else {
                            status(out, id, Sftp.OP_UNSUPPORTED, "statvfs is off in this fake")
                        }
                    }
                    else -> status(out, id, Sftp.OP_UNSUPPORTED, "Unsupported extension")
                }
            }
            else -> status(out, id, Sftp.OP_UNSUPPORTED, "Unsupported in this fake")
        }
    }

    private var handleCounter = 0
    private val openHandles = LinkedHashMap<String, String>()

    /** READDIR is answered once with the children, then with EOF. */
    private var pendingEntries: List<SftpName>? = null

    fun willReturnFromReadDir(entries: List<SftpName>) {
        pendingEntries = entries
    }

    // ------------------------------------------------------------------ helpers

    private fun reply(out: OutputStream, writer: SftpWriter) {
        val payload = writer.toByteArray()
        val header = ByteArrayOutputStream().apply {
            val v = payload.size
            write((v ushr 24) and 0xFF)
            write((v ushr 16) and 0xFF)
            write((v ushr 8) and 0xFF)
            write(v and 0xFF)
        }.toByteArray()
        out.write(header)
        out.write(payload)
    }

    /** A DATA packet carrying N uint64 values, as statvfs replies with. */
    private fun data(out: OutputStream, id: Int, vararg values: Long) {
        reply(out, SftpWriter().apply {
            byte(Sftp.FXP_DATA)
            uint32(id.toLong())
            for (value in values) uint64(value)
        })
    }

    private fun status(out: OutputStream, id: Int, code: Int, message: String) {
        reply(out, SftpWriter().apply {
            byte(Sftp.FXP_STATUS)
            uint32(id.toLong())
            uint32(code.toLong())
            string(message)
            string("")
        })
    }

    private fun name(out: OutputStream, id: Int, names: List<SftpName>) {
        reply(out, SftpWriter().apply {
            byte(Sftp.FXP_NAME)
            uint32(id.toLong())
            uint32(names.size.toLong())
            for (n in names) {
                string(n.filename)
                string(n.longName)
                attrs(n.attrs)
            }
        })
    }

    private fun fileAttrs(size: Int) = SftpAttrs(
        size = size.toLong(),
        uid = 1000L,
        gid = 1000L,
        permissions = 0x81A4L,
        accessTime = 1_700_000_000L,
        modifyTime = 1_700_000_000L
    )

    private fun dirAttrs() = SftpAttrs(
        size = 4096L,
        uid = 1000L,
        gid = 1000L,
        permissions = 0x41EDL,
        accessTime = 1_700_000_000L,
        modifyTime = 1_700_000_000L
    )

    private fun readIntOrNull(input: InputStream): Int? {
        val head = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(head, read, 4 - read)
            if (n <= 0) return null
            read += n
        }
        return ((head[0].toInt() and 0xFF) shl 24) or
            ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or
            (head[3].toInt() and 0xFF)
    }

    private fun normalise(path: String): String = SftpClient.normalise(path)
}
