package com.redtermapp.mosh

import android.app.Activity
import android.content.Context
import java.net.InetAddress
import com.redtermapp.ui.SshKeyAuthenticator
import com.redtermapp.ui.SshKeyStore
import com.redtermapp.ui.SshLaunchOptions
import com.redtermapp.ui.SshStore
import com.redtermapp.util.AppLog
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.util.SshClient
import com.redtermapp.util.sftp.SshAskpass

/**
 * Turns a saved server into a running mosh session.
 *
 * mosh is two protocols and one secret. SSH runs `mosh-server new` on the far end, which
 * picks a UDP port, prints it together with a one-time key, and detaches; from then on
 * the session is UDP only and the phone's IP may change underneath it. Nothing here is
 * optional — without `mosh-server` on the host there is no mosh session, only ssh — so
 * every failure here is reported as a reason and the caller falls back.
 */
object MoshBootstrap {

    private const val TAG = "mosh"

    /** Where the UDP side of the session lives, and the secret that opens it. */
    data class Endpoint(val ip: String, val port: Int, val key: String)

    sealed interface Outcome {
        /** A server was started and handed back its address. */
        data class Ready(val endpoint: Endpoint) : Outcome

        /**
         * mosh is not going to happen for this server, and [reason] says why in words a
         * user can act on. [detail] is whatever the host said, kept for Diagnostics.
         *
         * [anotherCredentialCouldHelp] marks a refusal the authenticator should follow
         * with another credential: the ssh session itself was turned down — exit 255,
         * which is the only exit that means that — so a different key or password may
         * still be accepted. False for every other failure, where authentication
         * already succeeded and the far end could not run `mosh-server`; running again
         * under a different key produces the same answer more slowly.
         */
        data class Unavailable(
            val reason: String,
            val detail: String = "",
            val anotherCredentialCouldHelp: Boolean = false,
        ) : Outcome
    }

    /**
     * The command run on the far end.
     *
     * `mosh-server` prints only `MOSH CONNECT <port> <key>`; it never says which address
     * to send those UDP datagrams to, and the address is not always the hostname's:
     * behind a jump host it is the *exit* host, and a hostname may resolve to something
     * else entirely. mosh's own wrapper solves this by reading `$SSH_CONNECTION`, whose
     * fourth field is the far end's own idea of its address — the same value
     * `mosh-server` uses to decide which interface to bind. Asking for it in the same
     * ssh invocation keeps this to one connection and one authentication.
     *
     * The escaping is three shells deep and each one matters:
     *
     *  - `$SSH_CONNECTION` must reach the *remote* shell unexpanded. It is inside double
     *    quotes here, so `\$` is what survives the device shell as a literal `$`, which
     *    the remote shell then expands. Single quotes would have been wrong twice over:
     *    a backslash inside them is literal, so the remote shell would have seen
     *    `\$SSH_CONNECTION` and printed the text rather than the value.
     *  - `exec` matters because `mosh-server` detaches by forking. Without it the wrapper
     *    shell lingers as mosh-server's parent and ssh's channel stays open, so the
     *    bootstrap appears to hang on a session that is in fact ready.
     *  - `mosh-server` is invoked by name, not by path, because the path differs between
     *    distributions (`/usr/bin`, `/usr/local/bin`) and a hardcoded one is a failure
     *    that only appears on some hosts.
     */
    private val REMOTE_COMMAND =
        "/bin/sh -c \"echo MOSH SSH_CONNECTION \\${'$'}SSH_CONNECTION; exec mosh-server new\""

    private val CONNECT_LINE = Regex("""^MOSH CONNECT (\d+) ([A-Za-z0-9+/]{22})\s*$""")
    private val LEGACY_KEY_LINE = Regex("""^MOSH_KEY=(\S+)\s*$""")
    private val SSH_CONNECTION_LINE =
        Regex("""^MOSH SSH_CONNECTION (\S+) \d+ (\S+) \d+\s*$""")

