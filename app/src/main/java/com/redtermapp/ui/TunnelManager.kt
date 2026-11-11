package com.redtermapp.ui

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import com.redtermapp.util.sftp.SshAskpass
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone tunnels: `ssh -N` with forwards and no remote command.
 *
 * A forward listed on a server opens with the session and closes when it ends, which
 * covers most uses. A tunnel is what you want when the port has to be open while
 * nothing is being typed — a database client connecting to a forwarded port, with no
 * terminal in sight — so it needs its own process and its own lifetime.
 *
 * Each process is recorded by server id, so a tunnel survives the activity that
 * started it and is stopped when the app's session service stops.
 */
object TunnelManager {

    private const val TAG = "TunnelManager"

    private class Running(
        val serverId: String,
        val label: String,
        val specs: List<String>,
        val process: Process,
        val script: File
    )

    private val running = ConcurrentHashMap<String, Running>()

    fun activeServers(): Set<String> = running.keys.toSet()

    fun specsFor(serverId: String): List<String> = running[serverId]?.specs ?: emptyList()

    fun isRunning(serverId: String): Boolean = running.containsKey(serverId)

    /**
     * Starts a tunnel, or does nothing if one is already up for that server.
     *
     * The script is kept, not deleted on the way out as one-shot commands are: the
     * process is still reading it for as long as it runs, and deleting it mid-flight
     * is how the command it was given becomes unaccountable.
     */
    fun start(context: Context, server: SshStore.Server, identities: List<String>): Boolean {
        val serverId = server.id
        if (isRunning(serverId)) return true
        val forwards = server.forwards.filter { it.enabled && it.usable }
        if (forwards.isEmpty()) return false
        val secret = SshPasswordPrompt.available(context, server)

        val args = mutableListOf("/bin/ssh", "-N")
        args.addAll(
            SshLaunchOptions.forServer(
                server = server,
                identities = identities,
                // BatchMode only while there is no secret to offer: with one, ssh is
                // allowed to reach for the askpass helper.
                extraOptions = if (secret.isEmpty()) listOf("BatchMode=yes") else emptyList()
            )
        )
        val command = args.joinToString(" ")
        val script = try {
            com.redtermapp.distro.ProotLaunch.writeLauncher(
                context = context,
                rootfsDir = SshClient.rootfs(context),
                startInner = "/root",
                command = command,
                scriptName = "tunnel-${server.id}.sh"
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not write the tunnel launcher", e)
            return false
        }

        val process = try {
            ProcessBuilder("/system/bin/sh", script.absolutePath)
                .apply {
                    environment().putAll(SshAskpass.environment(context, secret))
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            AppLog.w(context, "ssh", "tunnel to ${server.label} did not start: ${e.message}")
            runCatching { script.delete() }
            return false
        }
        // The output is drained on a thread rather than redirected to a file:
        // `Redirect.appendTo` is API 26 and this app runs from 24. A pipe nobody reads
        // would eventually fill and stop the tunnel, which `ssh -N` would cause by
        // saying one line and then going quiet.
        Thread({
            runCatching {
                process.inputStream.copyTo(
                    java.io.FileOutputStream(
                        File(context.cacheDir, "tunnel-${server.id}.log"), true
                    )
                )
            }
        }, "redterm-tunnel-output").apply {
            isDaemon = true
            start()
        }
        running[server.id] = Running(
            serverId = server.id,
            label = server.label,
            specs = forwards.map { it.spec },
            process = process,
            script = script
        )
        rememberTunnel(context, server.id)
        AppLog.i(context, "ssh", "tunnel to ${server.label} up: ${forwards.joinToString(", ") { it.spec }}")
        return true
    }

    /**
     * Re-opens the tunnels the last process had standing, after [OrphanSweeper]
     * has reaped the orphans. A server that no longer exists is dropped from
     * the record rather than started against a hole.
     */
    fun resumeAll(context: Context): Int {
        val servers = SshStore.load(context).associateBy { it.id }
        var resumed = 0
        for (serverId in decodeTunnels(context)) {
            val server = servers[serverId] ?: continue
            if (start(context, server, SshKeyStore.identitiesFor(context, server))) {
                resumed++
            }
        }
        return resumed
    }

    /**
     * Stops a tunnel.
     *
     * The process is destroyed rather than waited on: it is blocked in a network read
     * with a terminal that does not exist, so it will not leave on its own.
     */
    fun stop(context: Context, serverId: String) {
        val entry = running.remove(serverId) ?: return
        runCatching { entry.process.destroy() }
        runCatching { entry.script.delete() }
        forgetTunnel(context, serverId)
        AppLog.i(context, "ssh", "tunnel to ${entry.label} stopped")
    }

    fun stopAll(context: Context) {
        for (id in running.keys.toList()) stop(context, id)
        clearTunnels(context)
    }

    // The tunnel record is on disk, not in the map: a dead process orphans its
    // `ssh -N` and the map is gone with it, so "which tunnels were open" has to
    // outlive the process to be re-opened on the next start.
    private const val TUNNEL_PREFS = "ssh_tunnels"
    private const val TUNNEL_KEY = "active"

    private fun tunnels(context: Context) = decodeTunnels(context)

    private fun rememberTunnel(context: Context, serverId: String) {
        val ids = tunnels(context).filterNot { it == serverId } + serverId
        context.getSharedPreferences(TUNNEL_PREFS, Context.MODE_PRIVATE).edit {
            putString(TUNNEL_KEY, encodeTunnels(ids))
        }
    }

    private fun forgetTunnel(context: Context, serverId: String) {
        context.getSharedPreferences(TUNNEL_PREFS, Context.MODE_PRIVATE).edit {
            putString(TUNNEL_KEY, encodeTunnels(tunnels(context).filterNot { it == serverId }))
        }
    }

    private fun clearTunnels(context: Context) {
        context.getSharedPreferences(TUNNEL_PREFS, Context.MODE_PRIVATE).edit {
            remove(TUNNEL_KEY)
        }
    }

    fun encodeTunnels(ids: List<String>): String =
        JSONArray().apply { ids.forEach { put(it) } }.toString()

    /** Tolerant: a record that fails to parse is no tunnels, not a cold-start crash. */
    fun decodeTunnels(context: Context): List<String> =
        decodeTunnelIds(
            context.getSharedPreferences(TUNNEL_PREFS, Context.MODE_PRIVATE)
                .getString(TUNNEL_KEY, null)
        )

    /** Pure, so the reader is unit-tested: a corrupt record is no tunnels. */
    fun decodeTunnelIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Whether a tunnel is still alive.
     *
     * Checked on every status read rather than trusted: a tunnel dies on its own when
     * the remote host goes away or a port is taken, and the only way to know is to
     * look.
     */
    fun isAlive(context: Context, serverId: String): Boolean {
        val entry = running[serverId] ?: return false
        // exitValue() rather than isAlive(): the latter is API 26 and this runs from
        // 24. It throws while the process lives, which is the answer wanted.
        val running = try {
            entry.process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
        if (running) return true
        stop(context, serverId)
        return false
    }
}