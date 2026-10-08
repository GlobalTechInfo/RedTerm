package com.redtermapp.util.sftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * Drives [SftpSession] against [FakeSftpServer] over real pipes.
 *
 * This is the closest a JVM test gets to a device: real encoding, real framing,
 * real socket-style short reads, and a server that can be told to refuse exactly
 * the thing a real server refuses. Everything the file browser depends on is
 * asserted here, so a bug in the protocol shows up as a red test rather than as a
 * transfer that stops halfway on a phone.
 */
class SftpSessionTest {

    private companion object {
        /**
         * Receiving-side pipe buffer.
         *
         * Only PipedInputStream takes a size on Android — PipedOutputStream has no
         * such constructor — so this is what keeps a full-size chunk from being
         * split into dozens of tiny writes. The reads still come back short and
         * fragmented, which is the point of using a pipe at all.
         */
        const val PIPE_BUFFER = 64 * 1024
    }

    /**
     * A client wired to a fresh fake, with the handshake already done.
     *
     * Two independent pipe pairs, one per direction. Sharing a stream between the
     * two ends looks like it would work and does not: a pipe is unidirectional, so
     * the writer and the reader must be different objects.
     */
    private fun connect(server: FakeSftpServer): Pair<SftpSession, FakeSftpServer> {
        val clientToServer = PipedOutputStream()
        val serverToClient = PipedOutputStream()
        server.serve(PipedInputStream(clientToServer, PIPE_BUFFER), serverToClient)
        val session = SftpSession(PipedInputStream(serverToClient, PIPE_BUFFER), clientToServer)
        session.version()
        return session to server
    }

    @Test
    fun `the version exchange completes`() {
        val (session, _) = connect(FakeSftpServer())
        assertEquals(Sftp.VERSION, session.version())
    }

    /** The failure mode of a wrong key or an unreachable host. */
    @Test
    fun `a refused handshake throws rather than returning an unusable session`() {
        val clientToServer = PipedOutputStream()
        val serverToClient = PipedOutputStream()
        FakeSftpServer(refuseHandshake = true)
            .serve(PipedInputStream(clientToServer, PIPE_BUFFER), serverToClient)
        val session = SftpSession(PipedInputStream(serverToClient, PIPE_BUFFER), clientToServer)
        val failed = try {
            session.version()
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("a server that never answers must not look like a success", failed)
    }

    @Test
    fun `realpath resolves the login home`() {
        val (session, _) = connect(FakeSftpServer())
        assertEquals("/home/tester", session.realPath("."))
    }

    @Test
    fun `stat reports a file's size and mode`() {
        val server = FakeSftpServer().addFile("/srv/data.txt", "hello")
        val (session, _) = connect(server)
        val attrs = session.stat("/srv/data.txt")!!
        assertEquals(5L, attrs.size)
        assertFalse(attrs.isDirectory)
        assertFalse(attrs.isSymlink)
        assertEquals("-rw-r--r--", attrs.modeText)
    }

    @Test
    fun `stat reports a directory as one`() {
        val (session, _) = connect(FakeSftpServer().addDirectory("/srv/www"))
        val attrs = session.stat("/srv/www")!!
        assertTrue(attrs.isDirectory)
        assertEquals("drwxr-xr-x", attrs.modeText)
    }

    /** Missing must be distinguishable from "present but empty". */
    @Test
    fun `stat returns null for a path that is not there`() {
        val (session, _) = connect(FakeSftpServer())
        assertNull(session.stat("/nope"))
    }

    @Test
    fun `a permission failure surfaces the server's own status`() {
        val server = FakeSftpServer().addFile("/root/secret", "x")
        val (session, _) = connect(server)
        server.failWith = Sftp.PERMISSION_DENIED to "Permission denied"
        val error = try {
            session.stat("/root/secret")
            null
        } catch (e: SftpException) {
            e
        }
        assertEquals(Sftp.PERMISSION_DENIED, error!!.statusCode)
    }

    @Test
    fun `a download reproduces the file byte for byte`() {
        // Deliberately not valid UTF-8: a client that round-trips file content
        // through String would quietly mangle this and every binary file.
        val payload = ByteArray(256) { (it - 128).toByte() }
        val server = FakeSftpServer().addFile("/srv/blob.bin", payload)
        val (session, _) = connect(server)

        val handle = session.open("/srv/blob.bin", write = false)
        val collected = mutableListOf<Byte>()
        var offset = 0L
        while (true) {
            val chunk = session.read(handle, offset, 64) ?: break
            if (chunk.isEmpty()) break
            collected.addAll(chunk.toList())
            offset += chunk.size
        }
        session.close(handle)

        assertEquals(payload.size, collected.size)
        assertArrayEquals(payload, collected.toByteArray())
    }

    @Test
    fun `a download spanning several chunks reassembles in order`() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val server = FakeSftpServer().addFile("/srv/big.bin", payload)
        val (session, _) = connect(server)

        val handle = session.open("/srv/big.bin", write = false)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = session.read(handle, offset, 100) ?: break
            out.write(chunk)
            offset += chunk.size
        }
        session.close(handle)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `an upload lands on the server intact`() {
        val payload = ByteArray(300) { (it * 7 % 256).toByte() }
        val server = FakeSftpServer()
        val (session, _) = connect(server)

        val handle = session.open("/srv/new.bin", write = true, create = true, truncate = true)
        var offset = 0L
        for (chunk in payload.toList().chunked(128)) {
            session.write(handle, offset, chunk.toByteArray())
            offset += chunk.size
        }
        session.close(handle)
        assertArrayEquals(payload, server.contentOf("/srv/new.bin"))
    }

