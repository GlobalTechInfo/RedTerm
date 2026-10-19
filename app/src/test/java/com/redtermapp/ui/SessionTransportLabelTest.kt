package com.redtermapp.ui

import com.redtermapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the title and the notification say about a session's transport.
 *
 * The whole point is that it reports the transport in use rather than the one that was
 * asked for, because the interesting case is a server that wanted mosh and did not get
 * it. `useMosh` is written by `prepareMosh` and nowhere else, so it is the truth about
 * what connected — but only for as long as nothing else writes it, which is exactly the
 * kind of assumption a test should pin down.
 *
 * The label is a string *resource*, not a string built inside the descriptor. It was
 * built inside the descriptor first, which put two pieces of hardcoded English into the
 * title and the notification with no route to `strings.xml` at all.
 */
class SessionTransportLabelTest {

    private fun ssh(useMosh: Boolean) = SessionDescriptor.Ssh(
        id = "1", label = "Test", serverId = "s", host = "h", port = 22, user = "u",
        useMosh = useMosh,
    )

    @Test
    fun `an ssh session names the transport it is on`() {
        assertEquals(R.string.session_transport_mosh, transportLabelRes(ssh(useMosh = true)))
        assertEquals(R.string.session_transport_ssh, transportLabelRes(ssh(useMosh = false)))
    }

    /** A distro session is not a choice the user made, so it is not labelled. */
    @Test
    fun `a distro session is not labelled`() {
        val local = SessionDescriptor.Local(id = "1", label = "Arch", distro = "arch")
        assertEquals(0, transportLabelRes(local))
    }

    /** Nothing to label is not the same as "ssh": a null descriptor must not claim either. */
    @Test
    fun `an absent session is not labelled`() {
        assertEquals(0, transportLabelRes(null))
    }

    /** The two transports must never collapse onto the same string. */
    @Test
    fun `the two transports are distinguishable`() {
        assertNotEquals(
            transportLabelRes(ssh(useMosh = true)),
            transportLabelRes(ssh(useMosh = false)),
        )
    }
}
