package com.redtermapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshUdpProbeTest {

    @Test
    fun `a refused port means the UDP path reached the host`() {
        assertEquals(MoshUdpProbe.Verdict.UDP_OPEN, MoshUdpProbe.classify(MoshUdpProbe.Signal.PortUnreachable))
    }

    @Test
    fun `no answer means the UDP path is likely dropped`() {
        assertEquals(MoshUdpProbe.Verdict.UDP_BLOCKED, MoshUdpProbe.classify(MoshUdpProbe.Signal.NoAnswer))
    }

    @Test
    fun `an unrun probe is inconclusive, not a verdict`() {
        assertEquals(MoshUdpProbe.Verdict.INCONCLUSIVE, MoshUdpProbe.classify(MoshUdpProbe.Signal.Unknown))
    }

    @Test
    fun `the probe port is outside mosh-server's default range`() {
        // mosh-server binds 60000-61000 by default; probing one of those while a
        // session holds it would read the open port as closed and lie.
        assertTrue(
            "the probe port must not fall in mosh's 60000-61000 range",
            MoshUdpProbe.CLOSED_PROBE_PORT < 60000 || MoshUdpProbe.CLOSED_PROBE_PORT > 61000
        )
    }

    @Test
    fun `each verdict has its own remedy, and they are not identical`() {
        val open = MoshUdpProbe.suggestion(MoshUdpProbe.Verdict.UDP_OPEN, "host")
        val blocked = MoshUdpProbe.suggestion(MoshUdpProbe.Verdict.UDP_BLOCKED, "host")
        val unsure = MoshUdpProbe.suggestion(MoshUdpProbe.Verdict.INCONCLUSIVE, "host")
        assertNotEquals(open, blocked)
        assertNotEquals(blocked, unsure)
        assertNotEquals(open, unsure)
        // the blocked path names the tunnel that fixes it
        assertTrue(blocked.contains("SSH"))
        assertTrue(open.contains("SSH"))
    }
}