    /**
     * Starts a mosh session for [server], trying each of its credentials in turn.
     *
     * Runs on a worker thread: it opens an ssh connection, and on a raw-file device that
     * is seconds of blocking work that has no business on the main thread.
     */
    fun connect(
        activity: Activity,
        context: Context,
        server: SshStore.Server,
        cols: Int,
        rows: Int,
    ): Outcome {
        if (!MoshNative.ensureLoaded(context)) {
            return Outcome.Unavailable(
                "mosh is not available on this device",
                MoshNative.loadError.orEmpty(),
            )
        }

        // The last failure that was NOT about credentials is what a Rejected result
        // names, because "no credential accepted" would be the wrong reason for a host
        // that accepted every credential and then had no mosh-server to run.
        var nonCredentialReason: String? = null
        return when (
            val auth = SshKeyAuthenticator.authenticate(activity, context, server) { key, secret ->
                AppLog.d(
                    context, TAG,
                    if (key == null) {
                        "try a password for ${server.host}"
                    } else {
                        "try ${key.label} (${key.fileName})" +
                            if (secret.isEmpty()) "" else " with a held passphrase"
                    },
                )
                launch(
                    context = context,
                    server = server,
                    identities = key?.let { listOf(SshKeyStore.identityPathFor(context, it)) }
                        ?: emptyList(),
                    passphrase = secret,
                ).also { attempt ->
                    if (attempt is SshKeyAuthenticator.Attempt.Other) {
                        nonCredentialReason = attempt.detail
                    }
                }
            }
        ) {
            is SshKeyAuthenticator.Result.Connected -> auth.value
            is SshKeyAuthenticator.Result.Rejected -> Outcome.Unavailable(
                nonCredentialReason ?: "no SSH credential on this device was accepted",
                auth.detail,
            )
            SshKeyAuthenticator.Result.NoKey -> Outcome.Unavailable(
                "this server has no SSH key to start mosh with",
            )
        }
    }

    private fun launch(
        context: Context,
        server: SshStore.Server,
        identities: List<String>,
        passphrase: String?,
    ): SshKeyAuthenticator.Attempt<Outcome> {
        val command = buildCommand(server, identities, passphrase)

        // Two minutes is generous for "start a server that prints one line and forks",
        // and the point of the bound is the failure it turns into a message instead of.
        //
        // buildCommand drops BatchMode for a held secret, so ssh is allowed to ask for
        // a passphrase or password — and the askpass environment is the only way it
        // gets an answer, because this run has no terminal. The command line here
        // mirrors SFTP's exactly, and so must the environment: SshAskpass carries the
        // secret in the process environment, never in the command, and returns an
        // empty map when there is no secret, which is the BatchMode case.
        val (code, output) = SshClient.run(
            context = context,
            command = command,
            timeoutMinutes = 2,
            environment = SshAskpass.environment(context, passphrase),
        )

        val parsed = parse(output)
        if (parsed != null) {
            if (parsed !is Outcome.Ready) {
                return classify(parsed)
            }
            // Resolve the address ssh connected to, which is the one we can reach the
            // server on. See udpDestination: behind a 1:1 NAT the two are different
            // networks and only this one is reachable from the phone.
            val reported = parsed.endpoint.ip
            val resolved = resolveOnDevice(context, server.host)
            val destination = udpDestination(resolved, reported)
            if (resolved != null && resolved != reported) {
                AppLog.d(
                    context, TAG,
                    "sending UDP to $resolved (the address ssh connected to), not " +
                        "$reported (the address mosh-server bound, which is the host's " +
                        "own interface and is private behind a NAT)",
                )
            }
            if (destination == null) {
                return classify(
                    Outcome.Unavailable(
                        "the address to send mosh traffic to could not be determined",
                        "host ${server.host}, reported by the server as $reported",
                    ),
                )
            }
            return classify(Outcome.Ready(parsed.endpoint.copy(ip = destination)))
        }

        return classify(
            if (code == 255) {
                // 255 is ssh's own exit code, for everything it fails at itself —
                // including authentication. The credential was refused, so the next
                // one is worth offering.
                Outcome.Unavailable(
                    "the server refused to start mosh",
                    output.trim().takeLast(600),
                    anotherCredentialCouldHelp = true,
                )
            } else {
                // Authentication worked: 127 from the remote shell — what `exec
                // mosh-server new` leaves behind when the binary is not there — or 0
                // with no connect line on the odd host that prints an error and exits
                // cleanly. Both mean mosh-server could not be run, which is a package
                // the user can install, and another key changes nothing.
                Outcome.Unavailable(
                    "mosh-server is not installed on this server",
                    output.trim().takeLast(600),
                )
            },
        )
    }

    /**
     * The host as the device can address it, or null if it will not resolve.
     *
     * IPv4 is preferred: `mosh-server` binds whatever the SSH connection arrived on,
     * and a v4 session behind a v4 NAT is the case this whole problem exists for.
     */
    private fun resolveOnDevice(context: Context, host: String): String? = try {
        InetAddress.getAllByName(host)
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        AppLog.d(context, TAG, "could not resolve $host on this device: ${e.message}")
        null
    }

    private fun classify(outcome: Outcome): SshKeyAuthenticator.Attempt<Outcome> =
        when (outcome) {
            is Outcome.Ready -> SshKeyAuthenticator.Attempt.Ok(outcome)
            is Outcome.Unavailable ->
                if (outcome.anotherCredentialCouldHelp) {
                    SshKeyAuthenticator.Attempt.Refused(outcome.detail)
                } else {
                    SshKeyAuthenticator.Attempt.Other(outcome.reason)
                }
        }