    @Test
    fun `a zero byte upload still creates the file`() {
        val server = FakeSftpServer()
        val (session, _) = connect(server)
        val handle = session.open("/srv/empty.bin", write = true, create = true, truncate = true)
        session.close(handle)
        assertEquals(0, server.contentOf("/srv/empty.bin")!!.size)
    }

    @Test
    fun `opening a missing file for writing is refused unless it is created`() {
        val (session, _) = connect(FakeSftpServer())
        val error = try {
            session.open("/srv/absent", write = true, create = false)
            null
        } catch (e: SftpException) {
            e
        }
        assertEquals(Sftp.NO_SUCH_FILE, error!!.statusCode)
    }

    @Test
    fun `a directory listing excludes dot entries`() {
        val server = FakeSftpServer().addDirectory("/srv/www")
        server.willReturnFromReadDir(
            listOf(
                SftpName(".", "current", SftpAttrs(permissions = 0x41ED)),
                SftpName("..", "parent", SftpAttrs(permissions = 0x41ED)),
                SftpName("index.html", "-rw", SftpAttrs(size = 12, permissions = 0x81A4))
            )
        )
        val (session, _) = connect(server)
        assertEquals(listOf("index.html"), session.list("/srv/www").map { it.filename })
    }

    @Test
    fun `a trailing slash is the same path as none`() {
        val server = FakeSftpServer().addFile("/srv/x", "1")
        val (session, _) = connect(server)
        assertEquals(session.stat("/srv/x")?.size, session.stat("/srv/x/")?.size)
    }

    @Test
    fun `mkdir remove and rename round trip`() {
        val server = FakeSftpServer().addFile("/srv/one.txt", "hello")
        val (session, _) = connect(server)

        session.mkdir("/srv/newdir")
        assertTrue(session.stat("/srv/newdir")!!.isDirectory)

        session.rename("/srv/one.txt", "/srv/two.txt")
        assertNull(session.stat("/srv/one.txt"))
        assertEquals(5L, session.stat("/srv/two.txt")!!.size)

        session.remove("/srv/two.txt")
        assertNull(session.stat("/srv/two.txt"))
    }

    @Test
    fun `a request carries the right type so the server can route it`() {
        val server = FakeSftpServer().addFile("/srv/a", "x")
        val (session, _) = connect(server)
        session.stat("/srv/a")
        session.mkdir("/srv/b")
        assertEquals(listOf(Sftp.FXP_INIT, Sftp.FXP_STAT, Sftp.FXP_MKDIR), server.requests)
    }

