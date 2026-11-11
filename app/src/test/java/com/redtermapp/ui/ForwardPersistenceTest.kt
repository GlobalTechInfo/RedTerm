package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardPersistenceTest {

    private val a = ForwardPersistence.SavedForward("s1", 9000, "127.0.0.1", 5432)
    private val b = ForwardPersistence.SavedForward("s2", 8080, "db.internal", 6379)

    @Test
    fun `encode then decode round-trips the list`() {
        assertEquals(listOf(a, b), ForwardPersistence.decode(ForwardPersistence.encode(listOf(a, b))))
        val none: List<ForwardPersistence.SavedForward> = emptyList()
        assertEquals(none, ForwardPersistence.decode(ForwardPersistence.encode(none)))
    }

    @Test
    fun `a missing toHost falls back to loopback`() {
        val json = """[{"serverId":"s1","fromPort":9000,"toPort":5432}]"""
        val decoded = ForwardPersistence.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("127.0.0.1", decoded[0].toHost)
    }

    @Test
    fun `an out-of-range port or a blank server id is dropped, the rest kept`() {
        val json = """[
            {"serverId":"s1","fromPort":9000,"toHost":"127.0.0.1","toPort":5432},
            {"serverId":"s2","fromPort":70000,"toHost":"127.0.0.1","toPort":6379},
            {"serverId":"","fromPort":80,"toHost":"127.0.0.1","toPort":8080}
        ]"""
        val decoded = ForwardPersistence.decode(json)
        assertEquals(listOf(a), decoded)
    }

    @Test
    fun `a corrupt record is no forwards, not an exception`() {
        assertTrue(ForwardPersistence.decode(null).isEmpty())
        assertTrue(ForwardPersistence.decode("not json at all").isEmpty())
        assertTrue(ForwardPersistence.decode("[{") .isEmpty())
    }
}
