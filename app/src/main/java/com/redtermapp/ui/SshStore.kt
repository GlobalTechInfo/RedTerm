package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved SSH hosts. Kept as JSON in preferences so the list survives upgrades
 * and can grow without a schema migration.
 */
object SshStore {

    const val PREFS = "ssh_servers"
    private const val KEY = "servers"

    /**
     * How a connection proves who it is.
     *
     * Keys and passwords are not interchangeable: a server offering only
     * keyboard-interactive will reject a password attempt, and one offering only
     * publickey will reject the other. [Any] sends both, in the order ssh would
     * try them anyway.
     */
    enum class Auth {
        /** Public key only. The default, and the only thing the app assumed for a long time. */
        Keys,

        /** Interactive password. */
        Password,

        /** Try keys, then password. */
        Any;

        val wantsKeys: Boolean get() = this != Password
        val wantsPassword: Boolean get() = this != Keys
    }

    /**
     * A host to reach through to reach another one.
     *
     * Deliberately not a reference to another saved server: a jump host is usually
     * entered once, as an aside to the server it fronts, and making the user first
     * create it as a full server entry in its own right is two records to keep in
     * step for no benefit.
     */
    data class Jump(
        val host: String,
        val port: Int = 22,
        val user: String = ""
    ) {
        /** Blank rather than nonsense: a jump host with no host cannot be used. */
        val usable: Boolean get() = host.isNotBlank()
    }

    data class Server(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val user: String,
        /** Id of the [SshKeyStore.Entry] this server authenticates with, if any. */
        val keyId: String? = null,

        /**
         * Seconds between keepalives. Zero disables them.
         *
         * A wake lock keeps the phone awake; this keeps the *connection* awake. They
         * are unrelated problems and both are needed: NAT and firewalls drop an idle
         * TCP flow after minutes or hours, so a session that survives a locked screen
         * can still be cut off while it sits there doing nothing.
         */
        val keepAliveSeconds: Int = 0,

        /** Consecutive unanswered keepalives before giving up. Zero means ssh's own default. */
        val keepAliveCount: Int = 0,

        /** `-C`. Worth it on a slow or metered link, pure overhead on a fast one. */
        val compress: Boolean = false,

        /** `-A`: let the remote host use keys from this device's agent. */
        val forwardAgent: Boolean = false,

        /** Reached through another host, if any. */
        val jump: Jump? = null,

        /** What this server accepts. */
        val auth: Auth = Auth.Keys,

        /**
         * Id of the stored password for this server, if [auth] uses one.
         *
         * A reference and never the value: the secret itself lives encrypted under a
         * Keystore key, and this field is the only thing that goes into preferences.
         */
        val passwordRef: String? = null,

        /**
         * Whether to show the host key before trusting it.
         *
         * Off means `accept-new`: adopted silently, which is what a connection made
         * without a terminal to prompt on has to do. On means the first connection to
         * a new host is held for the user to look at.
         */
        val verifyHostKey: Boolean = false,

        /** Local forwards this server opens on connect. */
        val forwards: List<Forward> = emptyList()
    )

    /**
     * A tunnel this server opens.
     *
     * [spec] is the argument in ssh's own notation — `8080:db.internal:5432` for a
     * local forward, `db.internal:5432:localhost:5432` for a remote one — so that
     * what is stored is what ssh takes, with nothing in between to mistranslate.
     */
    data class Forward(val spec: String, val enabled: Boolean = true) {
        val usable: Boolean get() = spec.isNotBlank() && spec.none { it.isWhitespace() }
    }

    fun load(context: Context): List<Server> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return decode(raw)
    }

    fun save(context: Context, servers: List<Server>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, encode(servers))
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * The stored form, as pure data.
     *
     * Split out so a test can round-trip it. Every field a server gains has to appear
     * in both halves, and the half that is missing produces no error anywhere: the
     * value simply reads back as its default, so a setting the user changed is
     * silently ignored.
     */
    fun encode(servers: List<Server>): String {
        val array = JSONArray()
        for (s in servers) {
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("host", s.host)
                    put("port", s.port)
                    put("user", s.user)
                    put("keyId", s.keyId ?: "")
                    put("keepAliveSeconds", s.keepAliveSeconds)
                    put("keepAliveCount", s.keepAliveCount)
                    put("compress", s.compress)
                    put("forwardAgent", s.forwardAgent)
                    put("auth", s.auth.name)
                    put("passwordRef", s.passwordRef ?: "")
                    put("verifyHostKey", s.verifyHostKey)
                    put("jump", s.jump?.let { j ->
                        JSONObject().apply {
                            put("host", j.host)
                            put("port", j.port)
                            put("user", j.user)
                        }
                    } ?: JSONObject.NULL)
                    put("forwards", JSONArray().apply {
                        for (f in s.forwards) {
                            put(
                                JSONObject().apply {
                                    put("spec", f.spec)
                                    put("enabled", f.enabled)
                                }
                            )
                        }
                    })
                }
            )
        }
        return array.toString()
    }

    /**
     * The counterpart of [encode].
     *
     * Tolerant of records written before a field existed: an absent one reads as its
     * default rather than throwing, so an app upgrade does not lose the server list.
     */
    fun decode(raw: String): List<Server> = try {
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
                user = o.optString("user", "root"),
                keyId = o.optString("keyId").takeIf { it.isNotBlank() },
                keepAliveSeconds = o.optInt("keepAliveSeconds", 0).coerceAtLeast(0),
                keepAliveCount = o.optInt("keepAliveCount", 0).coerceAtLeast(0),
                compress = o.optBoolean("compress", false),
                forwardAgent = o.optBoolean("forwardAgent", false),
                jump = o.optJSONObject("jump")?.let { j ->
                    Jump(
                        host = j.optString("host"),
                        port = j.optInt("port", 22),
                        user = j.optString("user")
                    )
                },
                auth = runCatching { Auth.valueOf(o.optString("auth", Auth.Keys.name)) }
                    .getOrDefault(Auth.Keys),
                passwordRef = o.optString("passwordRef").takeIf { it.isNotBlank() },
                verifyHostKey = o.optBoolean("verifyHostKey", false),
                forwards = o.optJSONArray("forwards")?.let { list ->
                    (0 until list.length()).mapNotNull { n ->
                        list.optJSONObject(n)?.let { f ->
                            Forward(
                                spec = f.optString("spec"),
                                enabled = f.optBoolean("enabled", true)
                            )
                        }
                    }
                } ?: emptyList()
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}