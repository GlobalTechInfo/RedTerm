package com.redtermapp.util.sftp

import com.redtermapp.ui.SshStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line for an SFTP connection.
 *
 * Every failure here is silent in the worst way: ssh authenticates, the channel
 * opens, and then the wrong thing happens at the far end.
 */
class SftpCommandTest {

    private val server = SshStore.Server(
        id = "id-1", label = "Oracle", host = "129.146.25.232", port = 22, user = "ubuntu"
    )
    private val key = "/root/.ssh/id_ed25519_oracle"

    private fun command(passphrase: String? = null) =
        SftpClient.buildCommand(server, listOf(key), passphrase)

    /**
     * The one that cost the most time.
     *
     * Without `-s`, the trailing "sftp" is a program to *run* on the remote host,
     * and the program called sftp there is the client, which prints its own usage
     * and exits. The trace then shows a perfectly good authentication and a
     * connection closed for no visible reason, because the missing flag names
     * nothing that appears in it.
     */
    @Test
    fun `the sftp subsystem is requested rather than run as a remote command`() {
        val text = command()
        assertTrue("no -s in: $text", text.contains(" -s "))
        // Subsystem first, so it is a flag and not something ssh reads as a target.
        assertTrue("subsystem flag is not first: $text", text.startsWith("/bin/ssh -s "))
    }

    @Test
    fun `the subsystem name follows the target`() {
        val text = command()
        // The target is quoted like every other argument, so a host with spaces or
        // shell metacharacters cannot split into two arguments.
        assertTrue("wrong ending: $text", text.endsWith("'ubuntu@129.146.25.232' sftp"))
    }

    /** `-s` is a flag, so it has to precede `--`, which ends the options. */
    @Test
    fun `the subsystem flag comes before the end of options`() {
        val text = command()
        val dashS = text.indexOf(" -s ")
        val endOfOptions = text.indexOf(" -- ")
        assertTrue("no -s: $text", dashS > 0)
        assertTrue("no end of options: $text", endOfOptions > 0)
        assertTrue("-s after -- in: $text", dashS < endOfOptions)
    }

    /** A host beginning with a dash must not be taken for a flag. */
    @Test
    fun `options are terminated before the target`() {
        val dashed = server.copy(host = "-oProxyCommand=evil")
        val text = SftpClient.buildCommand(dashed, listOf(key), null)
        assertTrue("target not quoted: $text", text.contains(" -- "))
        assertTrue(text.contains("ubuntu@-oProxyCommand=evil"))
    }

    /**
     * BatchMode is what stops ssh asking, so it is only correct while there is no
     * passphrase to supply — and ssh treats it as a hard refusal, not a preference.
     */
    @Test
    fun `an unprotected key gets BatchMode`() {
        assertTrue(command(null).contains("BatchMode=yes"))
    }

    @Test
    fun `a passphrase key does not get BatchMode`() {
        assertFalse(command("secret").contains("BatchMode=yes"))
    }

    /** The phrase travels in the environment, so it must not be in the command. */
    @Test
    fun `the passphrase never appears in the command line`() {
        val text = command("hunter2")
        assertFalse("passphrase leaked into: $text", text.contains("hunter2"))
        assertFalse(text.contains("SSH_ASKPASS"))
        assertFalse(text.contains("REDTERM_KEY_PASSPHRASE"))
    }

    @Test
    fun `the port and the key are passed`() {
        val text = command()
        assertTrue("port missing: $text", text.contains("-p '22'"))
        assertTrue("key missing: $text", text.contains("'-i' '$key'"))
    }

    /** A space in a host must not split it into two arguments. */
    @Test
    fun `arguments with spaces are quoted`() {
        val spaced = server.copy(host = "my host", user = "some user")
        val text = SftpClient.buildCommand(spaced, listOf(key), null)
        // One quoted argument, so the space inside the host cannot end it early.
        assertTrue("target not quoted as one: $text", text.contains("'some user@my host'"))
    }
}