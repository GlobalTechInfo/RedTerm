package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named, saved bundle of the things a terminal session is made of: which
 * distro(s), which SSH server(s), the theme, and the snippets the user
 * reaches for. Launching a workspace opens every session it names in one
 * tap, which is what a "I always work in Ubuntu with my two VPSes and these
 * snippets" loop should cost.
 *
 * Like [SshStore] it is JSON in preferences: a workspace is app state, not
 * anything that outlives the process, and a flat record that can grow without
 * a migration. A workspace references servers and snippets by id and copies
 * the distro key; if a referenced server or snippet is later deleted the
 * launch skips the missing piece rather than failing the whole bundle.
 */
object WorkspaceStore {

    const val PREFS = "workspaces"
    private const val KEY = "list"

    data class Workspace(
        val id: String,
        val name: String,
        val distros: List<String> = emptyList(),
        val serverIds: List<String> = emptyList(),
        val theme: String? = null,
        val snippetIds: List<String> = emptyList(),
        val enableStartupHook: Boolean = false
    ) {
        /** Distinct, non-blank distro keys; order is the user's. */
        val cleanDistros: List<String>
            get() = distros.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun sessionCount(): Int = cleanDistros.size + serverIds.size
    }

    fun list(context: Context): List<Workspace> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.let { WorkspaceStore.fromJson(it) }
            ?: emptyList()

    fun save(context: Context, workspaces: List<Workspace>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, toJson(workspaces))
        }
    }

    fun get(context: Context, id: String): Workspace? = list(context).firstOrNull { it.id == id }

    /** Upsert by id: a list without [workspace] plus the new value, order kept. */
    fun upsert(context: Context, workspace: Workspace): List<Workspace> {
        val all = list(context).filterNot { it.id == workspace.id } + workspace
        save(context, all)
        return all
    }

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun newWorkspace(name: String): Workspace =
        Workspace(id = UUID.randomUUID().toString(), name = name)

    // --- pure JSON shape, round-tripped by a test so a missing field is a
    // test failure, not a silent empty workspace ---

    fun toJson(list: List<Workspace>): String =
        JSONArray().also { arr ->
            for (w in list) {
                arr.put(JSONObject().apply {
                    put("id", w.id)
                    put("name", w.name)
                    put("distros", JSONArray(w.cleanDistros))
                    put("servers", JSONArray(w.serverIds))
                    put("theme", w.theme ?: "")
                    put("snippets", JSONArray(w.snippetIds))
                    put("hook", w.enableStartupHook)
                })
            }
        }.toString()

    fun fromJson(raw: String?): List<Workspace> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { n ->
                val o = arr.optJSONObject(n) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Workspace(
                    id = id,
                    name = o.optString("name", id),
                    distros = jsonStrings(o, "distros"),
                    serverIds = jsonStrings(o, "servers"),
                    theme = o.optString("theme").takeIf { it.isNotEmpty() },
                    snippetIds = jsonStrings(o, "snippets"),
                    enableStartupHook = o.optBoolean("hook", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The non-blank string elements of a JSON array field, in order. */
    private fun jsonStrings(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }
}