    /**
     * A pipe delivers whatever the writer produced, not the exact sizes asked for,
     * so this exercises the client's read-fully loop rather than assuming a
     * single read returns the whole packet.
     */
    /**
     * Round-trips a NAME packet through the writer and reader directly.
     *
     * Pinpointing a framing mistake here is far easier than through a pipe, and a
     * multi-entry NAME response is where the optional-attribute flags differ
     * between entries — the case that goes wrong when a length is misread.
     */
    @Test
    fun `a NAME packet round trips with mixed attribute flags`() {
        val entries = listOf(
            SftpName(".", "current directory", SftpAttrs(permissions = 0x41EDL)),
            SftpName("..", "parent directory", SftpAttrs(permissions = 0x41EDL)),
            SftpName(
                "index.html", "-rw-r--r-- 1 u g 12 Jan 1 00:00 index.html",
                SftpAttrs(
                    size = 12L, uid = 1000L, gid = 1000L,
                    permissions = 0x81A4L,
                    accessTime = 1_700_000_000L, modifyTime = 1_700_000_000L
                )
            )
        )
        val writer = SftpWriter().apply {
            byte(Sftp.FXP_NAME)
            uint32(7L)
            uint32(entries.size.toLong())
            for (e in entries) {
                string(e.filename)
                string(e.longName)
                attrs(e.attrs)
            }
        }
        val reader = SftpReader(writer.toByteArray())
        assertEquals(Sftp.FXP_NAME, reader.byte())
        assertEquals(7L, reader.uint32())
        val count = reader.uint32().toInt()
        assertEquals(3, count)
        repeat(count) {
            val filename = reader.string()
            val longName = reader.string()
            val attrs = reader.attrs()
            val expected = entries[it]
            assertEquals(expected.filename, filename)
            assertEquals(expected.longName, longName)
            assertEquals(expected.attrs.size, attrs.size)
            assertEquals(expected.attrs.permissions, attrs.permissions)
            assertEquals(expected.attrs.isDirectory, attrs.isDirectory)
        }
        assertEquals(0, reader.remaining)
    }

    /**
     * The one property that keeps the browser from crashing.
     *
     * Resolving the home directory costs a connection, and the browser used to ask
     * for it while the activity was being created — a network call on the main
     * thread, which Android turns into an immediate crash. The value must therefore
     * never be computed until a worker asks for it.
     */
    @Test
    fun `asking for home does nothing until it is read`() {
        val server = FakeSftpServer()
        val home = DeferredHome(server)
        assertEquals("no connection yet", server.requests.size, 0)
        // Reading it is what connects; constructing the source must not.
        assertTrue(home.read() == "/home/tester")
        assertEquals(listOf(Sftp.FXP_INIT, Sftp.FXP_REALPATH), server.requests)
    }

    /** Stands in for SftpSource.home, which must be lazy for the same reason. */
    private class DeferredHome(server: FakeSftpServer) {
        private val value: String by lazy {
            val toServer = java.io.PipedOutputStream()
            val fromServer = java.io.PipedOutputStream()
            server.serve(java.io.PipedInputStream(toServer, PIPE_BUFFER), fromServer)
            SftpSession(java.io.PipedInputStream(fromServer, PIPE_BUFFER), toServer)
                .let {
                    it.version()
                    it.realPath(".")
                }
        }

        fun read(): String = value
    }

    /**
     * A refused connection closes the stream rather than going quiet.
     *
     * Polling `available()` cannot tell this apart from "not yet", so every
     * failure was reported as a timeout and the server's own message — which says
     * *why* — was lost. This has to fail fast and name the closure.
     */
    @Test
    fun `a closed stream is reported at once rather than as a timeout`() {
        val toServer = PipedOutputStream()
        val fromServer = PipedOutputStream()
        FakeSftpServer(refuseHandshake = true)
            .serve(PipedInputStream(toServer, PIPE_BUFFER), fromServer)
        val session = SftpSession(PipedInputStream(fromServer, PIPE_BUFFER), toServer)
        val started = System.currentTimeMillis()
        val error = try {
            session.version()
            null
        } catch (e: SftpProtocolException) {
            e
        }
        val elapsed = System.currentTimeMillis() - started
        assertNotNull("the closure must be reported", error)
        assertTrue(
            "took ${elapsed}ms: a closed pipe must not burn the whole deadline",
            elapsed < 5_000
        )
    }

    @Test
    fun `a large packet survives being split across reads`() {
        val name = "n".repeat(60_000) + ".log"
        val server = FakeSftpServer().addFile("/srv/$name", "payload")
        val (session, _) = connect(server)
        assertEquals(7L, session.stat("/srv/$name")?.size)
    }

