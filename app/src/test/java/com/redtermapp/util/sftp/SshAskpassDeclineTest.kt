package com.redtermapp.util.sftp

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which keys may be asked about, and when.
 *
 * Every folder listing and every transfer is a separate connection, so anything that
 * re-asks about a key on each one turns into being nagged for ever — the user
 * dismisses the dialog and the next tap brings it straight back.
 */
class SshAskpassDeclineTest {

    private val key = "id_ed25519_oracle"

    /** The store is process-wide, so each test starts from a clean one. */
    @Before
    fun reset() = SshAskpass.forgetAll()

    @Test
    fun `a key with a phrase held is never asked about again`() {
        SshAskpass.remember(key, "phrase-one")
        assertEquals("phrase-one", SshAskpass.cached(key))
        assertFalse("a held phrase must not be askable", SshAskpass.isDeclined(key))
    }

    @Test
    fun `declining a key stops it being asked about`() {
        SshAskpass.decline(key)
        assertTrue(SshAskpass.isDeclined(key))
    }

    /** Answering once clears the decline: the phrase works, so stop asking. */
    @Test
    fun `supplying a phrase clears a previous decline`() {
        SshAskpass.decline(key)
        SshAskpass.remember(key, "phrase-two")
        assertFalse(SshAskpass.isDeclined(key))
        assertEquals("phrase-two", SshAskpass.cached(key))
    }

    @Test
    fun `a decline is forgotten with everything else`() {
        SshAskpass.remember(key, "phrase")
        SshAskpass.decline(key)
        SshAskpass.forgetAll()
        assertNull(SshAskpass.cached(key))
        assertFalse(SshAskpass.isDeclined(key))
    }

    /** One key being declined must not affect another. */
    @Test
    fun `a decline applies only to the key it was given for`() {
        SshAskpass.remember(key, "phrase")
        SshAskpass.decline("id_ed25519_work")
        assertTrue(SshAskpass.isDeclined("id_ed25519_work"))
        assertFalse(SshAskpass.isDeclined(key))
        assertEquals("phrase", SshAskpass.cached(key))
    }

    @Test
    fun `a key with nothing held and nothing declined has no phrase`() {
        SshAskpass.decline("id_ed25519_never_seen")
        assertNull(SshAskpass.cached("id_ed25519_never_seen"))
    }
}
