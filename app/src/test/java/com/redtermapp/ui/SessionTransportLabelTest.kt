package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the title and the notification say about a session's transport.
 *
 * The whole point is that it reports the transport in use rather than the one that was
 * asked for, because the interesting case is a server that wanted mosh and did not get
 * it. `useMosh` is written by `prepareMosh` and nowhere else, so it is the truth about
 * what connected — but only for as long as nothing else writes it, which is exactly the
 * kind of assumption a test should pin down.
 */
class SessionTransportLabelTest {

    @Test
    fun `an ssh session says which transport it is on`() {
        val mosh = SessionDescriptor.Ssh(
            id = "1", label = "Test", serverId = "s", host = "h", port = 22, user = "u",
            useMosh = true,
        )
        val ssh = mosh.copy(useMosh = false)
        assertTrue("mosh session should say mosh", mosh.transportLabel.contains("mosh"))
        assertTrue("ssh session should say ssh", ssh.transportLabel.contains("ssh"))
    }

    /** A distro session is not a choice the user made, so it is not labelled. */
    @Test
    fun `a distro session is not labelled`() {
        val local = SessionDescriptor.Local(id = "1", label = "Arch", distro = "arch")
        assertEquals("", local.transportLabel)
    }

    /** The suffix is a suffix, not the whole title. */
    @Test
    fun `the label is a decoration and never replaces the name`() {
        val mosh = SessionDescriptor.Ssh(
            id = "1", label = "Test", serverId = "s", host = "h", port = 22, user = "u",
            useMosh = true,
        )
        val shown = "Test" + mosh.transportLabel
        assertTrue(shown.startsWith("Test"))
        assertTrue(shown != "Test")
    }
}
