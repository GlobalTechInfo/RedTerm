package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the round trip through JSON, which is what lets a killed session's
 * identity survive.
 *
 * Restoring reopens each session from these fields alone, so anything dropped
 * here is a session that comes back connected to the wrong host or the wrong key,
 * or does not come back at all.
 */
class SessionDescriptorTest {

    @Test
    fun `a local session round trips`() {
        val original = SessionDescriptor.Local("id-1", "Debian", "debian", "/root")
        val restored = SessionDescriptor.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `an ssh session round trips`() {
        val original = SessionDescriptor.Ssh(
            id = "id-2",
            label = "Work",
            serverId = "srv-1",
            host = "10.0.0.5",
            port = 2222,
            user = "deploy",
            keyId = "key-9"
        )
        val restored = SessionDescriptor.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `a list round trips in order`() {
        val list = listOf(
            SessionDescriptor.Local("a", "Alpine", "alpine", null),
            SessionDescriptor.Ssh("b", "Work", "s", "host", 22, "root", null),
            SessionDescriptor.Local("c", "Debian", "debian", "/tmp")
        )
        assertEquals(list, SessionDescriptor.listFromJson(SessionDescriptor.listToJson(list)))
    }

    /**
     * The kind is what everything branches on, so a record without a usable one is
     * dropped rather than guessed at: opening the wrong thing is worse than
     * forgetting it.
     */
    @Test
    fun `a record with an unknown kind is dropped`() {
        val json = org.json.JSONObject("""{"id":"x","label":"y","kind":"telnet"}""")
        assertNull(SessionDescriptor.fromJson(json))
    }

    @Test
    fun `a record with no id is dropped`() {
        val json = org.json.JSONObject("""{"label":"y","kind":"local","distro":"debian"}""")
        assertNull(SessionDescriptor.fromJson(json))
    }

    /** A legacy record must not be able to produce a session on port 0. */
    @Test
    fun `an out of range port is clamped rather than passed to ssh`() {
        val json = org.json.JSONObject(
            """{"id":"x","label":"y","kind":"ssh","serverId":"s","host":"h","port":0,"user":"root"}"""
        )
        val restored = SessionDescriptor.fromJson(json) as SessionDescriptor.Ssh
        assertEquals(1, restored.port)

        val absurd = org.json.JSONObject(
            """{"id":"x","label":"y","kind":"ssh","serverId":"s","host":"h","port":99999,"user":"root"}"""
        )
        assertEquals(65535, (SessionDescriptor.fromJson(absurd) as SessionDescriptor.Ssh).port)
    }

    @Test
    fun `a blank key id is stored as absent`() {
        val json = org.json.JSONObject(
            """{"id":"x","label":"y","kind":"ssh","serverId":"s","host":"h","port":22,"user":"root","keyId":""}"""
        )
        assertNull((SessionDescriptor.fromJson(json) as SessionDescriptor.Ssh).keyId)
    }

    /** Corrupt storage must not take the whole list down with it. */
    @Test
    fun `a corrupt list yields nothing instead of throwing`() {
        assertEquals(emptyList<SessionDescriptor>(), SessionDescriptor.listFromJson("{not json"))
        assertEquals(emptyList<SessionDescriptor>(), SessionDescriptor.listFromJson(""))
        assertEquals(emptyList<SessionDescriptor>(), SessionDescriptor.listFromJson(null))
    }

    @Test
    fun `one bad record does not discard the good ones`() {
        val raw = """[
            {"id":"a","label":"Alpine","kind":"local","distro":"alpine","startDir":""},
            {"id":"b","label":"Broken","kind":"nonsense"},
            {"id":"c","label":"Work","kind":"ssh","serverId":"s","host":"h","port":22,"user":"root","keyId":""}
        ]"""
        val parsed = SessionDescriptor.listFromJson(raw)
        assertEquals(listOf("a", "c"), parsed.map { it.id })
    }

    @Test
    fun `an ssh session with no server id falls back to the host`() {
        val json = org.json.JSONObject(
            """{"id":"x","label":"y","kind":"ssh","host":"example.com","port":22,"user":"root"}"""
        )
        assertEquals("example.com", (SessionDescriptor.fromJson(json) as SessionDescriptor.Ssh).serverId)
    }
}