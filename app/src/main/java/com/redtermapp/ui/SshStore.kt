package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved SSH hosts. Kept as JSON in preferences so the list survives upgrades
 * and can grow without a schema migration.
 */
object SshStore {

    const val PREFS = "ssh_servers"
    private const val KEY = "servers"

    data class Server(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val user: String
    ) {
        /** The command typed into the terminal, with the optional user prefix. */
        fun command(keyPath: String?): String {
            val target = if (user.isNotBlank()) "$user@$host" else host
            val identity = if (keyPath != null) " -i $keyPath" else ""
            return "ssh$identity -p $port $target"
        }
    }

    fun load(context: Context): List<Server> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val host = o.optString("host")
                if (host.isBlank()) return@mapNotNull null
                Server(
                    id = o.optString("id", host),
                    label = o.optString("label", host),
                    host = host,
                    port = o.optInt("port", 22),
                    user = o.optString("user", "root")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, servers: List<Server>) {
        val array = JSONArray()
        for (s in servers) {
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("host", s.host)
                    put("port", s.port)
                    put("user", s.user)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, array.toString())
        }
    }
}
