package com.redtermapp.ui

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local port forward: a device-side port that relays TCP to the guest's
 * loopback. A distro service bound to `127.0.0.1:PORT` inside the proot
 * rootfs is reachable from the device's own apps and browser; this relay is
 * for when you want a *different* device port (e.g. a port that is free and
 * memorable) to reach that guest service, or to expose it while the guest is
 * running.
 *
 * [Spec] is pure and unit-tested; [LocalPortRelay] runs the actual sockets and
 * is exercised by a real local echo server in the test, so the pipe is proven
 * rather than assumed.
 */
object LocalPortForward {

    /** One relay: bytes from [fromPort] on the device go to [toHost]:[toPort]. */
    data class Spec(val fromPort: Int, val toHost: String, val toPort: Int) {
        init {
            require(fromPort in 1..65535) { "from port out of range" }
            require(toPort in 1..65535) { "to port out of range" }
            require(toHost.isNotBlank()) { "no target host" }
        }
        val isValid: Boolean get() = fromPort in 1..65535 && toPort in 1..65535 && toHost.isNotBlank()
    }

    class LocalPortRelay(private val spec: Spec) : AutoCloseable {
        private var server: ServerSocket? = null
        private var worker: Thread? = null
        private val running = AtomicBoolean(false)

        /** Bind the relay and start piping connections. Throws when the port is taken. */
        @Synchronized
        fun start(): Boolean {
            if (running.get()) return true
            val ss = try {
                ServerSocket(spec.fromPort)
            } catch (_: Exception) {
                return false
            }
            server = ss
            running.set(true)
            worker = Thread({
                while (running.get()) {
                    val client = try { ss.accept() } catch (_: Exception) { break }
                    Thread({
                        var target: Socket? = null
                        try {
                            target = Socket(InetAddress.getByName(spec.toHost), spec.toPort)
                            val t = target!!
                            // Client -> guest, and guest -> client, each on its own thread.
                            Thread({ pump(client.getInputStream(), t.getOutputStream()) }, "redterm-pf-up").start()
                            pump(t.getInputStream(), client.getOutputStream())
                        } catch (_: Exception) {
                        } finally {
                            runCatching { client.close() }
                            runCatching { target?.close() }
                        }
                    }, "redterm-pf-$client").start()
                }
            }, "redterm-pf-accept").also { it.isDaemon = true; it.start() }
            return true
        }

        /** The relay answers while its accept loop is up. A loop that breaks on
         *  a dead port reports false here, so a caller can tell a running relay
         *  from a dead one instead of trusting [start] returning true once. */
        fun isRunning(): Boolean = running.get() && worker?.isAlive == true

        private fun pump(in_: InputStream, out: java.io.OutputStream) {
            val buf = ByteArray(8192)
            while (true) {
                val n = in_.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        }

        @Synchronized
        override fun close() {
            running.set(false)
            runCatching { server?.close() }
            worker?.interrupt()
            worker = null
            server = null
        }
    }
}
