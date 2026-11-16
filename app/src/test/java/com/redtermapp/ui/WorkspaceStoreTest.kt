package com.redtermapp.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace's JSON shape is what outlives an app update, so it is
 * round-tripped here exactly the way the key store's is: a writer field that
 * goes missing produces no error anywhere, only a workspace that silently
 * loses its servers. The reader must also tolerate a record that predates a
 * field entirely.
 */
class WorkspaceStoreTest {

    private val sample = WorkspaceStore.Workspace(
        id = "abc",
        name = "Daily",
        distros = listOf("ubuntu", "debian"),
        serverIds = listOf("s1", "s2"),
        theme = "nord",
        snippetIds = listOf("k9"),
        enableStartupHook = true,
        split = true,
        pinned = true
    )

    @Test
    fun `a workspace round-trips through json`() {
        val raw = WorkspaceStore.toJson(listOf(sample))
        val back = WorkspaceStore.fromJson(raw)
        assertEquals(1, back.size)
        assertEquals(sample, back.single())
    }

    @Test
    fun `an empty list round-trips`() {
        val empty = emptyList<WorkspaceStore.Workspace>()
        assertEquals(empty, WorkspaceStore.fromJson(WorkspaceStore.toJson(empty)))
        assertEquals(empty, WorkspaceStore.fromJson(null))
        assertEquals(empty, WorkspaceStore.fromJson(""))
        assertEquals(empty, WorkspaceStore.fromJson("not json"))
    }

    @Test
    fun `a record missing new fields still loads`() {
        val old = JSONObject().apply {
            put("id", "abc")
            put("name", "Daily")
        }
        val back = WorkspaceStore.fromJson(JSONArray().put(old).toString())
        val w = back.single()
        assertEquals("abc", w.id)
        assertEquals("Daily", w.name)
        assertTrue(w.distros.isEmpty())
        assertTrue(w.serverIds.isEmpty())
        assertNull(w.theme)
        assertTrue(w.snippetIds.isEmpty())
    }

    @Test
    fun `a bad record is dropped, not fatal to the list`() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "good").put("name", "G"))
            .put(JSONObject()) // no id: must be dropped
            .put(7) // not an object: must be dropped
            .toString()
        val back = WorkspaceStore.fromJson(raw)
        assertEquals(1, back.size)
        assertEquals("good", back.single().id)
    }

    @Test
    fun `clean distros are trimmed de-duplicated and ordered`() {
        val w = WorkspaceStore.Workspace("id", "n", distros = listOf(" ubuntu ", "", "ubuntu", " debian "))
        assertEquals(listOf("ubuntu", "debian"), w.cleanDistros)
        assertEquals(2, w.sessionCount())
    }

    @Test
    fun `a record written before split and pin existed loads with both off`() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("id", "old")
                    .put("name", "Old")
                    .put("distros", JSONArray(listOf("ubuntu")))
            )
            .toString()
        val back = WorkspaceStore.fromJson(raw).single()
        assertEquals("old", back.id)
        org.junit.Assert.assertFalse(back.split)
        org.junit.Assert.assertFalse(back.pinned)
    }
}
