package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelPersistenceTest {

    @Test
    fun `encode then decode round-trips the server ids`() {
        val ids = listOf("s1", "s2", "s3")
        assertEquals(ids, TunnelManager.decodeTunnelIds(TunnelManager.encodeTunnels(ids)))
        val none: List<String> = emptyList()
        assertEquals(none, TunnelManager.decodeTunnelIds(TunnelManager.encodeTunnels(none)))
    }

    @Test
    fun `a corrupt record is no tunnels, not a cold-start crash`() {
        assertTrue(TunnelManager.decodeTunnelIds(null).isEmpty())
        assertTrue(TunnelManager.decodeTunnelIds("").isEmpty())
        assertTrue(TunnelManager.decodeTunnelIds("garbage").isEmpty())
    }

    @Test
    fun `blank ids inside a well-formed record are dropped`() {
        val decoded = TunnelManager.decodeTunnelIds("""["s1",""]""")
        assertEquals(listOf("s1"), decoded)
    }
}
