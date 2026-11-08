package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A durable command/text snippet library.
 *
 * Supersedes the flat "snippets" list the terminal menu kept in the general
 * settings prefs: that list could grow duplicate names, had no search, no
 * folders, and no recency. This store is JSON in its own prefs file, de-dups
 * by name on save, records last-use for a "recents" ordering, and — so an
 * upgrade never loses what the user typed in the old dialog — migrates the
 * legacy flat list on first read.
 */
object SnippetStore {

    const val PREFS = "snippets"
    private const val KEY = "library"
    private const val LEGACY_PREFS = "settings"
    private const val LEGACY_KEY = "snippets"

    data class Snippet(
        val id: String,
        val name: String,
        val content: String,
        val folder: String = "",
        val lastUsedAt: Long = 0L
    )

    /** Every snippet; [legacy] (from settings prefs) is folded in once. */
    fun list(context: Context, migrate: Boolean = true): List<Snippet> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val current = fromJson(raw)
        if (!current.isEmpty() || !migrate) return current
        // First run: rescue the legacy flat list so nothing typed by hand is lost.
        val legacy = legacyList(context)
        if (legacy.isEmpty()) return current
        save(context, legacy)
        return legacy
    }

    fun save(context: Context, snippets: List<Snippet>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, toJson(snippets))
        }
    }

    /** De-dup by name: editing a snippet keeps its id; adding replaces same-name. */
    fun upsert(context: Context, snippet: Snippet): List<Snippet> {
        val all = list(context).filterNot { it.name == snippet.name } + snippet
        save(context, all)
        return all
    }

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun touch(context: Context, id: String) {
        save(context, list(context).map {
            if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        })
    }

    fun get(context: Context, id: String): Snippet? = list(context).firstOrNull { it.id == id }

    fun newSnippet(name: String, content: String, folder: String = ""): Snippet =
        Snippet(id = UUID.randomUUID().toString(), name = name, content = content, folder = folder)

    // --- pure, so search/ordering are unit-testable off-device ---

    /** Case-insensitive substring match over name and content. */
    fun search(snippets: List<Snippet>, query: String): List<Snippet> {
        val q = query.trim()
        if (q.isEmpty()) return snippets
        val needle = q.lowercase()
        return snippets.filter {
            it.name.lowercase().contains(needle) || it.content.lowercase().contains(needle)
        }
    }

    /** Most-recently-used first; never-used snippets keep their order after them. */
    fun byRecency(snippets: List<Snippet>): List<Snippet> {
        val used = snippets.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
        val unused = snippets.filter { it.lastUsedAt == 0L }
        return used + unused
    }

    /** Distinct, non-blank folders in first-seen order; blank is "General". */
    fun folders(snippets: List<Snippet>): List<String> =
        snippets.map { it.folder }.filter { it.isNotEmpty() }.distinct()

    fun toJson(list: List<Snippet>): String =
        JSONArray().also { arr ->
            for (s in list) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("content", s.content)
                    put("folder", s.folder)
                    put("lastUsedAt", s.lastUsedAt)
                })
            }
        }.toString()

    fun fromJson(raw: String?): List<Snippet> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { n ->
                val o = arr.optJSONObject(n) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Snippet(
                    id = o.optString("id").takeIf { it.isNotEmpty() } ?: name,
                    name = name,
                    content = o.optString("content"),
                    folder = o.optString("folder"),
                    lastUsedAt = o.optLong("lastUsedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The legacy flat {name,content} array, as current [Snippet]s. */
    private fun legacyList(context: Context): List<Snippet> {
        val raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { n ->
                val o = arr.optJSONObject(n) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Snippet(id = UUID.randomUUID().toString(), name = name, content = o.optString("content"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
