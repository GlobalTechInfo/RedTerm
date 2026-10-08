package com.redtermapp.util.sftp

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The SFTP protocol, spoken over whatever byte streams it is given.
 *
 * Requests are serialised: one is sent, and the next packet carrying its id is
 * the answer. That is simpler than multiplexing and costs nothing here, because
 * every operation is a round trip the user is waiting on anyway. It also means the
 * client can be driven over plain pipes in a test, with no process, no proot and
 * no server.
 *
 * This is draft-ietf-secsh-filexfer version 3, which is what OpenSSH's `sftp`
 * subsystem implements.
 */
class SftpSession(
    input: InputStream,
    output: OutputStream
) : Closeable {

    private val input = input
    private val output = output
    private var nextId = 1
    private var serverVersion = 0

    /**
     * How long to wait for the server before giving up.
     *
     * A process pipe has no read timeout to set, and a server that accepts the
     * connection and then says nothing would otherwise leave the transfer thread
     * blocked forever with a progress dialog stuck open.
     */
    @Volatile
    var timeoutMillis: Long = 60_000L

    /**
     * Complete packets as they arrive; a null element means the peer hung up.
     *
     * Framing is done on a reader thread rather than by the caller, for two
     * reasons. A caller can then wait with a real timeout, since blocking in read
     * on a pipe cannot be given one; and end-of-stream is observable at all. The
     * obvious alternative — poll `available()` and read only when it is positive —
     * cannot tell "nothing yet" from "the process has exited", because a closed
     * pipe also reports zero available. Every failure then looked like a timeout,
     * and the server's actual complaint was lost.
     *
     * End of stream is a flag rather than a null element: LinkedBlockingQueue
     * rejects nulls, and offering one throws instead of signalling.
     */
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val ended = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        Thread({
            try {
                while (!stopping.get()) {
                    val header = ByteArray(4)
                    if (!readExactly(header)) break
                    val length = ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                    if (length < 1 || length > Sftp.MAX_PACKET) break
                    val payload = ByteArray(length)
                    if (!readExactly(payload)) break
                    inbound.offer(payload)
                }
            } catch (_: Exception) {
                // The stream broke; ended below is the signal.
            }
            ended.set(true)
        }, "redterm-sftp-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Blocking, on the reader thread only, where a stuck read cannot be waited on. */
    private fun readExactly(buffer: ByteArray): Boolean {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) return false
            filled += read
        }
        return true
    }

    /**
     * The INIT/VERSION exchange, returning the version the server agreed on.
     *
     * Must happen before anything else and exactly once: the server will not
     * answer a request it has not been initialised for.
     */
    fun version(): Int {
        val packet = SftpWriter().apply {
            byte(Sftp.FXP_INIT)
            uint32(Sftp.VERSION.toLong())
        }
        send(packet)
        val (type, reader) = receive()
        if (type != Sftp.FXP_VERSION) {
            throw SftpProtocolException("expected VERSION, got packet type $type")
        }
        serverVersion = reader.uint32().toInt()
        return serverVersion
    }

    // ------------------------------------------------------------------ queries

    /** Resolves [path] to an absolute path, expanding `~`. */
    fun realPath(path: String): String {
        // A NAME response carrying exactly one entry: the resolved name, its
        // ls-style description, and its attributes.
        val response = request(Sftp.FXP_REALPATH) { string(path) }
        if (response.type != Sftp.FXP_NAME) {
            throw SftpProtocolException("expected NAME from REALPATH, got ${response.type}")
        }
        val names = response.names()
        if (names.isEmpty()) throw SftpProtocolException("REALPATH returned no name")
        return names.first().filename
    }

    /**
     * Attributes of [path], following symlinks. Null only when it is not there.
     *
     * A permission failure throws rather than returning null: "you may not look"
     * and "it does not exist" look identical to a user, and collapsing them means
     * a file that is really there appears to be missing.
     */
    fun stat(path: String): SftpAttrs? =
        request(Sftp.FXP_STAT) { string(path) }.attrsOrNull("STAT")

    /** Attributes of [path] without following symlinks. */
    fun lstat(path: String): SftpAttrs? =
        request(Sftp.FXP_LSTAT) { string(path) }.attrsOrNull("LSTAT")

    /** Everything in [path], excluding `.` and `..`. */
    fun list(path: String): List<SftpName> {
        val handle = openDir(path)
        return try {
            val all = mutableListOf<SftpName>()
            while (true) {
                val reader = request(Sftp.FXP_READDIR) { string(handle) }
                if (reader.type == Sftp.FXP_STATUS && reader.status == Sftp.EOF) break
                if (reader.type != Sftp.FXP_NAME) {
                    throw SftpProtocolException("expected NAME from READDIR, got ${reader.type}")
                }
                all += reader.names()
            }
            all.filterNot { it.filename == "." || it.filename == ".." }
        } finally {
            closeQuietly(handle)
        }
    }

    private fun openDir(path: String): String {
        val response = request(Sftp.FXP_OPENDIR) { string(path) }
        response.throwIfStatus("OPENDIR")
        if (response.type != Sftp.FXP_HANDLE) {
            throw SftpProtocolException("expected HANDLE from OPENDIR, got ${response.type}")
        }
        return response.string()
    }

    // -------------------------------------------------------------------- files

    /**
     * Opens [path] and returns its handle.
     *
     * @throws SftpException when the server refuses, so the caller gets the
     *   server's own wording for "permission denied" rather than a generic one.
     */
    fun open(
        path: String,
        write: Boolean,
        create: Boolean = false,
        truncate: Boolean = false,
        append: Boolean = false,
        exclusive: Boolean = false
    ): String {
        var flags = 0
        if (write) flags = flags or Sftp.FXF_WRITE else flags = flags or Sftp.FXF_READ
        if (append) flags = flags or Sftp.FXF_APPEND
        if (create) flags = flags or Sftp.FXF_CREAT
        if (truncate) flags = flags or Sftp.FXF_TRUNC
        if (exclusive) flags = flags or Sftp.FXF_EXCL
        val response = request(Sftp.FXP_OPEN) {
            string(path)
            uint32(flags.toLong())
            attrs(SftpAttrs())
        }
        response.throwIfStatus("OPEN")
        if (response.type != Sftp.FXP_HANDLE) {
            throw SftpProtocolException("expected HANDLE from OPEN, got ${response.type}")
        }
        return response.string()
    }

    /** Reads up to [length] bytes at [offset]; null at end of file. */
    fun read(handle: String, offset: Long, length: Int): ByteArray? {
        val reader = request(Sftp.FXP_READ) {
            string(handle)
            uint64(offset)
            uint32(length.toLong())
        }
        if (reader.type == Sftp.FXP_STATUS && reader.status == Sftp.EOF) return null
        if (reader.type != Sftp.FXP_DATA) {
            throw SftpProtocolException("expected DATA from READ, got ${reader.type}")
        }
        // Undecoded: this is file content, and UTF-8 round-tripping would
        // corrupt every byte sequence that is not valid UTF-8.
        return reader.byteString()
    }

    fun write(handle: String, offset: Long, data: ByteArray) {
        val reader = request(Sftp.FXP_WRITE) {
            string(handle)
            uint64(offset)
            // The data travels as a length-prefixed string, so a payload
            // containing NULs or newlines survives intact.
            uint32(data.size.toLong())
            raw(data)
        }
        if (reader.type != Sftp.FXP_STATUS || reader.status != Sftp.OK) {
            throw SftpProtocolException("WRITE was refused: ${reader.describe()}")
        }
    }

    fun close(handle: String) {
        val reader = request(Sftp.FXP_CLOSE) { string(handle) }
        if (reader.status != Sftp.OK) {
            throw SftpProtocolException("CLOSE was refused: ${reader.describe()}")
        }
    }

    // ------------------------------------------------------------------ changes

    fun mkdir(path: String): Unit = expectStatus(Sftp.FXP_MKDIR) {
        string(path)
        attrs(SftpAttrs())
    }

    fun remove(path: String): Unit = expectStatus(Sftp.FXP_REMOVE) { string(path) }

    fun rmdir(path: String): Unit = expectStatus(Sftp.FXP_RMDIR) { string(path) }

    /**
     * Changes a path's permissions.
     *
     * [mode] is POSIX permission bits only, without the file-type bits: SETSTAT
     * replaces the mode, so sending the whole `0755` including a type would leave the
     * file looking like a directory to the kernel. Callers pass what the user chose.
     *
     * Setstat was already on the wire and simply never called, so permissions on a
     * remote file could be read but not changed.
     */
    fun setPermissions(path: String, mode: Long): Unit = expectStatus(Sftp.FXP_SETSTAT) {
        string(path)
        attrs(SftpAttrs(permissions = mode and PERMISSION_BITS))
    }

    /** Makes a symbolic link. [target] is where the link points, not where it sits. */
    fun symlink(target: String, linkPath: String): Unit = expectStatus(Sftp.FXP_SYMLINK) {
        string(linkPath)
        string(target)
    }

    /**
     * Free space on the filesystem holding [path].
     *
     * `statvfs` is an extension rather than part of version 3, so it is asked for as
     * one. Every OpenSSH server has supported it since 2.x, and a server that does not
     * answers with an "unsupported" status, which surfaces here as null rather than
     * as a failure — an unknown free-space figure is not worth refusing an operation
     * over.
     */
    fun statvfs(path: String): StatVfs? {
        val response = request(Sftp.FXP_EXTENDED) {
            string(Sftp.EXT_STATVFS)
            string(path)
        }
        if (response.type == Sftp.FXP_STATUS) return null
        if (response.type != Sftp.FXP_DATA) {
            throw SftpProtocolException("expected DATA from statvfs, got ${response.type}")
        }
        val reader = response.reader
        return StatVfs(
            blockSize = reader.uint64(),
            blockCount = reader.uint64(),
            freeBlocks = reader.uint64(),
            availableBlocks = reader.uint64()
        )
    }

    /** The nine permission bits, plus the setuid/setgid/sticky bits above them. */
    private val PERMISSION_BITS = 0x1FFFL

    fun rename(from: String, to: String): Unit = expectStatus(Sftp.FXP_RENAME) {
        string(from)
        string(to)
    }

    private fun expectStatus(type: Int, payload: SftpWriter.() -> Unit) {
        val reader = request(type, payload)
        if (reader.status != Sftp.OK) {
            throw SftpException(
                Sftp.statusText(reader.status, reader.statusMessage), reader.status
            )
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun send(body: SftpWriter) {
        val payload = body.toByteArray()
        val header = SftpWriter().apply { uint32(payload.size.toLong()) }
        val headerBytes = header.toByteArray()
        synchronized(this) {
            output.write(headerBytes)
            output.write(payload)
            output.flush()
        }
    }

    /**
     * Takes the next packet: its type byte and a reader at its payload.
     *
     * Polled in short slices so that a peer that has hung up is noticed promptly
     * rather than only when the whole deadline has passed. A refused connection
     * ends the stream within milliseconds, and reporting that as a 60 second stall
     * would be both wrong and the reason a real error went unnoticed.
     */
    private fun receive(): Pair<Int, SftpReader> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw SftpProtocolException(
                    "the server stopped responding for ${timeoutMillis / 1000}s"
                )
            }
            val payload = try {
                inbound.poll(minOf(remaining, POLL_SLICE_MS), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SftpProtocolException("the transfer was interrupted")
            }
            if (payload != null) {
                val reader = SftpReader(payload)
                val type = reader.byte()
                return type to reader
            }
            if (ended.get()) {
                throw SftpProtocolException("the server closed the connection")
            }
        }
    }

    /**
     * Sends one request and returns the packet that answers it.
     *
     * Packets carrying a different id are discarded rather than mistaken for the
     * answer. Nothing else should arrive on an idle session, but a server that
     * sends a STATUS with no id (which the specification allows) must not be read
     * as the answer to whatever happened to be in flight.
     */
    private fun request(type: Int, payload: SftpWriter.() -> Unit): Response {
        val id = nextId++
        send(SftpWriter().apply {
            byte(type)
            uint32(id.toLong())
            payload()
        })
        while (true) {
            val (packetType, reader) = receive()
            val packetId = reader.uint32().toInt()
            if (packetId != id) continue
            return Response(packetType, reader)
        }
    }

    override fun close() {
        stopping.set(true)
        ended.set(true)
        try {
            output.flush()
        } catch (_: Exception) {
            // Already gone; closing is best effort.
        }
    }

    /** A decoded response, with the status fields pre-read where they apply. */
    private class Response(val type: Int, val reader: SftpReader) {
        val status: Int
        val statusMessage: String

        init {
            // Only a STATUS carries these; for everything else they stay neutral
            // so callers can compare against OK without checking the type first.
            if (type == Sftp.FXP_STATUS) {
                status = reader.uint32().toInt()
                statusMessage = reader.string()
            } else {
                status = -1
                statusMessage = ""
            }
        }

        /**
         * Rethrows a STATUS as an exception, keeping the server's own code.
         *
         * Without this a refusal is silently turned into "no handle came back",
         * which the caller cannot tell from a malformed reply and reports as a
         * protocol error instead of the permission problem it actually was.
         */
        fun throwIfStatus(operation: String) {
            if (type == Sftp.FXP_STATUS && status != Sftp.OK) {
                throw SftpException(
                    "$operation failed: " + Sftp.statusText(status, statusMessage), status
                )
            }
        }

        /**
         * Attributes, or null when the path is simply not there.
         *
         * Any other status is a real failure and is thrown, so a caller cannot
         * mistake "refused" for "absent".
         */
        fun attrsOrNull(operation: String): SftpAttrs? {
            if (type == Sftp.FXP_ATTRS) return reader.attrs()
            if (type == Sftp.FXP_STATUS && status == Sftp.NO_SUCH_FILE) return null
            throwIfStatus(operation)
            throw SftpProtocolException("expected ATTRS from $operation, got $type")
        }

        /** The payload as text: a handle name, or a resolved path. */
        fun string(): String = reader.string()

        /** The payload as raw bytes: file content, never decoded. */
        fun byteString(): ByteArray = reader.byteString()

        fun describe(): String =
            if (type == Sftp.FXP_STATUS) Sftp.statusText(status, statusMessage)
            else "unexpected packet $type"

        fun names(): List<SftpName> {
            val count = reader.uint32()
            if (count < 0 || count > 100_000) {
                throw SftpProtocolException("implausible NAME count $count")
            }
            val entries = ArrayList<SftpName>(count.toInt())
            repeat(count.toInt()) {
                val filename = reader.string()
                val longName = reader.string()
                entries.add(SftpName(filename, longName, reader.attrs()))
            }
            return entries
        }
    }

    private fun closeQuietly(handle: String) {
        try {
            close(handle)
        } catch (_: Exception) {
            // The handle is being discarded anyway.
        }
    }

    private companion object {
        /** How long one wait on the queue lasts before re-checking for closure. */
        const val POLL_SLICE_MS = 100L
    }
}
