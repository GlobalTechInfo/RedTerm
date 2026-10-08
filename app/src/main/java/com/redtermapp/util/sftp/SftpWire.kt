package com.redtermapp.util.sftp

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The byte-level encoding of SFTP packets.
 *
 * Everything on the wire is big-endian: a uint32 length, a one-byte type, and then
 * type-specific fields made of fixed-width integers and length-prefixed strings.
 * That is the whole of the protocol's data representation, so it is kept apart
 * from the request/response layer to stay checkable on its own.
 *
 * A "string" is a uint32 byte count followed by UTF-8 bytes. The count is a byte
 * count, not a character count, which is the one thing that silently corrupts a
 * non-ASCII filename when it is got wrong.
 */

/** Protocol constants, named as the specification names them. */
object Sftp {
    const val VERSION = 3

    // Requests
    const val FXP_INIT = 1
    const val FXP_VERSION = 2
    const val FXP_OPEN = 3
    const val FXP_CLOSE = 4
    const val FXP_READ = 5
    const val FXP_WRITE = 6
    const val FXP_LSTAT = 7
    const val FXP_FSTAT = 8
    const val FXP_SETSTAT = 9
    const val FXP_OPENDIR = 11
    const val FXP_READDIR = 12
    const val FXP_REMOVE = 13
    const val FXP_MKDIR = 14
    const val FXP_RMDIR = 15
    const val FXP_REALPATH = 16
    const val FXP_STAT = 17
    const val FXP_RENAME = 18
    const val FXP_SYMLINK = 20
    const val FXP_EXTENDED = 200

    // Responses
    const val FXP_STATUS = 101
    const val FXP_HANDLE = 102
    const val FXP_DATA = 103
    const val FXP_NAME = 104
    const val FXP_ATTRS = 105

    /**
     * Extension names, not numbers: these travel on the wire as strings and the
     * numbers are only their position in the list.
     *
     * `statvfs@openssh.com` is what every OpenSSH server has implemented since 2.x.
     */
    const val EXT_STATVFS = "statvfs@openssh.com"
    const val EXT_FSTATVFS = "fstatvfs@openssh.com"
    const val EXT_FSETSTAT = "fsetstat@openssh.com"

    // Status codes
    const val OK = 0
    const val EOF = 1
    const val NO_SUCH_FILE = 2
    const val PERMISSION_DENIED = 3
    const val FAILURE = 4
    const val BAD_MESSAGE = 5
    const val NO_CONNECTION = 6
    const val CONNECTION_LOST = 7
    const val OP_UNSUPPORTED = 8

    // open() flags
    const val FXF_READ = 0x0001
    const val FXF_WRITE = 0x0002
    const val FXF_APPEND = 0x0004
    const val FXF_CREAT = 0x0008
    const val FXF_TRUNC = 0x0010
    const val FXF_EXCL = 0x0020

    // attribute flags
    const val FILEXFER_ATTR_SIZE = 0x0001
    const val FILEXFER_ATTR_UIDGID = 0x0002
    const val FILEXFER_ATTR_PERMISSIONS = 0x0004
    const val FILEXFER_ATTR_ACMODTIME = 0x0008
    const val FILEXFER_ATTR_EXTENDED = -0x80000000

    /** Largest packet accepted, so a corrupt length cannot ask for a huge read. */
    const val MAX_PACKET = 256 * 1024

    /** Refuse absurd transfer requests rather than allocating them. */
    const val MAX_CHUNK = 64 * 1024

    fun statusText(code: Int, message: String): String = when (code) {
        OK -> message
        EOF -> "End of file"
        NO_SUCH_FILE -> "No such file or directory"
        PERMISSION_DENIED -> "Permission denied"
        FAILURE -> message.ifBlank { "The server reported a failure" }
        BAD_MESSAGE -> "The server rejected a message as malformed"
        NO_CONNECTION -> "The server closed the connection"
        CONNECTION_LOST -> "The connection was lost"
        OP_UNSUPPORTED -> "The server does not support that operation"
        else -> message.ifBlank { "The server reported error $code" }
    }
}

/** Builds one packet's payload. */
internal class SftpWriter {
    private val out = ByteArrayOutputStream(256)

    fun byte(value: Int) {
        out.write(value and 0xFF)
    }