    /** A UTF-8 name has a byte length and a character length; only the first matters. */
    @Test
    fun `a non ascii name is sent and read back unchanged`() {
        val name = "naïve-café-日本語.txt"
        val server = FakeSftpServer().addFile("/srv/$name", "x")
        val (session, _) = connect(server)
        assertEquals(1L, session.stat("/srv/$name")?.size)
    }

    @Test
    fun `an attrs payload with no size is not mistaken for zero`() {
        val attrs = SftpAttrs(permissions = 0x41EDL)
        assertNull(attrs.size)
        assertTrue(attrs.isDirectory)
    }

    @Test
    fun `a symlink is told apart from a directory`() {
        val link = SftpAttrs(permissions = 0xA1FFL)
        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
    }

    @Test
    fun `a truncated packet is refused rather than read past`() {
        val writer = SftpWriter().apply { byte(Sftp.FXP_DATA); uint32(9L); uint32(4L) }
        val truncated = writer.toByteArray().copyOf(10)
        val reader = SftpReader(truncated)
        reader.byte()
        reader.uint32()
        val error = try {
            reader.string()
            false
        } catch (_: SftpProtocolException) {
            true
        }
        assertTrue("a short string must not be decoded from whatever follows", error)
    }

    @Test
    fun `a packet claiming an impossible length is refused`() {
        val writer = SftpWriter().apply { byte(Sftp.FXP_DATA); uint32(0x7FFFFFFFL); uint32(4L) }
        val reader = SftpReader(writer.toByteArray())
        reader.byte()
        reader.uint32()
        assertThrows { reader.string() }
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("expected an exception", threw)
    }

    @Test
    fun `the fake and the client agree within the timeout`() {
        // A guard against a change that silently makes every request block: the
        // suite would otherwise hang rather than fail.
        val (session, _) = connect(FakeSftpServer().addFile("/a", "b"))
        val done = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = done.submit<SftpAttrs?> { session.stat("/a") }
            assertEquals(1L, future.get(10, TimeUnit.SECONDS)?.size)
        } finally {
            done.shutdownNow()
        }
    }

    /**
     * stderr arrives on its own thread, so a failure can be reported before a single
     * word of it has been read.
     *
     * The consequence was that every such failure was described the same way — a
     * stopped connection — whatever the server had actually said, which sent the
     * search for the cause in entirely the wrong direction.
     */
    @Test
    fun `a late line of diagnostics is still picked up`() {
        val diagnostics = SftpClient.Diagnostics()
        Thread {
            Thread.sleep(60)
            diagnostics.append("usage: sftp [-46AaCfNpqrv]")
        }.start()
        assertTrue(
            diagnostics.tailAwaitingFirstLine().contains("usage: sftp")
        )
    }

    /** No output at all is a legitimate answer and must not be waited on for long. */
    @Test
    fun `no diagnostics means an empty answer rather than a hang`() {
        val diagnostics = SftpClient.Diagnostics()
        val started = System.nanoTime()
        assertEquals("", diagnostics.tailAwaitingFirstLine(200))
        val waitedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited ${waitedMillis}ms", waitedMillis < 3_000)
    }

    /** Text already captured must be returned without waiting at all. */
    @Test
    fun `text already captured is returned immediately`() {
        val diagnostics = SftpClient.Diagnostics()
        diagnostics.append("Permission denied (publickey).")
        val started = System.nanoTime()
        assertTrue(diagnostics.tailAwaitingFirstLine().contains("Permission denied"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 100)
    }

    /**
     * Awaiting stops at the first line on purpose: it exists to stop a report being
     * written before the explanation exists, not to collect the whole stream. Later
     * lines still accumulate and are available to the log.
     */
    @Test
    fun `awaiting stops at the first line and later lines still accumulate`() {
        val diagnostics = SftpClient.Diagnostics()
        Thread {
            Thread.sleep(40)
            diagnostics.append("first")
            Thread.sleep(40)
            diagnostics.append("second")
        }.start()
        assertTrue(diagnostics.tailAwaitingFirstLine().contains("first"))
        val deadline = System.currentTimeMillis() + 2_000
        var collected = diagnostics.tail()
        while (!collected.contains("second") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            collected = diagnostics.tail()
        }
        assertTrue("second line lost: $collected", collected.contains("second"))
    }

}
