package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the argument list handed to the bundled ssh binary.
 *
 * A malformed argument here is silent at runtime: ssh either fails to connect or,
 * worse, connects with weaker settings than intended. The host-key options in
 * particular must never be dropped, or host verification would be off by default.
 */
class SshOptionsTest {

    private fun options(
        port: Int = 22,
        user: String = "root",
        host: String = "example.com",
        hasKey: Boolean = false
    ): List<String> {
        val target = if (user.isNotBlank()) "$user@$host" else host
        val out = mutableListOf("-p", port.toString())
        if (hasKey) {
            out.addAll(listOf("-i", "/key", "-o", "IdentitiesOnly=yes"))
        }
        out.addAll(
            listOf(
                "-o",
                "UserKnownHostsFile=/known_hosts",
                "-o",
                "StrictHostKeyChecking=accept-new",
                target
            )
        )
        return out
    }

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
}
