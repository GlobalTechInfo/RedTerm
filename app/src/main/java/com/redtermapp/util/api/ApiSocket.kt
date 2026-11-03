package com.redtermapp.util.api

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import androidx.annotation.RequiresApi
import com.redtermapp.util.AppLog
import java.io.EOFException

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
        running = true
        Thread({
            serve(app)
        }, "redterm-api-socket").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stop the listener. The `LocalServerSocket` is closed and the accept
     * loop exits; request threads that are mid-handling finish first.
     * `api-start` brings it back with [start].
     */
    @Volatile
    private var stopRequested = false

    fun stop() {
        stopRequested = true
        running = false
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun serve(app: Context) {
        var listen: LocalServerSocket? = null
        try {
            listen = LocalServerSocket(SOCKET_NAME)
            while (!stopRequested) {
                val con = runCatching { listen.accept() }.getOrNull() ?: continue
                // One thread per request: a location fix blocks for its
                // timeout, and a single handler would stall every other
                // guest command behind it.
                Thread({
                    handleConnection(con, app)
                }, "redterm-api-request").also { it.isDaemon = true; it.start() }
            }
        } catch (e: Exception) {
            // The listener is the whole bridge: a dead listener reads as
            // "the app is not running" on the guest side, so record why.
            if (!stopRequested) AppLog.e(app, "api-socket", "API socket stopped: ${e.message}")
        } finally {
            running = false
            runCatching { listen?.close() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleConnection(con: LocalSocket, app: Context) {
        try {
            if (con.peerCredentials.uid != app.applicationInfo.uid) {
                con.close()
                return
            }
            // The helper writes the request length as a LITTLE-endian
            // uint16, exactly like the response length this class writes
            // (see writeResponse). DataInputStream.readUnsignedShort is
            // big-endian, which reads an 84-byte request as 84*256 =
            // 21504 and rejects it as "too long": the one round every
            // command failed with.
            val input = con.inputStream
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) {
                con.close()
                return
            }
            val length = (b1 shl 8) or b0
            if (length > 16384) {
                writeResponse(con, "ERROR: request too long ($length bytes)\n")
                return
            }
            val buf = ByteArray(length)
            // A socket read may return fewer bytes than asked; read until
            // the request is complete or the peer hangs up.
            var filled = 0
            while (filled < length) {
                val n = input.read(buf, filled, length - filled)
                if (n < 0) throw java.io.EOFException("request truncated at $filled/$length bytes")
                filled += n
            }
            val result = when (val parsed = ApiRequestParser.parse(String(buf, Charsets.UTF_8))) {
                is ApiRequestParser.Result.Ok -> ApiActions.run(app, parsed.request)
                is ApiRequestParser.Result.Error -> parsed.message
            }
            writeResponse(con, result)
        } catch (e: Exception) {
            // An exception here would otherwise leave the guest holding a
            // closed pipe with no word: say what happened, to both sides.
            AppLog.e(app, "api-socket", "API request failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeResponse(con, "ERROR: ${e.javaClass.simpleName}: ${e.message}\n") }
        } finally {
            runCatching { con.close() }
        }
    }

    private fun writeResponse(con: LocalSocket, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = con.outputStream
        out.write(bytes.size and 0xff)
        out.write((bytes.size shr 8) and 0xff)
        out.write(bytes)
        out.flush()
    }
}
