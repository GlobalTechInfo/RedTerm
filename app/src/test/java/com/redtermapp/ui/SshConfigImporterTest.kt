package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading an `ssh_config`.
 *
 * Two rules shape everything here. First obtained wins, because that is what ssh
 * itself does and a config written by someone who knows ssh depends on it. And a
 * `Match` or `Include` block is read past rather than applied, because applying it to
 * every host would silently give each of them settings meant for one.
 */
class SshConfigImporterTest {

    private fun parse(text: String) = SshConfigImporter.parse(text)

    @Test
    fun `a host block becomes a server`() {
        val parsed = parse(
            """
            Host db
              HostName db.internal
              User postgres
              Port 5432
            """.trimIndent()
        )
        val server = parsed.servers.single()
        assertEquals("db", server.label)
        assertEquals("db.internal", server.host)
        assertEquals("postgres", server.user)
        assertEquals(5432, server.port)
    }

    @Test
    fun `a host with no hostname uses its own name`() {
        val parsed = parse("Host example.com\n  User root")
        assertEquals("example.com", parsed.servers.single().host)
    }

    @Test
    fun `defaults are applied when the config omits them`() {
        val server = parse("Host a\n  HostName 10.0.0.1").servers.single()
        assertEquals(22, server.port)
        assertEquals("", server.user)
        assertEquals(0, server.keepAliveSeconds)
        assertFalse(server.compress)
    }

    /** Several names on one line share the settings, and all of them are kept. */
    @Test
    fun `several names on one Host line each become a server`() {
        val parsed = parse("Host db prod\n  HostName 10.0.0.5")
        assertEquals(2, parsed.servers.size)
        assertEquals(listOf("db", "prod"), parsed.servers.map { it.label })
        assertTrue(parsed.servers.all { it.host == "10.0.0.5" })
    }

    /**
     * A wildcard cannot be a server: it is a rule, and offering "match everything" as
     * a thing to connect to would be nonsense.
     */
    @Test
    fun `a wildcard host is skipped and reported`() {
        val parsed = parse("Host *\n  ServerAliveInterval 60\n\nHost real\n  HostName r.example")
        assertEquals(listOf("real"), parsed.servers.map { it.label })
        assertEquals(listOf("*"), parsed.skippedPatterns)
    }

    @Test
    fun `a negated host is skipped`() {
        val parsed = parse("Host !secret\n  User x")
        assertTrue(parsed.servers.isEmpty())
        assertEquals(listOf("!secret"), parsed.skippedPatterns)
    }

    /** Comments are the common case in a hand-written config. */
    @Test
    fun `comments and blank lines are ignored`() {
        val parsed = parse(
            """
            # my hosts

            Host a    # the first one
              HostName a.internal   # resolves in the office

            """.trimIndent()
        )
        assertEquals(listOf("a"), parsed.servers.map { it.label })
    }

    /** A '#' is legal inside a host name, so only a whitespace-then-# comments. */
    @Test
    fun `a hash inside a host pattern is not a comment`() {
        val parsed = parse("Host db#1\n  HostName db1.internal")
        assertEquals(listOf("db#1"), parsed.servers.map { it.label })
    }

    @Test
    fun `compression and agent forwarding are read`() {
        val parsed = parse(
            """
            Host a
              HostName a.internal
              Compression yes
              ForwardAgent no
            """.trimIndent()
        )
        val server = parsed.servers.single()
        assertTrue(server.compress)
        assertFalse(server.forwardAgent)
    }

    @Test
    fun `keepalive is read`() {
        val server = parse("Host a\n  HostName x\n  ServerAliveInterval 45").servers.single()
        assertEquals(45, server.keepAliveSeconds)
    }

    @Test
    fun `a jump host is read in ssh notation`() {
        val server = parse("Host a\n  HostName x\n  ProxyJump ops@bastion:2222").servers.single()
        assertEquals(SshStore.Jump("bastion", 2222, "ops"), server.jump)
    }

    @Test
    fun `a jump host with no port uses the default`() {
        val server = parse("Host a\n  HostName x\n  ProxyJump bastion").servers.single()
        assertEquals(SshStore.Jump("bastion", 22, ""), server.jump)
    }

    /**
     * A conditional block applied to every host would give each of them settings
     * meant for one, so it is passed over and counted rather than honoured.
     */
    @Test
    fun `a Match block is passed over rather than applied`() {
        val parsed = parse(
            """
            Host db
              HostName db.internal

            Match host bastion
              User ops
            """.trimIndent()
        )
        val server = parsed.servers.single()
        assertEquals("", server.user)
        assertEquals(1, parsed.unrecognised.size)
    }

    @Test
    fun `Include is passed over rather than followed`() {
        val parsed = parse("Include ~/.ssh/conf.d/*\n\nHost a\n  HostName a")
        assertEquals(listOf("a"), parsed.servers.map { it.label })
        assertEquals(1, parsed.unrecognised.size)
    }

    /** Settings the app does not model must not stop the import. */
    @Test
    fun `an unmodelled setting does not stop the import`() {
        val parsed = parse(
            """
            Host a
              HostName a.internal
              ServerAliveCountMax 3
              TCPKeepAlive yes
              AddressFamily inet
            """.trimIndent()
        )
        assertEquals(1, parsed.servers.size)
        assertTrue(parsed.unrecognised.isEmpty())
    }

    /**
     * First obtained wins. A config written by someone who knows ssh depends on this,
     * and reversing it would connect them to the wrong host.
     */
    @Test
    fun `an earlier Host block wins over a later one`() {
        val parsed = parse(
            """
            Host a
              HostName first.internal
            Host a
              HostName second.internal
            """.trimIndent()
        )
        assertEquals(listOf("first.internal", "second.internal"), parsed.servers.map { it.host })
    }

    @Test
    fun `case of keywords does not matter`() {
        val parsed = parse("HOST a\n  hostname a.internal\n  PORT 2222")
        assertEquals(2222, parsed.servers.single().port)
    }

    @Test
    fun `a blank config imports nothing`() {
        val parsed = parse("   \n\n# nothing here\n")
        assertTrue(parsed.servers.isEmpty())
    }

    /** A Host with nothing usable must not produce a server pointing nowhere. */
    @Test
    fun `a Host with no name at all is ignored`() {
        val parsed = parse("Host\n  User root")
        assertTrue(parsed.servers.isEmpty())
    }

    @Test
    fun `an unparseable port falls back to the default`() {
        val server = parse("Host a\n  HostName x\n  Port not-a-number").servers.single()
        assertEquals(22, server.port)
    }
}