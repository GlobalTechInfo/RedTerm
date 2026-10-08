package com.redtermapp.util.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The three operations added after the browser worked end to end.
 *
 * `FXP_SETSTAT` had been on the wire since the beginning and was simply never called,
 * so permissions on a remote file could be read but not changed — which reads as the
 * server refusing when the real answer is that nothing was ever sent.
 */
class SftpProtocolExtrasTest {

    private fun connect(
        server: FakeSftpServer
    ): Pair<SftpSession, FakeSftpServer> {
        val clientToServer = PipedOutputStream()
        val serverToClient = PipedOutputStream()
        server.serve(PipedInputStream(clientToServer, 1 shl 20), serverToClient)
        val session = SftpSession(PipedInputStream(serverToClient, 1 shl 20), clientToServer)
        session.version()
        return session to server
    }

    // ------------------------------------------------------------------ setstat

    @Test
    fun `permissions can be set`() {
        val server = FakeSftpServer().addFile("/a.txt", "x")
        val (session, _) = connect(server)
        session.setPermissions("/a.txt", 0x1ED)
        assertEquals(0x1EDL, server.permissions["/a.txt"])
    }

    /**
     * SETSTAT replaces the mode rather than merging into it, so the file-type bits
     * must not be sent. Sending `0755` whole would leave the file looking like a
     * directory to the kernel.
     */
    @Test
    fun `the file type bits are not sent`() {
        val server = FakeSftpServer().addFile("/a.txt", "x")
        val (session, _) = connect(server)
        // 0x8000 is the directory bit, as `ls -l` would show it.
        session.setPermissions("/a.txt", 0x8000L or 0x1B5)
        assertEquals(0x1B5L, server.permissions["/a.txt"])
    }

    @Test
    fun `a refused setstat says so`() {
        val server = FakeSftpServer().addFile("/a.txt", "x")
        val (session, _) = connect(server)
        server.failWith = Sftp.PERMISSION_DENIED to "Permission denied"
        val failure = runCatching { session.setPermissions("/a.txt", 0x1B5) }.exceptionOrNull()
        assertNotNull("a refusal must not be silent", failure)
        assertEquals(Sftp.PERMISSION_DENIED, (failure as SftpException).statusCode)
    }

    // ------------------------------------------------------------------ symlink

    @Test
    fun `a symlink is created at the path asked for`() {
        val server = FakeSftpServer()
        val (session, _) = connect(server)
        session.symlink("/etc/hosts", "/tmp/link")
        assertEquals("/etc/hosts", server.links["/tmp/link"])
    }

    /**
     * The arguments are in the order the server expects: the link's own path first,
     * then what it points at. Reversed, the link is created somewhere the user never
     * named and points at nothing.
     */
    @Test
    fun `the link path comes before the target`() {
        val server = FakeSftpServer()
        val (session, _) = connect(server)
        session.symlink("/target/path", "/link/path")
        assertTrue("keys are link paths", server.links.containsKey("/link/path"))
        assertEquals("/target/path", server.links["/link/path"])
    }

    @Test
    fun `a refused symlink says so`() {
        val (session, server) = connect(FakeSftpServer())
        server.failWith = Sftp.PERMISSION_DENIED to "Permission denied"
        assertNotNull(runCatching { session.symlink("/t", "/l") }.exceptionOrNull())
    }

    // ------------------------------------------------------------------ statvfs

    @Test
    fun `free space comes back as bytes`() {
        val server = FakeSftpServer()
        server.blockSize = 4096
        server.blockCount = 1000
        server.freeBlocks = 250
        val (session, _) = connect(server)
        val stats = session.statvfs("/")
        assertNotNull(stats)
        assertEquals(4096L * 1000, stats!!.totalBytes)
        assertEquals(4096L * 250, stats.availableBytes)
    }

    @Test
    fun `the extension name is the one openssh answers to`() {
        assertEquals("statvfs@openssh.com", Sftp.EXT_STATVFS)
    }

    /**
     * A server without statvfs answers "unsupported". That must read as "cannot say",
     * not as a failure: refusing to show a folder because a free-space figure is
     * unavailable would be absurd.
     */
    @Test
    fun `a server without statvfs gives null rather than an error`() {
        val server = FakeSftpServer()
        server.statvfsSupported = false
        val (session, _) = connect(server)
        assertNull(session.statvfs("/"))
    }

    /** Blocks reserved for root are not usable by this user. */
    @Test
    fun `available space is preferred over free space`() {
        val stats = StatVfs(
            blockSize = 100,
            blockCount = 1000,
            freeBlocks = 900,
            availableBlocks = 300
        )
        assertEquals(100_000L, stats.totalBytes)
        assertEquals(30_000L, stats.availableBytes)
    }

    @Test
    fun `a refused statvfs path gives null`() {
        val (session, server) = connect(FakeSftpServer())
        server.failWith = Sftp.NO_SUCH_FILE to "No such file"
        assertNull(session.statvfs("/gone"))
    }
}