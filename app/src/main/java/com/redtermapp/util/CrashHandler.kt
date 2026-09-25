package com.redtermapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    const val MAX_REPORT_CHARS = 4096
    const val MAX_REPORT_FILES = 9
    private const val CRASH_DIR = "crash"

    @Volatile
    private var enabled = false

    fun init(context: Context) {
        if (enabled) return
        enabled = true
        val appContext = context.applicationContext
        crashDir(appContext).mkdirs()
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendReport(appContext, buildReport(thread, throwable))
            } catch (e: Throwable) {
                Log.e("CrashHandler", "Failed to write crash report", e)
            }
            Log.e("CrashHandler", "Uncaught exception in ${thread.name}", throwable)
            existingHandler?.uncaughtException(thread, throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR)

    /** Report files in chronological order: crash-report.txt, crash-report2.txt, ... */
    fun reportFiles(context: Context): List<File> {
        val dir = crashDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.startsWith("crash-report") }
            ?.sortedBy { reportIndex(it.name) }
            ?: emptyList()
    }

    fun hasReports(context: Context): Boolean = reportFiles(context).isNotEmpty()

    fun readReports(context: Context): String =
        reportFiles(context).joinToString("\n") { it.readText() }.trim()

    fun clearReports(context: Context) {
        reportFiles(context).forEach { it.delete() }
    }

    private fun reportIndex(name: String): Int {
        if (name == "crash-report.txt") return 1
        return name.removePrefix("crash-report").removeSuffix(".txt").toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun reportFileName(index: Int): String =
        if (index <= 1) "crash-report.txt" else "crash-report$index.txt"

    /**
     * Appends to crash-report.txt, rolling over to crash-report2.txt (and so on)
     * once the current file would exceed [MAX_REPORT_CHARS] characters.
     */
    fun appendReport(context: Context, report: String) {
        val dir = crashDir(context)
        dir.mkdirs()
        for (index in 1..MAX_REPORT_FILES) {
            val file = File(dir, reportFileName(index))
            val current = if (file.isFile) file.readText() else ""
            if (current.length + report.length <= MAX_REPORT_CHARS) {
                file.writeText(current + report)
                return
            }
        }
        val last = File(dir, reportFileName(MAX_REPORT_FILES))
        last.writeText(report.take(MAX_REPORT_CHARS))
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("\n===== Crash ").append(timestamp).append(" =====\n")
            append("Thread: ").append(thread.name).append('\n')
            append("Message: ").append(throwable.message ?: throwable.toString()).append("\n\n")
            append(throwable.stackTraceToString())
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                append("\nCaused by: ").append(cause.toString()).append('\n')
                append(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }
    }
}
