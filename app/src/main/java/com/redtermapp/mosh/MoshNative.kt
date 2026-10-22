package com.redtermapp.mosh

import android.content.Context
import android.util.Log
import com.redtermapp.util.AppLog

/**
 * The JNI boundary for mosh's protocol libraries.
 *
 * Phase 1's requirement is narrow: the library loads on every ABI RedTerm ships and
 * demonstrably works on a device. That sounds weak, and it is deliberately so — an
 * earlier probe of this same build linked a 5,216-byte object with a single dynamic
 * symbol, which proved the archives were on disk and nothing else, because nothing
 * referenced anything. The link now runs with `--no-undefined`, and this class exists
 * so there is a real call across the boundary to be wrong about.
 *
 * Built by `native/build-mosh.sh` into `app/src/main/jniLibs/<abi>/libredtermmosh.so`.
 * The sources it wraps are mosh's own: the State Synchronization Protocol, its
 * terminal state machine, and its AES-128-OCB authenticated encryption over nettle.
 */
object MoshNative {

    private const val TAG = "Mosh"

    /**
     * Why the library is unavailable, or null when it is fine.
     *
     * Null here and a message both leave mosh out of the picture; the difference is
     * that one is a decision and the other is a fault the user cannot see. A device
     * whose ABI we never built, or a load that fails, must be reported rather than
     * silently falling back to ssh on every server forever.
     */
    @Volatile
    var loadError: String? = null
        private set

    @Volatile
    private var loaded = false

    /**
     * The application context, kept so failures can reach Diagnostics.
     *
     * A session outlives the activity that started it — that is the whole point of one
     * — so [MoshSession] has no Context of its own to log with, and a native method
     * that is missing from the library still has to say so somewhere the user can
     * read. Captured once at load time for that reason.
     */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    var version: String? = null
        private set

    /**
     * Loads the library once per process.
     *
     * Every caller is a UI thread, and `System.loadLibrary` on a missing library
     * throws, so this cannot be allowed to reach an uncaught-exception handler. The
     * error is kept in [loadError] and mosh then behaves as "not available", which is
     * a state the UI already knows how to render.
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        loadError?.let { return false }
        return try {
            appContext = context.applicationContext
            System.loadLibrary("redtermmosh")
            version = nativeVersion()
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "libredtermmosh.so did not load: ${e.message}"
            AppLog.e(context, TAG, loadError!!)
            false
        } catch (e: SecurityException) {
            loadError = "libredtermmosh.so was refused: ${e.message}"
            AppLog.e(context, TAG, loadError!!)
            false
        }
    }

    /**
     * Round-trips a message through mosh's authenticated encryption on this device.
     *
     * Returns 1 on a correct round trip, 0 on a wrong one, -1 if an exception escaped
     * the native layer. Any of them means the native side is broken in a way that has
     * nothing to do with Kotlin, which is why it is reported by number rather than as
     * a boolean: "it did not work" and "it threw" are different bugs.
     */
    fun selfTest(context: Context): Int {
        if (!ensureLoaded(context)) return -1
        return try {
            nativeSelfTest()
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(context, TAG, "selfTest could not call native: ${e.message}")
            -1
        }
    }

    private external fun nativeSelfTest(): Int
    private external fun nativeVersion(): String

    /**
     * Starts a session against a mosh-server that ssh has already started.
     *
     * [key] is the secret half of MOSH_KEY and must never be logged, put in a command
     * line or written to disk — see the notes in `native/redterm_mosh.c`, which zeroes
     * its copy as soon as the session has taken one. The native side never returns it,
     * not even in an error message.
     *
     * Returns null on failure, with [errorOut] set to something readable. Callers treat
     * null as "fall back to ssh", because a device that cannot start mosh should still
     * be able to reach the host.
     */
    fun start(
        key: String,
        ip: String,
        port: Int,
        cols: Int,
        rows: Int,
        errorOut: StringBuilder,
    ): MoshSession? {
        if (!loaded) {
            errorOut.append(loadError ?: "mosh native library is not loaded")
            return null
        }
        val handle = try {
            nativeStart(key, ip, port, cols, rows, errorOut)
        } catch (e: UnsatisfiedLinkError) {
            appContext?.let { AppLog.e(it, TAG, "nativeStart is missing from this build: ${e.message}") }
            errorOut.append("mosh native library is out of date")
            0L
        }
        return if (handle == 0L) null else MoshSession(handle)
    }

    internal external fun nativeStart(
        key: String,
        ip: String,
        port: Int,
        cols: Int,
        rows: Int,
        errorOut: StringBuilder,
    ): Long

    /**
     * A live mosh session, and the only thing that owns its native handle.
     *
     * The handle is a raw pointer held in a Long, so its lifetime has to be explicit:
     * [close] stops the worker thread *before* freeing, which is the only ordering in
     * which no native call can touch freed memory. [stop] exists separately because a
     * dropped connection and a finished session are different things — stopping leaves
     * the last framebuffer readable, so a session can be shown as ended rather than
     * blanked.
     */
    class MoshSession internal constructor(private var handle: Long) : AutoCloseable {

        @Volatile
        private var stopped = false

        val isConnected: Boolean
            get() = handle != 0L && runCatching { nativeIsConnected(handle) }.getOrDefault(false)

        /** mosh's own idea of how long to wait, already clamped by the native layer. */
        val waitTimeMs: Int
            get() = if (handle == 0L) 0 else runCatching { nativeWaitTime(handle) }.getOrDefault(0)

        /** The last failure the worker thread saw, for Diagnostics. Never the key. */
        val lastError: String
            get() = if (handle == 0L) "" else runCatching { nativeLastError(handle) }.getOrDefault("")

        fun input(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            if (handle == 0L || length <= 0) return
            val slice = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
            runCatching { nativeInput(handle, slice) }
        }

        fun input(text: String) = input(text.toByteArray(Charsets.UTF_8))

        /** Columns and rows, in that order, because that is the order mosh takes. */
        fun resize(cols: Int, rows: Int) {
            if (handle == 0L) return
            runCatching { nativeResize(handle, cols, rows) }
        }

        /**
         * Drains whatever the remote has sent into [into], and returns how many bytes.
         *
         * Zero is not the same as "the connection is gone": mosh tolerates loss, and a
         * server that has gone away leaves the UDP socket readable and empty, which is
         * indistinguishable from an idle link at this level. Callers that need to know
         * whether the session is alive ask [isConnected] after a period of silence —
         * which is mosh's own model, and the reason it survives a network switch.
         */
        fun drain(into: ByteArray): Int {
            if (handle == 0L || into.isEmpty()) return 0
            return runCatching { nativePoll(handle, into) }.getOrDefault(0)
        }

        /** Stops the worker thread. The session stays readable until [close]. */
        fun stop() {
            if (handle != 0L && !stopped) {
                stopped = true
                runCatching { nativeStop(handle) }
            }
        }

        override fun close() {
            if (handle != 0L) {
                stop()
                runCatching { nativeFree(handle) }
                handle = 0L
            }
        }
    }

    internal external fun nativeIsConnected(handle: Long): Boolean
    internal external fun nativeWaitTime(handle: Long): Int
    internal external fun nativeLastError(handle: Long): String
    internal external fun nativeInput(handle: Long, bytes: ByteArray)
    internal external fun nativeResize(handle: Long, cols: Int, rows: Int)
    internal external fun nativePoll(handle: Long, into: ByteArray): Int
    internal external fun nativeStop(handle: Long)
    internal external fun nativeFree(handle: Long)

    init {
        Log.i(TAG, "MoshNative loaded")
    }
}