    fun uint32(value: Long) {
        val v = value.toInt()
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    fun uint64(value: Long) {
        uint32((value ushr 32) and 0xFFFFFFFFL)
        uint32(value and 0xFFFFFFFFL)
    }

    fun raw(bytes: ByteArray) {
        out.write(bytes, 0, bytes.size)
    }

    /** A length-prefixed string. The prefix counts bytes, not characters. */
    fun string(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        uint32(bytes.size.toLong())
        raw(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads one packet's payload, refusing to run off the end. */
internal class SftpReader(private val data: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = data.size - pos

    private fun need(count: Int) {
        if (count < 0 || pos + count > data.size) {
            throw SftpProtocolException(
                "truncated packet: needed $count byte(s) at offset $pos of ${data.size}"
            )
        }
    }

    fun byte(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun uint32(): Long {
        need(4)
        return ((data[pos++].toLong() and 0xFF) shl 24) or
            ((data[pos++].toLong() and 0xFF) shl 16) or
            ((data[pos++].toLong() and 0xFF) shl 8) or
            (data[pos++].toLong() and 0xFF)
    }

    fun uint64(): Long {
        val high = uint32()
        val low = uint32()
        return (high shl 32) or low
    }

    fun raw(count: Int): ByteArray {
        need(count)
        val slice = data.copyOfRange(pos, pos + count)
        pos += count
        return slice
    }

    /** A length-prefixed byte string, undecoded. */
    fun byteString(): ByteArray {
        val length = uint32()
        if (length < 0 || length > data.size) {
            throw SftpProtocolException("implausible string length $length")
        }
        return raw(length.toInt())
    }

    fun string(): String = String(byteString(), StandardCharsets.UTF_8)

    fun rest(): ByteArray = raw(remaining)
}

/** Anything malformed, unexpected or refused by the server. */
open class SftpException(
    message: String,
    val statusCode: Int = -1
) : Exception(message)

class SftpProtocolException(message: String) : SftpException(message)

// The three mode bits that sit above the nine permission bits.
private const val S_ISUID = 0x800L
private const val S_ISGID = 0x400L
private const val S_ISVTX = 0x200L

/**
 * One file's attributes.
 *
 * Every field is optional in the protocol, and servers genuinely omit them, so
 * each is nullable rather than defaulted to a plausible-looking zero — "size 0"
 * and "the server did not say" must not look the same in a file list.
 */
/**
 * Free space, as `statvfs` reports it.
 *
 * [availableBlocks] rather than [freeBlocks]: blocks reserved for root are counted in
 * the second and not the first, and a figure that includes them is one this user
 * cannot actually use.
 */
data class StatVfs(
    val blockSize: Long,
    val blockCount: Long,
    val freeBlocks: Long,
    val availableBlocks: Long
) {
    val totalBytes: Long get() = blockSize * blockCount
    val availableBytes: Long get() = blockSize * availableBlocks
}

data class SftpAttrs(
    val size: Long? = null,
    val uid: Long? = null,
    val gid: Long? = null,
    /** The raw POSIX mode, including the file-type bits. */
    val permissions: Long? = null,
    val accessTime: Long? = null,
    val modifyTime: Long? = null
) {
    /** True when the mode says directory, which is what decides browsability. */
    val isDirectory: Boolean get() = permissions?.let { (it shr 12) and 0xF == 0x4L } ?: false

    val isSymlink: Boolean get() = permissions?.let { (it shr 12) and 0xF == 0xAL } ?: false

    /** The `drwxr-xr-x` form, for the details line. */
    val modeText: String
        get() {
            val mode = permissions ?: return ""
            val type = when ((mode shr 12) and 0xF) {
                0x4L -> 'd'; 0xAL -> 'l'; 0x8L -> '-'
                else -> '?'
            }
            val letters = charArrayOf('r', 'w', 'x')
            val text = StringBuilder(10)
            text.append(type)
            // The nine permission bits sit at 8..0: user rwx at 8..6, group at
            // 5..3, other at 2..0.
            for (group in 0..2) {
                for (bit in 0..2) {
                    val shift = 8 - (group * 3 + bit)
                    val set = mode shr shift and 1L == 1L
                    text.append(if (set) letters[bit] else '-')
                }
            }
            // setuid, setgid and sticky sit above them, and each shows up in the
            // x position it belongs to: 3, 6 and 9.
            if (mode and S_ISUID != 0L) text[3] = if (mode shr 6 and 1L == 1L) 's' else 'S'
            if (mode and S_ISGID != 0L) text[6] = if (mode shr 3 and 1L == 1L) 's' else 'S'
            if (mode and S_ISVTX != 0L) text[9] = if (mode and 1L == 1L) 't' else 'T'
            return text.toString()
        }
}

/** One name from a READDIR, or one entry of a NAME response. */
data class SftpName(val filename: String, val longName: String, val attrs: SftpAttrs)

internal fun SftpWriter.attrs(value: SftpAttrs) {
    var flags = 0L
    if (value.size != null) flags = flags or Sftp.FILEXFER_ATTR_SIZE.toLong()
    if (value.uid != null && value.gid != null) flags = flags or Sftp.FILEXFER_ATTR_UIDGID.toLong()
    if (value.permissions != null) flags = flags or Sftp.FILEXFER_ATTR_PERMISSIONS.toLong()
    if (value.accessTime != null && value.modifyTime != null) {
        flags = flags or Sftp.FILEXFER_ATTR_ACMODTIME.toLong()
    }
    uint32(flags)
    if (value.size != null) uint64(value.size)
    if (value.uid != null && value.gid != null) {
        uint32(value.uid); uint32(value.gid)
    }
    if (value.permissions != null) uint32(value.permissions)
    if (value.accessTime != null && value.modifyTime != null) {
        uint32(value.accessTime); uint32(value.modifyTime)
    }
    // The extended-pair count is part of the payload only when the EXTENDED flag
    // is set. Writing it unconditionally puts four extra bytes in every attribute
    // block, which shifts everything after it: the reader then takes a permission
    // value for a filename length and the whole stream desynchronises.
}

internal fun SftpReader.attrs(): SftpAttrs {
    val flags = uint32().toInt()
    val size = if (flags and Sftp.FILEXFER_ATTR_SIZE != 0) uint64() else null
    var uid: Long? = null
    var gid: Long? = null
    if (flags and Sftp.FILEXFER_ATTR_UIDGID != 0) {
        uid = uint32(); gid = uint32()
    }
    val permissions = if (flags and Sftp.FILEXFER_ATTR_PERMISSIONS != 0) uint32() else null
    var access: Long? = null
    var modify: Long? = null
    if (flags and Sftp.FILEXFER_ATTR_ACMODTIME != 0) {
        access = uint32(); modify = uint32()
    }
    if (flags and Sftp.FILEXFER_ATTR_EXTENDED != 0) {
        // Unknown attributes have to be consumed or the rest of the packet is
        // read from the wrong offset.
        repeat(uint32().toInt().coerceIn(0, 4096)) {
            string(); string()
        }
    }
    return SftpAttrs(size, uid, gid, permissions, access, modify)
}
