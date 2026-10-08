package com.redtermapp.ui

import android.content.Context
import android.util.Log
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import com.redtermapp.util.sftp.SshAskpass
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
        AppLog.i(context, "ssh", "tunnel to ${server.label} up: ${forwards.joinToString(", ") { it.spec }}")
        return true
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
        AppLog.i(context, "ssh", "tunnel to ${entry.label} stopped")
    }

    fun stopAll(context: Context) {
        for (id in running.keys.toList()) stop(context, id)
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