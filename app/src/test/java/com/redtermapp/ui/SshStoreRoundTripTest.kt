package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field a saved server has to survive being written and read back.
 *
 * The missing half of this pair produces no error: the value reads back as its
 * default, so a setting the user changed is silently ignored and there is nothing
 * anywhere to notice. Each field is set to something that is *not* its default, so a
 * dropped field shows up as a failed test rather than as a coincidence.
 */
class SshStoreRoundTripTest {

    private fun full(
        id: String = "id-1",
        auth: SshStore.Auth = SshStore.Auth.Any,
        jump: SshStore.Jump? = SshStore.Jump("bastion.example.com", 2222, "ops")
    ) = SshStore.Server(
        id = id,
        label = "Oracle",
        host = "129.146.25.232",
        port = 22,
        user = "ubuntu",
        keyId = "key-1",
        useMosh = true,
        keepAliveSeconds = 30,
        keepAliveCount = 4,
        compress = true,
        forwardAgent = true,
        jump = jump,
        auth = auth,
        passwordRef = "pw-1",
        verifyHostKey = true,
        forwards = listOf(
            SshStore.Forward("8080:db.internal:5432", enabled = true),
            SshStore.Forward("db.internal:5432:localhost:5432", enabled = false)
        )
    )

    @Test
    fun `every field survives a round trip`() {
        val server = full()
        val decoded = SshStore.decode(SshStore.encode(listOf(server)))
        assertEquals(1, decoded.size)
        assertEquals(server, decoded.first())
    }

    @Test
    fun `a jump host survives`() {
        val decoded = SshStore.decode(SshStore.encode(listOf(full()))).single()
        assertEquals(SshStore.Jump("bastion.example.com", 2222, "ops"), decoded.jump)
    }

    @Test
    fun `no jump host survives as no jump host`() {
        val decoded = SshStore.decode(SshStore.encode(listOf(full(jump = null)))).single()
        assertNull(decoded.jump)
    }

    @Test
    fun `forwards keep their order and their enabled flag`() {
        val decoded = SshStore.decode(SshStore.encode(listOf(full()))).single()
        assertEquals(2, decoded.forwards.size)
        assertEquals("8080:db.internal:5432", decoded.forwards[0].spec)
        assertTrue(decoded.forwards[0].enabled)
        assertFalse(decoded.forwards[1].enabled)
    }

    @Test
    fun `no forwards survives as no forwards`() {
        val server = full().copy(forwards = emptyList())
        assertTrue(SshStore.decode(SshStore.encode(listOf(server))).single().forwards.isEmpty())
    }

    @Test
    fun `key authentication survives`() {
        val decoded = SshStore.decode(
            SshStore.encode(listOf(full(auth = SshStore.Auth.Keys)))
        ).single()
        assertEquals(SshStore.Auth.Keys, decoded.auth)
    }

    @Test
    fun `password authentication survives`() {
        val decoded = SshStore.decode(
            SshStore.encode(listOf(full(auth = SshStore.Auth.Password)))
        ).single()
        assertEquals(SshStore.Auth.Password, decoded.auth)
    }

    @Test
    fun `an unset password reference does not come back as an empty one`() {
        val server = full().copy(passwordRef = null)
        assertNull(SshStore.decode(SshStore.encode(listOf(server))).single().passwordRef)
    }

    // ------------------------------------------------------------ older records

    /**
     * A record written before any of these fields existed has to load rather than
     * throw, and everything it did carry has to be intact.
     */
    @Test
    fun `a record with no new fields loads with every default`() {
        val legacy = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("id", "old")
                put("label", "Legacy")
                put("host", "example.com")
                put("port", 2222)
                put("user", "root")
            }
        ).toString()
        val decoded = SshStore.decode(legacy)
        assertEquals(1, decoded.size)
        val server = decoded.first()
        assertEquals("Legacy", server.label)
        assertEquals(2222, server.port)
        assertEquals(0, server.keepAliveSeconds)
        assertFalse(server.compress)
        assertEquals(SshStore.Auth.Keys, server.auth)
        assertFalse(server.verifyHostKey)
        assertNull(server.jump)
        assertTrue(server.forwards.isEmpty())
    }

    /** An unknown auth name must not throw and take the whole list with it. */
    @Test
    fun `an unrecognised auth name falls back to keys`() {
        val odd = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("host", "example.com")
                put("auth", "SOMETHING_NEW")
            }
        ).toString()
        assertEquals(SshStore.Auth.Keys, SshStore.decode(odd).single().auth)
    }

    @Test
    fun `a record with no host is dropped rather than saved as unreachable`() {
        val broken = org.json.JSONArray().put(
            org.json.JSONObject().apply { put("label", "No host") }
        ).toString()
        assertTrue(SshStore.decode(broken).isEmpty())
    }

    @Test
    fun `unreadable storage yields no servers instead of throwing`() {
        assertTrue(SshStore.decode("not json at all").isEmpty())
    }

    @Test
    fun `a jump host with no host is not usable`() {
        assertFalse(SshStore.Jump("").usable)
        assertTrue(SshStore.Jump("bastion").usable)
    }

    /** Whitespace in a spec would let it become two arguments. */
    @Test
    fun `a forward with whitespace is not usable`() {
        assertFalse(SshStore.Forward("8080:db:5432 -oProxyCommand=evil").usable)
        assertFalse(SshStore.Forward("").usable)
        assertTrue(SshStore.Forward("8080:db:5432").usable)
    }

    /**
     * `useMosh` on its own, because a field added to the reader and not the writer has
     * no symptom anywhere: the server loads, the toggle renders, and every session opens
     * over ssh with nothing saying the preference was ever lost. That is the same shape
     * of bug as the `encrypted` flag on key entries, and the full-equality assertion
     * above only covers it because this test happens to set every field.
     */
    @Test
    fun `the mosh preference survives a round trip`() {
        val on = SshStore.Server("id", "label", "host", 22, "user", useMosh = true)
        val off = SshStore.Server("id", "label", "host", 22, "user", useMosh = false)
        assertTrue(SshStore.decode(SshStore.encode(listOf(on))).first().useMosh)
        assertFalse(SshStore.decode(SshStore.encode(listOf(off))).first().useMosh)
    }

    /**
     * A record written before mosh existed has no such key, and must not come back
     * claiming the server wants it.
     */
    @Test
    fun `a server saved before mosh existed does not come back wanting it`() {
        // Hand-written rather than produced by encode(), because the current encoder
        // always writes the field -- that is the fix -- and so cannot stand in for a
        // record written before it existed.
        val withoutTheKey =
            """[{"id":"id","label":"label","host":"host","port":22,"user":"user"}]"""
        val decoded = SshStore.decode(withoutTheKey)
        assertEquals(1, decoded.size)
        assertFalse(decoded.first().useMosh)
    }
}
