package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection options, and what they mean to ssh.
 *
 * Every mistake here is silent in the same way: ssh connects, or refuses to, with no
 * hint that the settings were wrong. A mistyped option name is ignored entirely by
 * some versions and fatal in others, so each is checked as a pair.
 */
class SshLaunchOptionsTest {

    private fun server(
        keepAliveSeconds: Int = 0,
        keepAliveCount: Int = 0,
        compress: Boolean = false,
        forwardAgent: Boolean = false,
        auth: SshStore.Auth = SshStore.Auth.Keys,
        jump: SshStore.Jump? = null,
        forwards: List<SshStore.Forward> = emptyList()
    ) = SshStore.Server(
        id = "id-1",
        label = "Oracle",
        host = "129.146.25.232",
        port = 22,
        user = "ubuntu",
        keepAliveSeconds = keepAliveSeconds,
        keepAliveCount = keepAliveCount,
        compress = compress,
        forwardAgent = forwardAgent,
        auth = auth,
        jump = jump,
        forwards = forwards
    )

    private fun optionsFor(server: SshStore.Server) = SshLaunchOptions.serverOptions(server)

    // ------------------------------------------------------------- keepalives

    /**
     * A wake lock keeps the phone awake; this keeps the connection awake. They are
     * separate problems and a session needs both — NAT drops an idle flow regardless
     * of whether the device is busy.
     */
    @Test
    fun `a keepalive interval becomes the ssh option`() {
        assertTrue(optionsFor(server(keepAliveSeconds = 30)).contains("ServerAliveInterval=30"))
    }

    @Test
    fun `no keepalive means no interval option`() {
        assertFalse(
            optionsFor(server(keepAliveSeconds = 0))
                .any { it.startsWith("ServerAliveInterval") }
        )
    }

    @Test
    fun `a negative interval is treated as off`() {
        assertFalse(
            optionsFor(server(keepAliveSeconds = -5))
                .any { it.startsWith("ServerAliveInterval") }
        )
    }

    @Test
    fun `a keepalive count rides along with an interval`() {
        assertTrue(
            optionsFor(server(keepAliveSeconds = 30, keepAliveCount = 4))
                .contains("ServerAliveCountMax=4")
        )
    }

    /**
     * A count on its own does nothing, and emitting it alone would suggest that it
     * does — so it is only ever sent alongside an interval.
     */
    @Test
    fun `a count without an interval is not sent`() {
        assertFalse(
            optionsFor(server(keepAliveSeconds = 0, keepAliveCount = 4))
                .any { it.startsWith("ServerAliveCountMax") }
        )
    }

    // --------------------------------------------------------------- passwords

    @Test
    fun `keys only refuses password authentication`() {
        assertTrue(optionsFor(server(auth = SshStore.Auth.Keys)).contains("PasswordAuthentication=no"))
        assertTrue(
            optionsFor(server(auth = SshStore.Auth.Keys))
                .contains("KbdInteractiveAuthentication=no")
        )
    }

    @Test
    fun `a password server allows both password prompts`() {
        assertTrue(optionsFor(server(auth = SshStore.Auth.Password)).contains("PasswordAuthentication=yes"))
        assertTrue(
            optionsFor(server(auth = SshStore.Auth.Password))
                .contains("KbdInteractiveAuthentication=yes")
        )
    }

    /**
     * A password-only server that also offered public key would try every key on the
     * device first and burn the server's MaxAuthTries before ever asking.
     */
    @Test
    fun `a password-only server does not offer keys`() {
        assertTrue(optionsFor(server(auth = SshStore.Auth.Password)).contains("PubkeyAuthentication=no"))
    }

    @Test
    fun `a server that accepts either still offers keys`() {
        assertFalse(
            optionsFor(server(auth = SshStore.Auth.Any))
                .any { it == "PubkeyAuthentication=no" }
        )
    }

    // ------------------------------------------------------------------- flags

    @Test
    fun `compression is a flag, not an option`() {
        assertTrue(SshLaunchOptions.forServer(server(compress = true)).contains("-C"))
        assertFalse(SshLaunchOptions.forServer(server(compress = false)).contains("-C"))
    }

    @Test
    fun `agent forwarding is a flag, not an option`() {
        assertTrue(SshLaunchOptions.forServer(server(forwardAgent = true)).contains("-A"))
        assertFalse(SshLaunchOptions.forServer(server(forwardAgent = false)).contains("-A"))
    }

    // -------------------------------------------------------------- jump host

