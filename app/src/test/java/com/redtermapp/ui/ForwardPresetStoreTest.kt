package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardPresetStoreTest {

    private val p1 = ForwardPresetStore.Preset(9000, "127.0.0.1", 5432)
    private val p2 = ForwardPresetStore.Preset(8080, "db.internal", 6379)

    @Test
    fun `encode then decode round-trips per-server presets`() {
        val presets = mapOf("s1" to listOf(p1, p2), "s2" to listOf(p2))
        val decoded = ForwardPresetStore.decode(ForwardPresetStore.encode(presets))
        assertEquals(presets, decoded)
        val none: Map<String, List<ForwardPresetStore.Preset>> = emptyMap()
        assertEquals(none, ForwardPresetStore.decode(ForwardPresetStore.encode(none)))
    }

    @Test
    fun `out-of-range ports and blank hosts are dropped entry by entry`() {
        val json = """
            {
              "s1": [
                {"fromPort":9000,"toHost":"127.0.0.1","toPort":5432},
                {"fromPort":70000,"toHost":"127.0.0.1","toPort":80},
                {"fromPort":80,"toHost":"db.internal"}
              ]
            }
        """.trimIndent()
        val decoded = ForwardPresetStore.decode(json)
        assertEquals(listOf(p1), decoded["s1"])
    }

    @Test
    fun `a missing host falls back to loopback, a corrupt record to nothing`() {
        val json = """{"s1":[{"fromPort":9000,"toPort":5432}]}"""
        assertEquals(
            listOf(ForwardPresetStore.Preset(9000, "127.0.0.1", 5432)),
            ForwardPresetStore.decode(json)!!["s1"]
        )
        assertTrue(ForwardPresetStore.decode(null).isEmpty())
        assertTrue(ForwardPresetStore.decode("{not json").isEmpty())
    }
}
