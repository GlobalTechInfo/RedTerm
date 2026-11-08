package com.redtermapp.ui

import android.content.Context
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import com.redtermapp.util.sftp.SshAskpass
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A forward that stays open with no terminal in front of it.
 *
 * The old "Port forward" button was a single *device-local* relay and stopped
 * there. A user working over SSH wants the opposite: a device port that reaches a
 * remote host's port *through* a server. That is `ssh -N -L from:toHost:toPort`,
 * exactly the shape TunnelManager already opens for the forwards saved on a server —
 * but ad-hoc, against a named target, and open for as long as the user says so.
 *
 * [Spec] is pure and unit-tested. [ForwardManager] runs them, one entry per
 * forward, so any number stay open at once and any number of servers are
 * reachable at the same time.
 */
object ForwardManager {

    /** One forward. [forward] is the port mapping the screen shows; [summary] names the
     *  target for the log. */
    sealed class Spec {
        abstract val fromPort: Int
        abstract val toHost: String
        abstract val toPort: Int
        abstract val forward: String
        abstract val summary: String
        /** True when this forward goes through a server; a local relay is false. */
        abstract fun viaSsh(): Boolean
    }

    /** Device port -> [toHost]:[toPort] on the device itself. For a guest service. */
    data class LocalSpec(
        override val fromPort: Int,
        override val toHost: String,
        override val toPort: Int
    ) : Spec() {
        override fun viaSsh() = false
        override val forward get() = "$fromPort -> $toHost:$toPort"
        override val summary get() = "device: $forward"
    }

    /** Device port -> [toHost]:[toPort] on the remote side of [server], via `ssh -L`. */
    data class SshSpec(
        override val fromPort: Int,
        override val toHost: String,
        override val toPort: Int,
        val server: SshStore.Server,
        val identities: List<String>
    ) : Spec() {
        override fun viaSsh() = true
        override val forward get() = "$fromPort -> $toHost:$toPort"
        override val summary get() = "${server.label}: $forward"
    }

    /** The [ssh -L] argument that carries one [SshSpec], or null for a local relay. */
    fun forwardArg(spec: Spec): String? =
        when (spec) {
            is LocalSpec -> null
            is SshSpec -> "-L${spec.fromPort}:${spec.toHost}:${spec.toPort}"
        }

    private const val TAG = "ForwardManager"
    private val sequence = AtomicInteger(0)

    private class Running(
        val id: String,
        val spec: Spec,
        val relay: LocalPortForward.LocalPortRelay?,
        val process: Process?,
        val script: File?
    )

    private val active = ConcurrentHashMap<String, Running>()

    fun activeIds(): Set<String> = active.keys.toSet()

    fun specFor(id: String): Spec? = active[id]?.spec

    fun summaryFor(id: String): String? = active[id]?.spec?.summary

    /** Starts a forward. Returns its id, or null when it could not be opened. */
    fun start(context: Context, spec: Spec): String? {
        val id = "forward-" + sequence.incrementAndGet()
        val running = when (spec) {
            is LocalSpec -> {
                val relay = LocalPortForward.LocalPortRelay(
                    LocalPortForward.Spec(spec.fromPort, spec.toHost, spec.toPort)
                )
                if (relay.start()) Running(id, spec, relay, null, null)
                else {
                    AppLog.w(context, "port-forward", "local relay ${spec.summary} did not bind")
                    return null
                }
            }
            is SshSpec -> startSsh(context, id, spec) ?: return null
        }
        active[id] = running
        AppLog.i(context, "port-forward", "forward up: ${spec.summary}")
        return id
    }

    /** Builds and runs the `ssh -N -L` process for an [SshSpec]. Null when it cannot. */
    private fun startSsh(context: Context, id: String, spec: SshSpec): Running? {
        if (!SshClient.ensureInstalled(context)) {
            AppLog.w(context, TAG, "ssh is not installed; cannot open ${spec.summary}")
            return null
        }
        val secret = SshPasswordPrompt.available(context, spec.server)
        val args = mutableListOf("/bin/ssh", "-N", forwardArg(spec))
        args.addAll(
            SshLaunchOptions.forServer(
                server = spec.server,
                identities = spec.identities,
                // Only this forward, not the server's saved set: an ad-hoc
                // forward is one port, and opening every saved forward too is
                // not what the user asked for.
                extraOptions = if (secret.isEmpty()) listOf("BatchMode=yes") else emptyList(),
                withForwards = false
            )
        )
        val script = try {
            com.redtermapp.distro.ProotLaunch.writeLauncher(
                context = context,
                rootfsDir = SshClient.rootfs(context),
                startInner = "/root",
                command = args.joinToString(" "),
                scriptName = "forward-$id.sh"
            )
        } catch (e: Exception) {
            AppLog.w(context, TAG, "could not write the forward launcher: ${e.message}")
            return null
        }
        val process = try {
            ProcessBuilder("/system/bin/sh", script.absolutePath)
                .apply {
                    environment().putAll(SshAskpass.environment(context, secret))
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            AppLog.w(context, TAG, "forward ${spec.summary} did not start: ${e.message}")
            runCatching { script.delete() }
            return null
        }
        // The output is drained to a log rather than redirected to a file:
        // Redirect.appendTo is API 26, and a pipe nobody reads would eventually
        // fill and kill the very ssh this is standing up for.
        Thread({
            runCatching {
                process.inputStream.copyTo(
                    java.io.FileOutputStream(File(context.cacheDir, "forward-$id.log"), true)
                )
            }
        }, "redterm-forward-output").apply {
            isDaemon = true
            start()
        }
        return Running(id, spec, null, process, script)
    }

    fun stop(context: Context, id: String) {
        val entry = active.remove(id) ?: return
        entry.relay?.close()
        entry.process?.destroy()
        entry.script?.delete()
        AppLog.i(context, "port-forward", "forward stopped: ${entry.spec.summary}")
    }

    fun stopAll(context: Context) {
        for (id in active.keys.toList()) stop(context, id)
    }

    /**
     * Whether a forward is still standing. An ssh process dies on its own when
     * the server drops or the port is taken; checked on every read, the way
     * TunnelManager checks, and the entry is dropped the moment it is not.
     */
    fun isAlive(id: String): Boolean {
        val entry = active[id] ?: return false
        entry.relay?.let {
            val alive = it.isRunning()
            if (!alive) active.remove(id)
            return alive
        }
        val alive = try {
            entry.process!!.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
        if (!alive) active.remove(id)
        return alive
    }
}
