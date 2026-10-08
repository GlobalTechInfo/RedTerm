package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the argument list handed to the bundled OpenSSH binaries.
 *
 * These call [SshLaunchOptions] itself. An earlier version of this file
 * reimplemented the builder as a private helper and asserted against that copy,
 * which meant it would have kept passing after the real code was changed — or
 * deleted.
 */
class SshOptionsTest {

    private fun server(
        port: Int = 22,
        user: String = "root",
        host: String = "example.com",
        keyId: String? = null
    ) = SshStore.Server(
        id = "srv",
        label = "Example",
        host = host,
        port = port,
        user = user,
        keyId = keyId
    )

    private fun options(
        port: Int = 22,
        user: String = "root",
        host: String = "example.com",
        hasKey: Boolean = false
    ): List<String> = SshLaunchOptions.forServer(
        server = server(port, user, host),
        identities = if (hasKey) listOf("/root/.ssh/id_ed25519_work") else emptyList()
    )

    @Test
    fun `port is always passed`() {
        assertTrue(options(port = 2222).contains("2222"))
    }

    @Test
    fun `host key checking is enabled and never silently dropped`() {
        val args = options()
        assertTrue(args.contains("StrictHostKeyChecking=accept-new"))
        assertTrue(args.any { it.startsWith("UserKnownHostsFile=") })
    }

    @Test
    fun `user and host are combined into one target`() {
        assertEquals("root@example.com", options(user = "root", host = "example.com").last())
    }

    @Test
    fun `a blank user omits the user prefix`() {
        assertEquals("example.com", options(user = "  ", host = "example.com").last())
    }

    @Test
    fun `an ipv6 host is not mangled by the user prefix`() {
        assertEquals("root@2001:db8::1", options(host = "2001:db8::1").last())
    }

    @Test
    fun `identity is only passed when a key exists`() {
        assertFalse(options(hasKey = false).any { it == "-i" })
        assertTrue(options(hasKey = true).contains("-i"))
        assertTrue(options(hasKey = true).contains("IdentitiesOnly=yes"))
    }

    @Test
    fun `the target is always the final argument`() {
        for (port in listOf(22, 2222)) {
            for (key in listOf(false, true)) {
                val args = options(port = port, hasKey = key)
                assertTrue(args.last().contains("example.com"))
            }
        }
    }

    /**
     * ssh treats everything after the host as a remote command, so an option
     * placed past the target is parsed as part of the command line instead of
     * being applied. sftp and scp pass extra options for exactly this reason.
     */
    @Test
    fun `extra options for the transfer tools stay ahead of the target`() {
        val args = SshLaunchOptions.forTarget(
            host = "example.com",
            port = 22,
            user = "root",
            extraOptions = listOf("BatchMode=yes")
        )
        assertTrue(args.contains("BatchMode=yes"))
        assertEquals("root@example.com", args.last())
    }

    @Test
    fun `no identity means no -i and no IdentitiesOnly`() {
        val args = SshLaunchOptions.forTarget("example.com", 22, "root")
        assertFalse(args.contains("-i"))
        assertFalse(args.contains("IdentitiesOnly=yes"))
    }

    /**
     * A server with no key bound still authenticates, using the keys on the
     * device. Each is offered in turn so ssh can try them, rather than naming
     * none and letting ssh guess names it will not find under a label scheme.
     */
    @Test
    fun `several identities are each offered`() {
        val args = SshLaunchOptions.options(listOf("/k/a", "/k/b", "/k/c"))
        // The value that follows each -i, in order.
        val offered = args.withIndex()
            .filter { it.value == "-i" }
            .map { args[it.index + 1] }
        assertEquals(listOf("/k/a", "/k/b", "/k/c"), offered)
        assertTrue(args.contains("IdentitiesOnly=yes"))
    }

    /**
     * ssh counts every failed public-key attempt against MaxAuthTries, which
     * defaults to 6 on many servers. Offering more keys than that gets the
     * connection dropped before the right one is tried.
     */
    @Test
    fun `the identity list is capped so MaxAuthTries is not exceeded`() {
        val many = (1..20).map { "/k/key$it" }
        val args = SshLaunchOptions.options(many)
        assertEquals(SshLaunchOptions.MAX_IDENTITIES, args.count { it == "-i" })
    }

    @Test
    fun `the known hosts file is the one inside the client rootfs`() {
        assertEquals(
            "/root/.ssh/known_hosts",
            options().first { it.startsWith("UserKnownHostsFile=") }.substringAfter('=')
        )
    }
}