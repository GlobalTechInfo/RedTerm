package com.redtermapp.ui

/**
 * Builds the argument list handed to the bundled OpenSSH binaries.
 *
 * This is the one place that decides how a connection is secured, and a mistake
 * here is silent at runtime: `ssh` either fails to connect or, worse, connects
 * with weaker settings than intended. It lives outside the activity so it can be
 * unit tested directly rather than through a copy of itself.
 */
object SshLaunchOptions {

    /** Inside the client rootfs the home directory is /root. */
    const val KNOWN_HOSTS = "/root/.ssh/known_hosts"

    /**
     * How many identities to offer when a server has no key bound to it.
     *
     * ssh counts each failed public-key attempt against the server's
     * `MaxAuthTries`, which defaults to 6 on many servers and results in a
     * disconnect. Six keys would therefore be enough to get the connection
     * dropped before the right one was tried.
     */
    const val MAX_IDENTITIES = 5

    /**
     * Options every connection gets.
     *
     * Host key checking stays on. `accept-new` still verifies an unknown key
     * against the fingerprint sshd offers, but adopts it without asking, which
     * is the only workable option when the prompt would land on a terminal the
     * user is not watching. Anything weaker than this is a real downgrade.
     */
    private val BASE_OPTIONS = listOf(
        "UserKnownHostsFile=$KNOWN_HOSTS",
        "StrictHostKeyChecking=accept-new"
    )

    /** Arguments for a saved server. */
    fun forServer(
        server: SshStore.Server,
        identities: List<String> = emptyList(),
        extraOptions: List<String> = emptyList(),
        /** Also apply the server's port forwards. Off for one-shot commands. */
        withForwards: Boolean = true
    ): List<String> = buildList {
        add("-p")
        add(server.port.toString())
        addAll(options(identities, extraOptions, server))
        addAll(jumpArgs(server))
        if (compress(server)) add("-C")
        if (server.forwardAgent) add("-A")
        if (withForwards) {
            for (forward in server.forwards.filter { it.enabled && it.usable }) {
                addAll(forwardArgs(forward))
            }
        }
        add(target(server.host, server.user))
    }

    /**
     * The connection options on their own, with no target.
     *
     * A caller that appends a remote command — an `ls`, an `sftp` subsystem —
     * needs the options without the host, because ssh reads the host as "the
     * first non-option argument" and everything after it as the command.
     */
    fun options(
        identities: List<String> = emptyList(),
        extraOptions: List<String> = emptyList(),
        server: SshStore.Server? = null
    ): List<String> {
        val args = mutableListOf<String>()
        for (identity in identities.take(MAX_IDENTITIES)) {
            args.add("-i")
            args.add(identity)
        }
        if (args.isNotEmpty()) {
            // Only the identities named above, so the agent cannot silently
            // substitute a different one than the user picked.
            args.add("-o")
            args.add("IdentitiesOnly=yes")
        }
        for (option in BASE_OPTIONS + serverOptions(server) + extraOptions) {
            args.add("-o")
            args.add(option)
        }
        return args
    }

    /**
     * The options a saved server contributes, on their own.
     *
     * Separate from [options] so it can be tested without an identity list and a
     * target, and so the same set can be handed to a caller assembling its own argv.
     */
    fun serverOptions(server: SshStore.Server?): List<String> {
        if (server == null) return emptyList()
        val options = mutableListOf<String>()
        if (server.keepAliveSeconds > 0) {
            options.add("ServerAliveInterval=${server.keepAliveSeconds}")
            // An unanswered count only means anything once the interval is set; on its
            // own it does nothing, and sending it would suggest otherwise.
            if (server.keepAliveCount > 0) {
                options.add("ServerAliveCountMax=${server.keepAliveCount}")
            }
        }
        options.add("PasswordAuthentication=${if (server.auth.wantsPassword) "yes" else "no"}")
        options.add("KbdInteractiveAuthentication=${if (server.auth.wantsPassword) "yes" else "no"}")
        if (!server.auth.wantsKeys) {
            // A password-only server that also offers public key would try every key
            // first, burning MaxAuthTries before it ever asks.
            options.add("PubkeyAuthentication=no")
        }
        return options
    }

    /**
     * `-J` for the jump host, if there is one.
     *
     * ssh then authenticates to the intermediate itself, including its own host key
     * check and its own keepalives, so nothing here has to know how a tunnel works.
     */
    fun jumpArgs(server: SshStore.Server): List<String> {
        val jump = server.jump?.takeIf { it.usable } ?: return emptyList()
        val hostPart = if (jump.port == 22) jump.host else "${jump.host}:${jump.port}"
        return listOf("-J", target(hostPart, jump.user))
    }

    private fun compress(server: SshStore.Server) = server.compress

    /**
     * The flag for one forward.
     *
     * Which flag depends on the shape of the spec: ssh's own convention is that a
     * local forward is `bind:host:hostport` and a remote one is `host:hostport:bind`.
     * Guessing from the spec rather than from a separate type keeps what is stored
     * identical to what is passed, with nothing in between to mistranslate.
     */
    fun forwardArgs(forward: SshStore.Forward): List<String> {
        val parts = forward.spec.split(':')
        // A single colon, or three, both read as local. Four is the classic "bad
        // forwarding specification" and is not ours to interpret.
        val flag = if (parts.size == 4) "-R" else "-L"
        return listOf(flag, forward.spec)
    }

    /**
     * The host-key options on their own, for callers that build their own argv.
     *
     * Deliberately does not include the keepalives: a one-shot command like the
     * connection test is finished in seconds and a keepalive interval on it is
     * pointless noise in the trace.
     */
    fun baseOptions(): List<String> = BASE_OPTIONS

    /** Port, identity and host-key options for a bare host. */
    fun forTarget(
        host: String,
        port: Int,
        user: String = "",
        identities: List<String> = emptyList(),
        extraOptions: List<String> = emptyList()
    ): List<String> = buildList {
        add("-p")
        add(port.toString())
        addAll(options(identities, extraOptions))
        // The target has to come last: ssh treats everything after the host as a
        // remote command, so `-o` flags placed after it are parsed as one.
        add(target(host, user))
    }

    /**
     * Joins user and host.
     *
     * A blank user is dropped rather than sent as `@host`, and an IPv6 literal
     * is left unbracketed: ssh parses the part after the last `@` itself, so
     * inserting brackets would hand it a hostname that does not exist.
     */
    fun target(host: String, user: String): String =
        if (user.isBlank()) host else "$user@$host"
}