    /**
     * Reads mosh-server's and ssh's startup lines out of a combined output stream.
     *
     * Internal rather than private so it can be tested directly: this is the only part of
     * the bootstrap with logic in it, and its failure modes are all invisible from the
     * outside -- a missing address and a missing port both arrive as "null", which reads
     * from the UI as "mosh-server is not installed" and sends the user off to install a
     * package that is already there.
     */
    internal fun parse(output: String): Outcome? {
        var port = -1
        var key: String? = null
        var ip: String? = null
        var sawConnection = false

        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            val connect = CONNECT_LINE.matchEntire(line)
            if (connect != null) {
                port = connect.groupValues[1].toInt()
                key = connect.groupValues[2]
                continue
            }
            // Older mosh-servers only ever printed the key, and the port had to be
            // guessed. Kept for completeness rather than because it is expected.
            LEGACY_KEY_LINE.matchEntire(line)?.let { key = it.groupValues[1] }
            SSH_CONNECTION_LINE.matchEntire(line)?.let { m ->
                ip = m.groupValues[2]
                sawConnection = true
            }
        }

        if (key == null || port <= 0) return null

        if (ip == null) {
            return Outcome.Unavailable(
                if (sawConnection) {
                    "the server did not report its own address"
                } else {
                    "the server did not report its own address, " +
                        "which it does only over an ordinary SSH connection"
                },
                "MOSH CONNECT was received but no address came back with it",
            )
        }

        return Outcome.Ready(Endpoint(ip, port, key))
    }

    /**
     * Where the UDP packets should actually be sent.
     *
     * This is the address ssh connected to, not the address `mosh-server` reports, and
     * the difference is the whole feature on a cloud server.
     *
     * `mosh-server` reads `$SSH_CONNECTION` and binds the server-side field. Behind a
     * 1:1 NAT — which is what a public IP on Oracle, AWS, GCP and most clouds are — the
     * ssh session arrives on the instance's *internal* interface, so that field is the
     * private address: on a real Oracle instance, `10.0.0.79`. `mosh-server` binds it
     * correctly and the cloud's NAT would forward inbound UDP to it happily, but a
     * client that trusted the number would send its packets into the VPC from the
     * internet, where nothing answers, and every session would time out having proved
     * nothing.
     *
     * The address ssh connected *to* is always reachable, because it is the one that
     * just worked: the public IP the user typed, or the Tailscale address, or whatever
     * a hostname resolved to on the device.
     *
     * [resolvedHost] is that lookup, and [reportedBy] is `$SSH_CONNECTION`'s field — kept
     * only for the case where the host cannot be resolved on the device at all.
     */
    internal fun udpDestination(resolvedHost: String?, reportedBy: String?): String? =
        resolvedHost?.takeIf { it.isNotBlank() } ?: reportedBy

    /**
     * Builds the ssh command line, mirroring the SFTP client's exactly.
     *
     * The same arguments, in the same order, for the same reasons — a divergence here
     * would mean a server that works for transfers but not for mosh, with nothing in the
     * trace pointing at the mosh side. The one difference is the trailing command:
     * `sftp` names a subsystem and `mosh-server new` is a program to run, so this one
     * needs no `-s` for the subsystem and instead passes `new` as the remote argument.
     */
    internal fun buildCommand(
        server: SshStore.Server,
        identities: List<String>,
        passphrase: String?,
    ): String = buildList {
        val batch = if (passphrase.isNullOrEmpty()) listOf("BatchMode=yes") else emptyList()
        add("/bin/ssh")
        add("-p")
        add(ProotLaunch.quoteForShell(server.port.toString()))
        for (argument in SshLaunchOptions.jumpArgs(server)) {
            add(ProotLaunch.quoteForShell(argument))
        }
        // No -C, even when the server is saved with compression on.
        //
        // This is the one place the bootstrap deliberately departs from the SFTP
        // command line it otherwise mirrors exactly. Compression is negotiated over the
        // SSH channel, and the channel here carries a single connect line and then
        // closes: the session proper is UDP with its own authenticated encryption. So it
        // compresses nothing, and a server that does not support zlib answers it with
        // "Compression not supported, disabling." on the very channel the bootstrap is
        // reading its answer from — noise in the one stream that has to be parsed
        // cleanly. mosh's own wrapper does not offer compression at all.
        for (argument in SshLaunchOptions.options(
            identities = identities,
            extraOptions = batch + listOf("ConnectTimeout=20", "ConnectionAttempts=1"),
            server = server,
        )) {
            add(ProotLaunch.quoteForShell(argument))
        }
        // Ends the options, so a host beginning with a dash cannot be taken for a flag.
        add("--")
        add(ProotLaunch.quoteForShell(SshLaunchOptions.target(server.host, server.user)))
        add(ProotLaunch.quoteForShell(REMOTE_COMMAND))
    }.joinToString(" ")
}