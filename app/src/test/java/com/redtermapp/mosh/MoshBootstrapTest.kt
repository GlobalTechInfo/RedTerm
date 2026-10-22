package com.redtermapp.mosh

import com.redtermapp.ui.SshStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bootstrap parser.
 *
 * The output being parsed is produced by two programs on a machine this code does not
 * control, so the cases below are the ones that have to survive whatever a real host
 * emits: a banner interleaved with the lines that matter, CRLF from a Windows-side ssh, and
 * the two ways a host can be missing `mosh-server` while still exiting cleanly.
 */
class MoshBootstrapTest {

    private fun ready(output: String): MoshBootstrap.Endpoint =
        (MoshBootstrap.parse(output) as MoshBootstrap.Outcome.Ready).endpoint

    private fun unavailable(output: String): MoshBootstrap.Outcome.Unavailable =
        MoshBootstrap.parse(output) as MoshBootstrap.Outcome.Unavailable

    private fun server(): SshStore.Server =
        SshStore.Server(id = "s", label = "Test", host = "h", port = 22, user = "u")

    @Test
    fun `reads address and port from a real startup transcript`() {
        val output = """
            MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22
            MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ
        """.trimIndent()
        val endpoint = ready(output)
        assertEquals("192.168.1.9", endpoint.ip)
        assertEquals(60031, endpoint.port)
        assertEquals("1S0XWpHR3/UazstuCCN5eQ", endpoint.key)
    }

    @Test
    fun `keeps working when a banner is printed between the two lines`() {
        // This is the real shape of the output: mosh-server prints its licence banner and
        // its "detached, pid = N" line either side of the connect line.
        val output = """
            MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22
            MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ

            mosh-server (mosh 1.4.0) [build mosh 1.4.0]
            Copyright 2012 Keith Winstein <mosh-devel@mit.edu>
            [mosh-server detached, pid = 78225]
        """.trimIndent()
        assertEquals("192.168.1.9", ready(output).ip)
    }

    @Test
    fun `tolerates carriage returns from a host with a Windows ssh client`() {
        val output = "MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22\r\n" +
            "MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ\r\n"
        assertEquals(60031, ready(output).port)
    }

    /**
     * The exit host, not the host the user typed.
     *
     * `SSH_CONNECTION`'s fourth field is the far end's own view of its address, which is
     * the interface `mosh-server` bound to. Taking the third field instead — or the
     * hostname the user typed — sends UDP somewhere the session does not exist, and the
     * symptom is a connection that authenticates and then never draws anything.
     */
    @Test
    fun `takes the server side of the connection rather than the client side`() {
        val output = "MOSH SSH_CONNECTION 10.0.0.5 55555 198.51.100.7 22\n" +
            "MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ\n"
        assertEquals("198.51.100.7", ready(output).ip)
    }

    @Test
    fun `a connect line with no address is refused rather than guessed`() {
        // Guessing the hostname here would produce a session that looks authenticated and
        // is in fact addressed to nothing.
        val output = "MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ\n"
        val result = unavailable(output)
        assertTrue(
            "reason should name the address, got: ${result.reason}",
            result.reason.contains("address"),
        )
    }

    @Test
    fun `ssh connection without a connect line means mosh-server is missing`() {
        // A host with no mosh-server installed exits cleanly having printed nothing.
        // parse returns null so the caller can say "not installed" rather than invent a
        // port.
        val output = "bash: mosh-server: command not found\n"
        assertEquals(null, MoshBootstrap.parse(output))
    }

    @Test
    fun `an address without a connect line is not a usable endpoint`() {
        val output = "MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22\n"
        assertEquals(null, MoshBootstrap.parse(output))
    }

    @Test
    fun `a truncated connect line is not accepted as a key`() {
        // The port is followed by a base64 key of a known length. A short line is a
        // truncated stream, and passing half a key to the session produces a handshake
        // that fails for no stated reason.
        val output = "MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22\n" +
            "MOSH CONNECT 60031 1S0XWpHR3\n"
        assertEquals(null, MoshBootstrap.parse(output))
    }

