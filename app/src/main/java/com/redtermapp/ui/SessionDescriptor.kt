package com.redtermapp.ui

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What a terminal session *is*, as opposed to how it is displayed.
 *
 * A session used to be identified only by its name in the terminal's own
 * `mSessionName`, which the user is free to edit from the session drawer. That is
 * enough to draw a list and nothing more: you cannot tell an SSH session from a
 * distro session, cannot reconnect to the right server, cannot close the sessions
 * belonging to a deleted server, and cannot restore them after Android kills the
 * process. Every one of those needed something the name could not carry.
 *
 * The label is kept because it *is* what the user sees, but nothing branches on
 * it — branching on a display string is what let a session named after a distro
 * be mistaken for one.
 */
sealed class SessionDescriptor {

    abstract val id: String
    abstract val label: String

    /** A shell running inside an installed proot distribution. */
    data class Local(
        override val id: String,
        override val label: String,
        val distro: String,
        val startDir: String? = null,
        /**
         * Whether this session's output is being written to a recording.
         *
         * Carried on the descriptor so the indicator survives the process and so a
         * restored session is still known to be recorded — the file name is derived
         * from the distribution and the time, so it can be found again afterwards
         * without storing it twice.
         */
        val recording: Boolean = false
    ) : SessionDescriptor()

    /**
     * A shell running the bundled ssh client against a saved server.
     *
     * Host, port, user and key are copied in rather than looked up on purpose:
     * a restored session has to reconnect with the settings it was opened with
     * even if the server entry has since been edited or deleted. The server id
     * is still stored, because it is what lets deleting a server close its
     * sessions and what lets the transfer UI find the same server again.
     */
    data class Ssh(
        override val id: String,
        override val label: String,
        val serverId: String,
        val host: String,
        val port: Int,
        val user: String,
        val keyId: String? = null,
        val startDir: String? = null
    ) : SessionDescriptor()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("kind", if (this@SessionDescriptor is Local) "local" else "ssh")
        when (this@SessionDescriptor) {
            is Local -> {
                put("distro", distro)
                put("startDir", startDir ?: "")
            }
            is Ssh -> {
                put("serverId", serverId)
                put("host", host)
                put("port", port)
                put("user", user)
                put("keyId", keyId ?: "")
                put("startDir", startDir ?: "")
            }
        }
    }

    companion object {

        fun newId(): String = UUID.randomUUID().toString()

        /**
         * Reads one descriptor, or null when the record is unusable.
         *
         * A corrupt entry is dropped rather than thrown from: it would otherwise
         * take every other session down with it, and the only consequence is that
         * one session does not come back after a restart.
         */
        fun fromJson(o: JSONObject?): SessionDescriptor? {
            if (o == null) return null
            val id = o.optString("id").ifBlank { return null }
            val label = o.optString("label").ifBlank { return null }
            val startDir = o.optString("startDir").takeIf { it.isNotBlank() }
            return when (o.optString("kind")) {
                "local" -> {
                    val distro = o.optString("distro").takeIf { it.isNotBlank() } ?: return null
                    Local(id, label, distro, startDir)
                }
                "ssh" -> {
                    val host = o.optString("host").takeIf { it.isNotBlank() } ?: return null
                    Ssh(
                        id = id,
                        label = label,
                        serverId = o.optString("serverId").ifBlank { host },
                        host = host,
                        port = o.optInt("port", 22).coerceIn(1, 65535),
                        user = o.optString("user"),
                        keyId = o.optString("keyId").takeIf { it.isNotBlank() },
                        startDir = startDir
                    )
                }
                else -> null
            }
        }

        fun listToJson(descriptors: List<SessionDescriptor>): String {
            val array = JSONArray()
            for (d in descriptors) array.put(d.toJson())
            return array.toString()
        }

        fun listFromJson(raw: String?): List<SessionDescriptor> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { fromJson(array.optJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}