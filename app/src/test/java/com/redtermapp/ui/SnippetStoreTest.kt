package com.redtermapp.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure ordering/search/migration logic of the snippet library, pinned so
 * a refactor cannot quietly change what "recents" means or drop a legacy
 * snippet on upgrade.
 */
class SnippetStoreTest {

    private fun s(id: String, name: String, content: String = "", folder: String = "", used: Long = 0L) =
        SnippetStore.Snippet(id, name, content, folder, used)

    @Test
    fun `round trip preserves every field`() {
        val list = listOf(
            s("1", "git push", "git push origin HEAD", "vcs", used = 5),
            s("2", "list images", "docker ps -a", "devops")
        )
        val back = SnippetStore.fromJson(SnippetStore.toJson(list))
        assertEquals(list, back)
    }

    @Test
    fun `a record without optional fields still loads`() {
        val raw = JSONArray().put(JSONObject().put("id", "x").put("name", "x")).toString()
        val one = SnippetStore.fromJson(raw).single()
        assertEquals("x", one.name)
        assertEquals("", one.folder)
        assertEquals(0L, one.lastUsedAt)
    }

    @Test
    fun `search is case-insensitive and matches name and content`() {
        val list = listOf(s("1", "Git Push", "git push"), s("2", "ls", "ls -la /var"))
        assertEquals(listOf("Git Push"), SnippetStore.search(list, "git").map { it.name })
        assertEquals(listOf("ls"), SnippetStore.search(list, "/var").map { it.name })
        assertEquals(2, SnippetStore.search(list, "   ").size)
        assertTrue(SnippetStore.search(list, "nope").isEmpty())
    }

    @Test
    fun `recency puts used snippets first by timestamp`() {
        val list = listOf(s("old", "a", used = 1), s("new", "b", used = 9), s("never", "c"))
        assertEquals(listOf("new", "old", "never"), SnippetStore.byRecency(list).map { it.id })
    }

    @Test
    fun `folders are distinct non-blank in first-seen order`() {
        val list = listOf(s("1", "a", folder = "ops"), s("2", "b", folder = ""), s("3", "c", folder = "ops"), s("4", "d", folder = "dev"))
        assertEquals(listOf("ops", "dev"), SnippetStore.folders(list))
    }

    @Test
    fun `corrupt json is an empty list, not a throw`() {
        assertTrue(SnippetStore.fromJson("{{").isEmpty())
        assertTrue(SnippetStore.fromJson(null).isEmpty())
    }
}
