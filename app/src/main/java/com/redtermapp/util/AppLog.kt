package com.redtermapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A persistent on-device log the user can read, copy and share.
 *
 * Logcat is not reachable from the Settings screen and is lost when the process
 * dies, which makes diagnosing a process that exits immediately very hard. This
 * keeps a bounded ring of recent lines in a file the app owns, and the log can
 * be copied out of the app.
 */
object AppLog {

    private const val TAG = "RedTerm"
    const val MAX_BYTES = 512L * 1024L
    private const val MAX_LINES_IN_VIEW = 800

    @Volatile
    private var logFile: File? = null

    private fun file(context: Context): File {
        logFile?.let { if (it.parentFile?.exists() != false) return it }
        val dir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        val created = File(dir, "app.log")
        logFile = created
        return created
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    private fun append(context: Context, level: String, tag: String, message: String) {
        val line = "${stamp()} $level/$tag: $message\n"
        when (level) {
            "E" -> Log.e(tag, message)
            "W" -> Log.w(tag, message)
            "I" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
        try {
            val target = file(context)
            if (target.length() > MAX_BYTES) {
                // Keep the tail: the interesting part is the most recent.
                val kept = target.readBytes().drop((MAX_BYTES / 2).toInt())
                val start = kept.indexOfFirst { it == '\n'.code.toByte() }
                target.writeBytes(if (start >= 0) kept.drop(start + 1).toByteArray() else kept.toByteArray())
            }
            target.appendText(line)
        } catch (_: Exception) {
        }
    }

    fun d(context: Context, tag: String, message: String) =
        append(context, "D", tag, message)

    fun i(context: Context, tag: String, message: String) =
        append(context, "I", tag, message)

    fun w(context: Context, tag: String, message: String) =
        append(context, "W", tag, message)

    fun e(context: Context, tag: String, message: String) =
        append(context, "E", tag, message)

    /**
     * Captures the whole process's log, not just this app's own breadcrumbs.
     *
     * logcat shows an app every entry logged by its own UID, which includes
     * anything the AndroidX, proot and support libraries emit, so a failure deep
     * in a dependency is visible. `--pid` is used where the platform supports it
     * and the output is filtered by PID otherwise, because older logcat builds
     * do not accept the flag.
     */
    fun captureProcessLog(context: Context): String {
        val pid = android.os.Process.myPid()
        val command = ProcessBuilder(
            "/system/bin/logcat", "-d", "-v", "threadtime", "--pid=$pid"
        ).redirectErrorStream(true)
        var text = ""
        try {
            val process = command.start()
            text = process.inputStream.readBytes().toString(Charsets.UTF_8)
            SshClientWait(process)
        } catch (e: Exception) {
            AppLog.append(context, "W", "applog", "logcat --pid failed: ${e.message}")
            // Older logcat builds reject --pid, so retry and filter by hand.
            text = try {
                val fallback = ProcessBuilder(
                    "/system/bin/logcat", "-d", "-v", "threadtime"
                ).redirectErrorStream(true).start()
                val all = fallback.inputStream.readBytes().toString(Charsets.UTF_8)
                SshClientWait(fallback)
                all.lineSequence()
                    .filter { line ->
                        val fields = line.trim().split(' ')
                        fields.size > 3 && fields.getOrNull(1)?.toIntOrNull() == pid
                    }
                    .joinToString("\n")
            } catch (e2: Exception) {
                "logcat unavailable: ${e2.message}"
            }
        }
        val header = "\n===== logcat for pid $pid =====\n"
        try {
            file(context).appendText(header + text.trim() + "\n")
        } catch (_: Exception) {
        }
        return text
    }

    private fun SshClientWait(process: Process) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                return
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(150)
            }
        }
        process.destroy()
    }

    fun logFile(context: Context): File = file(context)

    fun exists(context: Context): Boolean = file(context).length() > 0

    fun clear(context: Context) {
        try {
            file(context).writeText("")
        } catch (_: Exception) {
        }
    }

    /** The most recent lines, newest last. */
    fun tail(context: Context, maxLines: Int = MAX_LINES_IN_VIEW): String {
        val text = try {
            file(context).readText()
        } catch (_: Exception) {
            return ""
        }
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= maxLines) return lines.joinToString("\n")
        return lines.takeLast(maxLines).joinToString("\n")
    }
}
