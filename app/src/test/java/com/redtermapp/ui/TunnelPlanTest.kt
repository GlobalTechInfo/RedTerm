package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelPlanTest {

    private fun fwd(spec: String, enabled: Boolean = true) = SshStore.Forward(spec, enabled)

    private fun server(id: String, forwards: List<SshStore.Forward>) =
        SshStore.Server(
            id = id,
            label = "L$id",
            host = "h$id",
            port = 22,
            user = "u",
            forwards = forwards
        )

    @Test
    fun `only servers with an enabled usable forward are eligible`() {
        val on = server("a", listOf(fwd("8080:db:5432")))
        val off = server("b", listOf(fwd("8080:db:5432", enabled = false)))
        val blank = server("c", listOf(fwd("   ")))
        val none = server("d", emptyList())

        val eligible = TunnelPlan.serversWithTunnels(listOf(on, off, blank, none))
        assertEquals(listOf("a"), eligible.map { it.id })
    }

    @Test
    fun `active specs keep only enabled usable ones in stored order`() {
        val s = server(
            "a",
            listOf(
                fwd("2222:h:22"),
                fwd("bad spec"), // whitespace is not usable
                fwd("3333:h:22", enabled = false),
                fwd("4444:h:22")
            )
        )
        assertEquals(listOf("2222:h:22", "4444:h:22"), TunnelPlan.activeSpecs(s))
    }

    @Test
    fun `allSpecs lists every usable forward regardless of enabled`() {
        val s = server(
            "a",
            listOf(fwd("2222:h:22"), fwd("bad spec"), fwd("4444:h:22", enabled = false))
        )
        assertEquals(listOf("2222:h:22", "4444:h:22"), TunnelPlan.allSpecs(s))
        assertTrue(TunnelPlan.serversWithTunnels(emptyList()).isEmpty())
    }
}
