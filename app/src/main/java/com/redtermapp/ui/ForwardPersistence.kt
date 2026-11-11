package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ssh port-forwards the user asked to keep open, written to disk so a
 * reclaimed process does not silently lose them.
 *
 * A forward is a *child process* of the app, and Android reclaims the app
 * without killing its children: the `ssh -N` outlives the process and is
 * reparented to init. That is why the record lives here — on a cold start
 * the previous process's orphans are reaped and this list is re-opened, so
 * "a port that stays open while nothing is being typed" keeps its promise
 * across app deaths instead of quietly breaking at the first low-memory kill.
 *
 * Local relays are deliberately not persisted: they live in the app's own
 * process, die with it, and their target (a guest service) may be gone by
 * the time the app comes back. Only the ssh forwards outlive the app.
 *
 * [encode]/[decode] are pure and round-trip tested: a missing writer field
 * surfaces nowhere else, only as a forward that refuses to resume.
 */
object ForwardPersistence {

    private const val PREFS = "port_forwards"
    private const val KEY = "ssh_forwards"

    /** One ssh forward, keyed by server so a deleted server drops its forwards. */
    data class SavedForward(
        val serverId: String,
        val fromPort: Int,
        val toHost: String,
        val toPort: Int
    )

    fun load(context: Context): List<SavedForward> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, entries: List<SavedForward>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, encode(entries))
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY)
        }
    }

    fun encode(entries: List<SavedForward>): String =
        JSONArray().apply {
            for (e in entries) {
                put(
                    JSONObject()
                        .put("serverId", e.serverId)
                        .put("fromPort", e.fromPort)
                        .put("toHost", e.toHost)
                        .put("toPort", e.toPort)
                )
            }
        }.toString()

    /**
     * Tolerant on purpose: one entry with a missing or out-of-range port is
     * dropped, the rest resume. An unreadable record is no forwards, not an
     * exception in the middle of a cold start.
     */
    fun decode(json: String?): List<SavedForward> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val serverId = o.optString("serverId", "")
                val fromPort = o.optInt("fromPort")
                val toPort = o.optInt("toPort")
                if (serverId.isBlank() || fromPort !in 1..65535 || toPort !in 1..65535) {
                    return@mapNotNull null
                }
                SavedForward(
                    serverId = serverId,
                    fromPort = fromPort,
                    toHost = o.optString("toHost", "127.0.0.1"),
                    toPort = toPort
                )
            }
        }.getOrDefault(emptyList())
    }
}
