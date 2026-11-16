package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decision-making of the multi-forward manager.
 *
 * The ssh path itself (a real `ssh -N -L` process) is not exercisable on the
 * host: it needs the bundled binary and a server. What is checked here is the
 * argument that ends up on that command line and the labels the screen shows,
 * because those are the two things that are wrong in a way no reading would
 * catch — and the relay path already proves itself in [LocalPortForwardTest].
 */
class ForwardManagerTest {

    private val server = SshStore.Server(
        id = "s1", label = "MyServer", host = "example.com", port = 2222, user = "alice"
    )

    @Test
    fun `a local relay forward carries no ssh argument`() {
        val spec = ForwardManager.LocalSpec(9000, "127.0.0.1", 8080)
        assertNull(ForwardManager.forwardArg(spec))
        assertTrue(!spec.viaSsh())
    }

    @Test
    fun `an ssh forward carries the -L argument in ssh notation`() {
        val spec = ForwardManager.SshSpec(9000, "127.0.0.1", 5432, server, emptyList())
        assertEquals("-L9000:127.0.0.1:5432", ForwardManager.forwardArg(spec))
        assertTrue(spec.viaSsh())
    }

    @Test
    fun `an ssh forward names the host when the target is not loopback`() {
        val spec = ForwardManager.SshSpec(9000, "db.internal", 5432, server, listOf("/id_ed25519_x"))
        assertEquals("-L9000:db.internal:5432", ForwardManager.forwardArg(spec))
        assertEquals(listOf("/id_ed25519_x"), spec.identities)
    }

    @Test
    fun `the mapping is shown without a prefix and the summary names the target`() {
        val a = ForwardManager.SshSpec(9001, "127.0.0.1", 5432, server, emptyList())
        val b = ForwardManager.SshSpec(9002, "127.0.0.1", 6379, server, emptyList())
        val local = ForwardManager.LocalSpec(9000, "127.0.0.1", 8080)
        // What the card shows next to the origin label: no name, just the mapping.
        assertEquals("9001 -> 127.0.0.1:5432", a.forward)
        assertEquals("9002 -> 127.0.0.1:6379", b.forward)
        assertEquals("9000 -> 127.0.0.1:8080", local.forward)
        // What the log carries: the target's name up front.
        assertEquals("MyServer: 9001 -> 127.0.0.1:5432", a.summary)
        assertEquals("device: 9000 -> 127.0.0.1:8080", local.summary)
    }

    @Test
    fun `a resume plan keeps only the forwards whose server still exists`() {
        val saved = listOf(
            ForwardPersistence.SavedForward("s1", 9001, "127.0.0.1", 5432),
            ForwardPersistence.SavedForward("gone", 9002, "127.0.0.1", 6379),
            ForwardPersistence.SavedForward("s1", 9003, "127.0.0.1", 3306)
        )
        val plan = ForwardManager.planResume(saved, mapOf("s1" to server))
        assertEquals(listOf(saved[0], saved[2]), plan)
    }
}
