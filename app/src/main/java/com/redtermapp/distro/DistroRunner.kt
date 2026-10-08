package com.redtermapp.distro

import android.content.Context
import com.redtermapp.R
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a shell command inside a distro and collects its output.
 *
 * This goes through the same proot launcher as the interactive terminal, so
 * package updates behave identically to running them by hand.
 */
class DistroRunner(private val context: Context) {

    data class Result(
        val command: String,
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * @param timeoutMinutes guards against a package manager waiting on input;
     *   every command passed here is expected to run non-interactively.
     */
    /**
     * A handle for stopping a run that is still going.
     *
     * A long job with no way to stop it is one the user has to wait out, and an
     * ongoing notification they cannot dismiss is the visible form of that.
     */
    class CancelSignal {
        @Volatile
        private var cancelled = false
        val isCancelled: Boolean get() = cancelled
        fun cancel() { cancelled = true }
    }

    fun run(
        distroName: String,
        command: String,
        timeoutMinutes: Long = 20,
        onOutput: ((String) -> Unit)? = null,
        cancel: CancelSignal? = null
    ): Result {
        val installer = DistroInstaller(context)
        val rootfsDir = installer.getRootfsDir(distroName)
        if (!rootfsDir.isDirectory) {
            return Result(command, -1, context.getString(R.string.distro_not_installed), false)
        }
        File(rootfsDir, "tmp").mkdirs()
        installer.refreshNetworkConfig(distroName)
        val launcher = ProotLaunch.writeLauncher(
            context = context,
            rootfsDir = rootfsDir,
            startInner = "/root",
            command = command,
            // Its own script: sharing launch.sh with the terminal meant a
            // background update could rewrite the file the terminal was starting.
            scriptName = "command-run.sh"
        )
        return try {
            val process = ProcessBuilder("/system/bin/sh", launcher.absolutePath)
                .redirectErrorStream(true)
                .start()
            val collected = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val pump = Thread {
                try {
                    reader.forEachLine { line ->
                        synchronized(collected) {
                            if (collected.length < MAX_OUTPUT) {
                                collected.append(line).append('\n')
                            }
                        }
                        onOutput?.invoke(line)
                    }
                } catch (_: Exception) {
                }
            }
            pump.isDaemon = true
            pump.start()
            if (cancel?.isCancelled == true) process.destroy()
            val finished = awaitExit(process, timeoutMinutes, cancel)
            if (!finished) {
                process.destroy()
                // The reader thread is a daemon, but give it a moment to flush
                // whatever was already written so the user sees partial output.
                pump.join(500)
            }
            val code = if (finished) process.exitValue() else -1
            val output = synchronized(collected) { collected.toString() }.trim()
            Result(command, code, output, !finished)
        } catch (e: Exception) {
            Log.w("DistroRunner", "Command failed: $command", e)
            Result(command, -1, e.message ?: e.javaClass.simpleName, false)
        }
    }

    /**
     * Process.waitFor(timeout, unit) only exists from API 26, so on older
     * devices the exit value is polled instead.
     */
    private fun awaitExit(
        process: Process,
        timeoutMinutes: Long,
        cancel: CancelSignal? = null
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                if (cancel?.isCancelled == true) {
                    process.destroy()
                    // Give it a moment to actually go, then stop asking.
                    Thread.sleep(300)
                    return false
                }
                Thread.sleep(200)
            }
        }
        return false
    }

    /** Convenience for quick probes such as `command -v ssh`. */
    fun capture(distroName: String, command: String): String? =
        run(distroName, command, timeoutMinutes = 2).output.takeIf { it.isNotBlank() }

    fun hasBinary(distroName: String, binary: String): Boolean {
        val rootfs = DistroInstaller(context).getRootfsDir(distroName)
        val found = generateSequence(rootfs) { it.parentFile }
            .map { File(it, "bin/$binary") }
            .any { it.exists() }
        if (found) return true
        // A package may install it under /usr/bin or sbin.
        return generateSequence(rootfs) { it.parentFile }
            .map { listOf(File(it, "usr/bin/$binary"), File(it, "usr/sbin/$binary")) }
            .any { list -> list.any { it.exists() } }
    }

    private companion object {
        /** Enough for a full `apt upgrade` transcript without unbounded growth. */
        const val MAX_OUTPUT = 400_000
    }
}