    @Test
    fun `a jump host becomes -J with user and host`() {
        val args = SshLaunchOptions.jumpArgs(server(jump = SshStore.Jump("bastion.example.com", user = "ops")))
        assertEquals(listOf("-J", "ops@bastion.example.com"), args)
    }

    /** A non-default jump port has to be carried, or the connection goes to 22. */
    @Test
    fun `a jump port is carried when it is not the default`() {
        val args = SshLaunchOptions.jumpArgs(server(jump = SshStore.Jump("bastion", port = 2222)))
        assertEquals(listOf("-J", "bastion:2222"), args)
    }

    @Test
    fun `a default jump port is left out`() {
        val args = SshLaunchOptions.jumpArgs(server(jump = SshStore.Jump("bastion", port = 22)))
        assertEquals(listOf("-J", "bastion"), args)
    }

    @Test
    fun `no jump host means no -J`() {
        assertTrue(SshLaunchOptions.jumpArgs(server(jump = null)).isEmpty())
    }

    /** A jump host with no host is not a jump host, and `-J` would break the parse. */
    @Test
    fun `a jump host with no host is ignored`() {
        assertTrue(SshLaunchOptions.jumpArgs(server(jump = SshStore.Jump(""))).isEmpty())
    }

    // ---------------------------------------------------------------- forwards

    /**
     * ssh's convention: three colon-separated parts are a local forward, four are a
     * remote one. What is stored is what is passed, so a spec that ssh understands
     * keeps working.
     */
    @Test
    fun `a local forward uses -L`() {
        assertEquals(
            listOf("-L", "8080:db.internal:5432"),
            SshLaunchOptions.forwardArgs(SshStore.Forward("8080:db.internal:5432"))
        )
    }

    @Test
    fun `a remote forward uses -R`() {
        assertEquals(
            listOf("-R", "db.internal:5432:localhost:5432"),
            SshLaunchOptions.forwardArgs(SshStore.Forward("db.internal:5432:localhost:5432"))
        )
    }

    /** A single-colon spec is still local, which is the common localhost case. */
    @Test
    fun `a single-port spec is local`() {
        assertTrue(
            SshLaunchOptions.forwardArgs(SshStore.Forward("5353")).first() == "-L"
        )
    }

    @Test
    fun `enabled forwards are applied and disabled ones are not`() {
        val args = SshLaunchOptions.forServer(
            server(
                forwards = listOf(
                    SshStore.Forward("8080:db:5432", enabled = true),
                    SshStore.Forward("9090:web:80", enabled = false)
                )
            )
        )
        assertTrue(args.containsAll(listOf("-L", "8080:db:5432")))
        assertFalse(args.contains("9090:web:80"))
    }

    /** A one-shot command should not open tunnels it will never use. */
    @Test
    fun `forwards can be left off for one-shot commands`() {
        val args = SshLaunchOptions.forServer(
            server(forwards = listOf(SshStore.Forward("8080:db:5432"))),
            withForwards = false
        )
        assertFalse(args.contains("8080:db:5432"))
    }

    @Test
    fun `a blank forward is ignored`() {
        val args = SshLaunchOptions.forServer(server(forwards = listOf(SshStore.Forward(""))))
        assertFalse(args.contains("-L"))
    }

    // ------------------------------------------------------------ whole argv

    @Test
    fun `the target comes last so later flags are not read as a command`() {
        val args = SshLaunchOptions.forServer(
            server(keepAliveSeconds = 30, compress = true, forwardAgent = true)
        )
        assertEquals("ubuntu@129.146.25.232", args.last())
    }

    /** Host key checking is never weakened by any of these settings. */
    @Test
    fun `host key checking stays on whatever else is asked for`() {
        val args = SshLaunchOptions.forServer(server(keepAliveSeconds = 30, compress = true))
        assertTrue(args.containsAll(listOf("UserKnownHostsFile=${SshLaunchOptions.KNOWN_HOSTS}")))
        assertTrue(args.contains("StrictHostKeyChecking=accept-new"))
    }

    /**
     * The exact argv for a plain key-based server.
     *
     * Written out in full because this is the line every other feature adds to, and
     * because the password options are sent even here: ssh's own default is to allow
     * password authentication, so a keys-only server has to say `no` explicitly or
     * ssh will fall back to asking for one — which, in a terminal session, produces a
     * password prompt for a server that never wanted one.
     */
    @Test
    fun `a plain key server produces exactly the expected arguments`() {
        assertEquals(
            listOf(
                "-p", "22",
                "-o", "UserKnownHostsFile=${SshLaunchOptions.KNOWN_HOSTS}",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no",
                "ubuntu@129.146.25.232"
            ),
            SshLaunchOptions.forServer(server())
        )
    }
}