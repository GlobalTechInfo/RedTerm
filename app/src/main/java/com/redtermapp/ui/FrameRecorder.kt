package com.redtermapp.ui

import java.io.BufferedOutputStream
import java.io.File

/**
 * One open recording file, written one encoded frame at a time.
 *
 * A frame never leaves its caller until it is on disk (`flush` per write), so a
 * session that dies abruptly — kill, crash, power loss — leaves every frame up to
 * the last one drawn rather than whatever survived in the stream when the process
 * ended. The frames and byte counts are kept for the stop log, because "recording
 * stopped" with no numbers tells a user looking at a suspiciously short file
 * nothing they could work from.
 */
class FrameRecorder(val file: File) {

    private val stream = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var closed = false

    var frames = 0
        private set
    var bytes = 0L
        private set
    var lastError: String? = null
        private set

    /** False only when a write failed: the owner then stops and reports it. */
    fun write(frame: String?): Boolean {
        if (closed || frame == null) return !closed
        return try {
            val bytes = frame.toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.flush()
            frames++
            this.bytes += bytes.size
            true
        } catch (e: Exception) {
            lastError = e.message
            closed = true
            false
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            runCatching { stream.flush(); stream.close() }
        }
    }

    /** True from just after construction until a failure or [close]. */
    val isOpen: Boolean get() = !closed
}
