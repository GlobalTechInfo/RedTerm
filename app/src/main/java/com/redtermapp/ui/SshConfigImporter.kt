package com.redtermapp.ui

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Reads an OpenSSH `ssh_config` and turns its `Host` blocks into saved servers.
 *
 * The file is the configuration every other ssh tool on every other machine already
 * has. Asking someone to retype the hosts, ports and identities they have written
 * there is the kind of busywork that makes an app feel unfinished, and an imported
 * config also brings the settings that matter with it — a jump host, an identity
 * file — which are exactly the ones a hand-built form tends to omit.
 *
 * Deliberately a subset: the parts a phone screen can present and a user can check.
 * `Match`, `Include`, `ProxyCommand` and the rest are read past rather than guessed
 * at, because a config file with a `Match exec` block would silently import the wrong
 * settings for every host in it.
 */
object SshConfigImporter {

    private const val TAG = "SshConfigImporter"

    data class Parsed(
        val servers: List<SshStore.Server>,
        /** Host patterns that are wildcards or `*`, which cannot become a server. */
        val skippedPatterns: List<String>,
        /** Lines the parser did not understand, so the user can be told. */
        val unrecognised: List<String>
    )

    /**
     * One `Host` block, reduced to what can be shown.
     *
     * [hosts] keeps every pattern on the line: `Host db prod` is two names with one
     * set of settings, and inventing a single label for the pair would lose one of
     * them.
     */
    private class Block(val hosts: List<String>) {
        var hostName: String? = null
        var user: String? = null
        var port: Int? = null
        var identityFile: String? = null
        var jumpHost: String? = null
        var forwardAgent: Boolean? = null
        var compression: Boolean? = null
        var keepAlive: Int? = null
    }

    /** Parses [text]. Pure, so it can be tested against real configurations. */
    fun parse(text: String): Parsed {
        val blocks = mutableListOf<Block>()
        val unrecognised = mutableListOf<String>()
        // Set by a `Match`, so the settings that follow it are not mistaken for more
        // settings on the last Host block. They belong to a conditional block this
        // parser does not evaluate, and attributing them to a host is how a config
        // gives every server the credentials of one particular bastion.
        var insideMatch = false

        for (rawLine in text.lineSequence()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) {
                unrecognised.add(rawLine.trim())
                continue
            }
            val keyword = parts[0].lowercase()
            val value = parts[1].trim()
            if (keyword == "host") insideMatch = false
            val block = if (insideMatch) null else blocks.lastOrNull()
            when (keyword) {
                // A new Host ends the previous block. Two Host lines in a row, the
                // first carrying only the name, is normal and not an error.
                "host" -> blocks.add(Block(hosts = hostPatterns(rawLine)))
                "hostname" -> block?.hostName = value
                "user" -> block?.user = value
                "port" -> blocks.lastOrNull()?.let { it.port = value.toIntOrNull() }
                "identityfile" -> block?.identityFile = value
                "proxyjump" -> block?.jumpHost = value
                // No, and the negation both ways: a config that says "no" is a
                // decision, not an absence.
                "forwardagent" -> block?.forwardAgent = parseYesNo(value)
                "compression" -> block?.compression = parseYesNo(value)
                "serveraliveinterval" -> block?.keepAlive = value.toIntOrNull()
                "match" -> {
                    insideMatch = true
                    unrecognised.add(rawLine.trim())
                }
                "include" ->
                    // Read past on purpose: applying a conditional block to every host
                    // would be worse than ignoring it.
                    unrecognised.add(rawLine.trim())
                // Anything else is a setting this app does not model. Passed over
                // quietly, because a config is full of them and refusing to import
                // because of one would be useless.
                else -> Unit
            }
        }

