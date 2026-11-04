package com.redtermapp.util.api

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import androidx.annotation.RequiresApi
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The app-side half of RedTerm's termux:API bridge, and why it is a thread
 * and not a Service: an abstract Unix socket is an app-owned namespace, it
 * dies with the process exactly like any other in-memory state, and
 * restarting it is one line in [start]. Nothing here outlives the process,
 * so nothing here needs to be persisted either.
 *
 * The wire protocol is Termux:API's, so the guest-side helper is
 * interchangeable with what termux scripts already expect: a uint16 LE
 * length, then the text of a fake `am` command line; the answer (the
 * action's result, or the error text the guest must be able to read) goes
 * back on the same connection, length-prefixed the same way.
 *
 * The peer must be this app's own uid: the guest processes of a proot
 * session run as it, which is precisely what makes them reachable, and
 * it is what keeps every other app on the device out.
 */
object ApiSocket {

    /** The abstract socket name. 108 bytes is the kernel's ceiling; this is 22. */
    const val SOCKET_NAME = "com.redtermapp://listen"

    /**
     * How many request threads may work at once. Each accepted connection
     * gets a thread that blocks on the socket; an unbounded accept loop
     * means a guest (or a buggy script) that opens connections without
     * sending can exhaust the process thread limit, and the resulting
     * OutOfMemoryError is not an Exception, so it would take the listener
     * down with it.
     */
    private const val MAX_CONCURRENT_REQUESTS = 16

    /** How long a request thread waits for the peer before giving up. */
    private const val READ_TIMEOUT_MS = 30_000

    private val requestSlots = Semaphore(MAX_CONCURRENT_REQUESTS)

    /**
     * `LocalSocket` has no per-socket read deadline, so a peer that holds the
     * connection open without sending would block a worker forever. The read
     * runs on this bounded pool and the handler waits for it with a deadline;
     * on timeout the handler closes the socket, which is what unblocks the
     * worker. Capping the pool to the same number as [requestSlots] means a
     * burst of stuck peers can never grow past the process's thread budget.
     */
    private val readWorkers = Executors.newFixedThreadPool(
        MAX_CONCURRENT_REQUESTS,
        ThreadFactory { r -> Thread(r, "redterm-api-read").apply { isDaemon = true } }
    )

    /** What the read worker hands back: a parsed request, a refusal, or silence. */
    private sealed interface ReadResult {
        data class Ok(val request: ApiRequest) : ReadResult
        data class Err(val message: String) : ReadResult
        object PeerClosed : ReadResult
    }

    @Volatile
    private var started = false

    @Volatile
    private var running = false

    /** Whether the listener thread is currently alive. */
    fun isRunning(): Boolean = running

    /**
     * Start the bridge. Idempotent; called from the application's create.
     * `api-start` uses it to report a no-op when it is already up, and
     * `api-stop` + this to bring it back after a stop.
     */
    fun start(context: Context) {
        if (started && running) return
        started = true
        if (Build.VERSION.SDK_INT < 28) {
            // The abstract-namespace LocalServerSocket is API 28+. On
            // older devices the bridge never comes up, and the guest
            // helper's connect retries say exactly that.
            AppLog.w(context, "api-socket", "the termux:API bridge needs Android 9.0 (API 28); it is off on this device")
            return
        }
        val app = context.applicationContext
        stopRequested = false
        // Bind on the calling thread: `api-start` reports the bind result
        // right after start() returns, so `running` must be true only once
        // the socket actually holds the name. A failed bind (the previous
        // listener still holding the socket name after a stop) says so
        // instead of clearing the flag a moment later in a finally.
        val listen = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Exception) {
            AppLog.e(app, "api-socket", "could not bind the API socket: ${e.message}")
            return
        }
        listener = listen
        running = true
        Thread({
            serve(app, listen)
        }, "redterm-api-socket").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stop the listener. The `LocalServerSocket` is closed and the accept
     * loop exits; request threads that are mid-handling finish first.
     * `api-start` brings it back with [start].
     */
    @Volatile
    private var stopRequested = false

    /** The bound listener; [stop] closes it so a blocked `accept` returns. */
    @Volatile
    private var listener: LocalServerSocket? = null

    fun stop() {
        stopRequested = true
        running = false
        // Closing the bound socket is what releases the accept loop; the
        // flag alone leaves it blocked in accept() for the life of the
        // process. The in-flight request threads finish on their own.
        val l = listener
        if (l != null) {
            runCatching { l.close() }
            listener = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun serve(app: Context, listen: LocalServerSocket) {
        try {
            while (!stopRequested) {
                val con = runCatching { listen.accept() }.getOrNull() ?: continue
                // One thread per request: a location fix blocks for its
                // timeout, and a single handler would stall every other
                // guest command behind it. The slots cap the threads at
                // [MAX_CONCURRENT_REQUESTS]; a peer that holds connections
                // open without sending gets a busy answer, not a leak.
                if (!requestSlots.tryAcquire(5, TimeUnit.SECONDS)) {
                    runCatching { writeResponse(con, "ERROR: the API bridge is busy; retry in a moment\n", app) }
                    runCatching { con.close() }
                    continue
                }
                Thread({
                    try {
                        handleConnection(con, app)
                    } finally {
                        requestSlots.release()
                    }
                }, "redterm-api-request").also { it.isDaemon = true; it.start() }
            }
        } catch (e: Exception) {
            // The listener is the whole bridge: a dead listener reads as
            // "the app is not running" on the guest side, so record why.
            if (!stopRequested) AppLog.e(app, "api-socket", "API socket stopped: ${e.message}")
        } finally {
            running = false
            listener = null
            runCatching { listen.close() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleConnection(con: LocalSocket, app: Context) {
        try {
            if (con.peerCredentials.uid != app.applicationInfo.uid) {
                con.close()
                return
            }
            val future = readWorkers.submit { readRequest(con) }
            val outcome = try {
                future.get(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // The peer held the connection open past the deadline. Cancel
                // the task and close the socket: the close is what unblocks
                // the worker, since a blocked socket read cannot be
                // interrupted by thread interrupt.
                future.cancel(true)
                AppLog.e(app, "api-socket", "API request timed out after ${READ_TIMEOUT_MS}ms; connection closed")
                runCatching { con.close() }
                return
            } catch (e: ExecutionException) {
                AppLog.e(app, "api-socket", "API request read failed: ${e.cause?.let { it.javaClass.simpleName + ": " + it.message }}")
                runCatching { con.close() }
                return
            }
            try {
                when (outcome) {
                    is ReadResult.Ok -> writeResponse(con, ApiActions.run(app, outcome.request), app)
                    is ReadResult.Err -> writeResponse(con, outcome.message, app)
                    is ReadResult.PeerClosed -> Unit
                }
            } catch (e: Exception) {
                // An exception here would otherwise leave the guest holding a
                // closed pipe with no word: say what happened, to both sides.
                AppLog.e(app, "api-socket", "API request failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { writeResponse(con, "ERROR: ${e.javaClass.simpleName}: ${e.message}\n", app) }
            }
        } finally {
            runCatching { con.close() }
        }
    }

    /**
     * Read and parse one request, on a worker thread. The helper writes the
     * request length as a LITTLE-endian uint16, exactly like the response
     * length this class writes (see writeResponse); a big-endian reader
     * would take an 84-byte request for 84*256 = 21504 and reject it.
     */
    private fun readRequest(con: LocalSocket): ReadResult {
        val input = con.inputStream
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) return ReadResult.PeerClosed
        val length = (b1 shl 8) or b0
        if (length > 16384) return ReadResult.Err("ERROR: request too long ($length bytes)\n")
        val buf = ByteArray(length)
        // A socket read may return fewer bytes than asked; read until the
        // request is complete or the peer hangs up.
        var filled = 0
        while (filled < length) {
            val n = input.read(buf, filled, length - filled)
            if (n < 0) return ReadResult.Err("ERROR: the request was truncated at $filled/$length bytes; it was not run\n")
            filled += n
        }
        return when (val parsed = ApiRequestParser.parse(String(buf, Charsets.UTF_8))) {
            is ApiRequestParser.Result.Ok -> ReadResult.Ok(parsed.request)
            is ApiRequestParser.Result.Error -> ReadResult.Err(parsed.message)
        }
    }

    private fun writeResponse(con: LocalSocket, body: String, app: Context) {
        var bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) {
            // The length field is a uint16: a longer body cannot be
            // described, and writing it anyway would desynchronise the
            // stream for the reader. Answer with a refusal that fits, and
            // log the loss so the feature that produced it can be bounded.
            AppLog.e(app, "api-socket", "API answer of ${bytes.size} bytes exceeds the 65535-byte wire field; refusing")
            bytes = "ERROR: the answer is ${bytes.size} bytes; the wire carries at most 65535 — it was not sent\n"
                .toByteArray(Charsets.UTF_8)
        }
        val out = con.outputStream
        out.write(bytes.size and 0xff)
        out.write((bytes.size shr 8) and 0xff)
        out.write(bytes)
        out.flush()
    }
}
