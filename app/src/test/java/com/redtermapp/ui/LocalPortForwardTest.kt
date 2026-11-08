package com.redtermapp.ui

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPortForwardTest {

    @Test
    fun `spec validation rejects out-of-range ports and blank hosts`() {
        assertTrue(LocalPortForward.Spec(1234, "127.0.0.1", 8000).isValid)
        try { LocalPortForward.Spec(0, "127.0.0.1", 8000); throw AssertionError("fromPort 0 must throw") }
        catch (_: IllegalArgumentException) { }
        try { LocalPortForward.Spec(1234, "127.0.0.1", 70000); throw AssertionError("toPort 70000 must throw") }
        catch (_: IllegalArgumentException) { }
        try { LocalPortForward.Spec(1234, " ", 8000); throw AssertionError("blank host must throw") }
        catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `relay pipes bytes from the device port to the target`() {
        // A real echo server standing in for the guest service.
        val echoPort = freePort()
        val echoSocket = ServerSocket(echoPort)
        echoServer = echoSocket
        Thread({
            try {
                val s = echoSocket.accept()
                val inStream = s.getInputStream()
                val outStream = s.getOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    outStream.write(buf, 0, n)
                    outStream.flush()
                }
            } catch (_: Exception) {
            }
        }, "echo").start()

        val fromPort = freePort()
        val relay = LocalPortForward.LocalPortRelay(
            LocalPortForward.Spec(fromPort, "127.0.0.1", echoPort)
        )
        assertTrue(relay.start())
        try {
            val sock = Socket("127.0.0.1", fromPort)
            val payload = "hello guest".toByteArray()
            sock.getOutputStream().write(payload)
            sock.getOutputStream().flush()
            val inStream = sock.getInputStream()
            val resp = ByteArray(payload.size)
            var read = 0
            while (read < resp.size) {
                val n = inStream.read(resp, read, resp.size - read)
                if (n < 0) break
                read += n
            }
            assertTrue(
                "expected to read ${payload.size} echoed bytes, got $read",
                read == payload.size && resp.contentEquals(payload)
            )
        } finally {
            relay.close()
            echoServer?.close()
        }
    }

    @Volatile
    private var echoServer: ServerSocket? = null

    private fun freePort(): Int {
        val ss = ServerSocket(0)
        val p = ss.localPort
        ss.close()
        return p
    }
}