    /**
     * A held secret changes the command in exactly one way: BatchMode goes, so ssh is
     * allowed to ask for the passphrase or password. What must never happen is the
     * secret appearing in the command itself — the launcher script is written to app
     * storage and logged on the way past when a call fails, so a secret there is a
     * secret on disk. It travels in the askpass environment instead, which
     * `SshAskpass`'s own tests cover by running it through a real shell.
     */
    @Test
    fun `a held passphrase drops BatchMode and never reaches the command line`() {
        val command = MoshBootstrap.buildCommand(server(), emptyList(), passphrase = "s3cret!")
        assertFalse("ssh must be allowed to ask: $command", command.contains("BatchMode"))
        assertFalse("the passphrase must not travel in the command: $command",
            command.contains("s3cret"))
    }

    @Test
    fun `no passphrase keeps BatchMode, so nothing can hang on a prompt`() {
        val command = MoshBootstrap.buildCommand(server(), emptyList(), passphrase = null)
        assertTrue("unattended runs must fail fast: $command", command.contains("BatchMode=yes"))
    }

    @Test
    fun `a line that merely contains a connect line is not accepted`() {
        val output = "MOSH SSH_CONNECTION 10.0.0.5 55555 192.168.1.9 22\n" +
            "remote: MOSH CONNECT 60031 1S0XWpHR3/UazstuCCN5eQ\n"
        assertEquals(null, MoshBootstrap.parse(output))
    }

    /**
     * Compression is never negotiated for a mosh bootstrap.
     *
     * The SSH channel carries one connect line and then closes — the session proper is
     * UDP with its own encryption — so `-C` compresses nothing, and a server that does
     * not support zlib answers it with "Compression not supported, disabling." on the
     * very stream the bootstrap parses. Copied from the SFTP command line, where it
     * belongs, and it showed up on the terminal instead of a shell.
     */
    @Test
    fun `compression is never negotiated, even for a server that saves it`() {
        val compressed = SshStore.Server(
            id = "s", label = "Test", host = "h", port = 22, user = "u", compress = true,
        )
        val command = MoshBootstrap.buildCommand(compressed, emptyList(), passphrase = null)
        assertFalse("the bootstrap must not pass -C: $command", command.contains(" -C"))
    }

    /**
     * The address ssh connected to is the one we can send UDP to.
     *
     * `mosh-server` binds `$SSH_CONNECTION`'s server-side field. Behind a 1:1 NAT — a
     * public IP on any major cloud — the ssh session arrives on the instance's internal
     * interface, so that field is the *private* address, which nothing outside the VPC
     * can reach. Measured on a real Oracle instance: the server bound `10.0.0.79`
     * while the client was trying to reach it from the internet.
     */
    @Test
    fun `the destination is the resolved host, not the address the server reported`() {
        assertEquals(
            "129.146.25.232",
            MoshBootstrap.udpDestination("129.146.25.232", "10.0.0.79"),
        )
    }

    /** Same host, reached over an overlay rather than the public internet. */
    @Test
    fun `an overlay address is used when that is what ssh connected to`() {
        assertEquals(
            "100.68.49.100",
            MoshBootstrap.udpDestination("100.68.49.100", "100.68.49.100"),
        )
    }

    /**
     * Only a host that will not resolve on the device at all falls back, and then to the
     * only address left — which is the one thing that has ever been reported.
     */
    @Test
    fun `an unresolvable host falls back to the reported address`() {
        assertEquals("10.0.0.79", MoshBootstrap.udpDestination(null, "10.0.0.79"))
        assertEquals("10.0.0.79", MoshBootstrap.udpDestination("", "10.0.0.79"))
    }

    @Test
    fun `neither address available is still unavailable`() {
        assertNull(MoshBootstrap.udpDestination(null, null))
    }
}
