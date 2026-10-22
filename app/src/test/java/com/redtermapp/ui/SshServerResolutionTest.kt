package com.redtermapp.ui

import com.redtermapp.ui.TerminalActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which description of a server a session should believe.
 *
 * An intent from the server list carries a hand-copied subset of the server's fields.
 * `useMosh` was left out of that copy once, and the result was a server whose card said
 * *mosh* opening over ssh — with the two facts on different screens, so neither looked
 * wrong on its own and nothing said to look for a mismatch. The saved record wins now, so
 * a forgotten field cannot do that again.
 */
class SshServerResolutionTest {

    private val saved = SshStore.Server(
        id = "s1", label = "Test", host = "h", port = 22, user = "u", useMosh = true,
    )

    /** What an intent that forgot to carry `useMosh` looks like. */
    private val carried = saved.copy(useMosh = false)

    @Test
    fun `the saved server wins over a hand-copied one`() {
        val resolved = TerminalActivity.resolveSshServer(saved, carried, keyId = null)
        assertTrue("the saved mosh preference must survive", resolved.useMosh)
        assertEquals("Test", resolved.label)
    }

    @Test
    fun `a server that is not saved falls back to what the intent carried`() {
        val resolved = TerminalActivity.resolveSshServer(null, carried, keyId = null)
        assertEquals("h", resolved.host)
        assertEquals(false, resolved.useMosh)
    }

    /**
     * Choosing a key for one connection is the whole reason the caller can pass one, so
     * that override still applies over the saved record.
     */
    @Test
    fun `a key override still wins`() {
        val bound = saved.copy(keyId = "k1")
        val resolved = TerminalActivity.resolveSshServer(bound, carried, keyId = "k2")
        assertEquals("k2", resolved.keyId)
        assertTrue("the mosh preference must not be lost by a key override", resolved.useMosh)
    }

    @Test
    fun `a null override leaves the saved key alone`() {
        val bound = saved.copy(keyId = "k1")
        assertEquals("k1", TerminalActivity.resolveSshServer(bound, carried, null).keyId)
    }
}
