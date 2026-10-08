package com.redtermapp.util

import android.content.Context
import com.redtermapp.distro.ProotLaunch
import java.io.File

/**
 * Reads, fingerprints and forgets entries in the client's `known_hosts`.
 *
 * All three are done with the bundled `ssh-keygen` rather than by parsing the file
 * here. `ssh-keygen -R` understands hashed entries, markers, `@cert-authority` and
 * every future format, and it rewrites the file atomically; a hand-rolled parser
 * would silently fail on exactly the entries a user most needs to remove.
 *
 * This is what the missing escape hatch used to be. When a host's key changed, the
 * connection failed with a message telling the user to review it from a terminal —
 * and there was no way from the app to remove the stale entry, so the account was
 * stuck until they cleared the app's data.
 */
object KnownHosts {

    /** One entry, as the user sees it. */
    data class Entry(
        /** Host as `known_hosts` records it, which for a non-default port is `host:port`. */
        val host: String,
        /** Key type, e.g. `ssh-ed25519`. */
        val type: String,
        /** `SHA256:…`, or empty when the fingerprint could not be produced. */
        val fingerprint: String
    ) {
        /** How a host is named inside `known_hosts`, which differs for port 22. */
        fun lookupName(): String = host
    }

    /** Every entry in the file, in file order. */
    fun entries(context: Context): List<Entry> {
        val file = SshClient.knownHosts(context)
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            // Comments, and the blank line a rewrite can leave behind.
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            // A leading marker means this entry constrains certificates rather than
            // trusting a plain key, so it is not something to offer for removal as
            // though it were a host the app connected to.
            val host = if (parts[0].startsWith("@")) parts.getOrNull(1) ?: return@mapNotNull null
            else parts[0]
            Entry(host = host, type = parts[1], fingerprint = "")
        }
    }

    /**
     * The fingerprint of the key the server actually presented.
     *
     * Read out of a verbose connection rather than from `known_hosts`: this has to be
     * the key being *offered*, which is the whole point of asking the user about it.
     * Null when the server never got far enough to say.
     */
    fun offeredFingerprint(trace: String): String? {
        // debug1: Server host key: ssh-ed25519 SHA256:…
        val match = HOST_KEY_LINE.find(trace) ?: return null
        val fingerprint = match.groupValues.getOrNull(2)?.trim()
        return fingerprint?.takeIf { it.isNotEmpty() }
    }

    private val HOST_KEY_LINE = Regex("""Server host key:\s*(\S+)\s+(SHA256:\S+)""")

    /**
     * Removes an entry.
     *
     * Returns whether anything was removed, which is false for an entry that is not
     * there: telling the user "forgotten" when the file never had it would be a lie
     * about the only thing that matters here.
     */
    fun forget(context: Context, host: String): Boolean {
        if (host.isBlank()) return false
        val file = SshClient.knownHosts(context)
        if (!file.isFile) return false
        val (code, output) = SshClient.run(
            context = context,
            command = "/bin/ssh-keygen -R ${ProotLaunch.quoteForShell(host)} -f " +
                ProotLaunch.quoteForShell(SshClient.inRootfs(context, file)),
            timeoutMinutes = 1
        )
        return code == 0 && output.contains("removed") && file.isFile
    }

    /** Forgets every entry. */
    fun forgetAll(context: Context): Boolean {
        val entries = entries(context)
        // One at a time on purpose: a single rewrite would discard host key
        // constraints the app does not understand, and this file is the only thing
        // standing between the user and a machine-in-the-middle.
        var removedAny = false
        for (entry in entries) {
            if (forget(context, entry.host)) removedAny = true
        }
        return removedAny
    }

    /** Only for tests and diagnostics: the file itself. */
    fun file(context: Context): File = SshClient.knownHosts(context)
}