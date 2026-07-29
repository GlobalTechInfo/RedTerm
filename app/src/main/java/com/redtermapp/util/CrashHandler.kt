package com.redtermapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {
    private var enabled = false

    fun init(context: Context) {
        if (enabled) return
        enabled = true
        val crashDir = File(context.filesDir, "crash")
        crashDir.mkdirs()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(crashDir, "crash_$dateStr.log")
            try {
                FileWriter(file).use { writer ->
                    writer.write("Time: $dateStr\n")
                    writer.write("Thread: ${thread.name} (${thread.id})\n")
                    writer.write("Message: ${throwable.message}\n\n")
                    writer.write("Stack trace:\n")
                    for (element in throwable.stackTrace) {
                        writer.write("\tat ${element.toString()}\n")
                    }
                    if (throwable.cause != null) {
                        writer.write("\nCaused by: ${throwable.cause}\n")
                        for (element in throwable.cause!!.stackTrace) {
                            writer.write("\tat ${element.toString()}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            Log.e("CrashHandler", "Uncaught exception in ${thread.name}", throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
}