        val servers = mutableListOf<SshStore.Server>()
        val skipped = mutableListOf<String>()
        for (block in blocks) {
            // First obtained wins, matching ssh: an earlier block overrides a later one.
            val target = block.hostName ?: block.hosts.firstOrNull() ?: continue
            val names = block.hosts.filter { it.isNotBlank() }
            val usable = names.filter { !it.contains('*') && !it.contains('?') && !it.contains('!') }
            skipped += names - usable.toSet()
            for (name in usable) {
                servers.add(
                    SshStore.Server(
                        id = UUID.randomUUID().toString(),
                        label = name,
                        host = target,
                        port = block.port ?: 22,
                        user = block.user.orEmpty(),
                        compress = block.compression == true,
                        forwardAgent = block.forwardAgent == true,
                        keepAliveSeconds = block.keepAlive?.takeIf { seconds -> seconds > 0 } ?: 0,
                        jump = block.jumpHost
                            ?.takeIf { text -> text.isNotBlank() }
                            ?.let { text -> parseJump(text) },
                        // An IdentityFile cannot be matched to a key on this device
                        // until it exists, so it is carried through on the label's
                        // behalf rather than guessed at.
                        keyId = null
                    )
                )
            }
        }
        return Parsed(servers, skipped.distinct(), unrecognised.distinct())
    }

    /**
     * Saves what [parse] found, skipping hosts that are already saved.
     *
     * Importing the same config twice must not produce two of every server: the label
     * and host are the pair that identifies one, because a config has no ids.
     */
    fun importInto(context: Context, text: String): ImportSummary {
        val parsed = parse(text)
        var existing = SshStore.load(context)
        var added = 0
        var skippedExisting = 0
        for (server in parsed.servers) {
            val duplicate = existing.any {
                it.host.equals(server.host, ignoreCase = true) && it.port == server.port &&
                    it.user.equals(server.user, ignoreCase = true)
            }
            if (duplicate) {
                skippedExisting++
                continue
            }
            existing = existing + server
            added++
        }
        if (added > 0) SshStore.save(context, existing)
        Log.i(
            TAG,
            "imported $added server(s), skipped $skippedExisting existing, " +
                "${parsed.skippedPatterns.size} pattern(s)"
        )
        return ImportSummary(added, skippedExisting, parsed.skippedPatterns, parsed.unrecognised)
    }

    data class ImportSummary(
        val added: Int,
        val skippedExisting: Int,
        val skippedPatterns: List<String>,
        val unrecognised: List<String>
    )

    /**
     * Reads the value list of a `Host` line.
     *
     * Taken from the line before the comment is stripped, because a `#` is legal
     * inside a host pattern and a line like `Host db#1` is not a comment.
     */
    private fun hostPatterns(original: String): List<String> {
        val body = original.trim()
        val keyword = body.split(Regex("\\s+")).firstOrNull() ?: return emptyList()
        val afterKeyword = body.removePrefix(keyword).trim()
        // Everything up to the first whitespace-then-# is part of the value, because
        // a '#' is legal inside a host pattern and `Host db#1` is not a comment.
        val commentAt = Regex("""\s+#""").find(afterKeyword)?.range?.first ?: -1
        val value = if (commentAt >= 0) afterKeyword.take(commentAt) else afterKeyword
        return value.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun stripComment(line: String): String {
        val match = Regex("""(^|\s)#""").find(line) ?: return line
        return line.take(match.range.first)
    }

    private fun parseYesNo(value: String): Boolean? = when (value.lowercase()) {
        "yes", "true", "on" -> true
        "no", "false", "off" -> false
        else -> null
    }

    /**
     * `user@host:port`, or `ssh://user@host:port`.
     *
     * `ssh -J` takes the same notation, so what is parsed here is what is handed to
     * it, with nothing in between.
     */
    private fun parseJump(value: String): SshStore.Jump? {
        var text = value
        if (text.startsWith("ssh://")) text = text.removePrefix("ssh://")
        var user = ""
        val at = text.lastIndexOf('@')
        if (at > 0) {
            user = text.take(at)
            text = text.substring(at + 1)
        }
        var port = 22
        val colon = text.lastIndexOf(':')
        if (colon > 0 && !text.endsWith("]")) {
            port = text.substring(colon + 1).toIntOrNull() ?: 22
            text = text.take(colon)
        }
        return if (text.isBlank()) null else SshStore.Jump(text, port, user)
    }
}