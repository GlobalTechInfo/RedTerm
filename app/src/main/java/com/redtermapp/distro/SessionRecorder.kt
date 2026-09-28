package com.redtermapp.distro

import android.content.Context
import com.redtermapp.R
import java.io.File

/**
 * Records the real output of a command, escape sequences included.
 *
 * The terminal library exposes no raw output hook (its only byte-level entry
 * point feeds the process stdin), so the capture is done the way a person
 * would do it: run the command under `script`, which allocates a pty and
 * therefore keeps colours and cursor control intact.
 */
class SessionRecorder(private val context: Context) {

    data class Recording(val file: File, val command: String)

    fun dir(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "RedTerm/recordings")
            .apply { if (!exists()) mkdirs() }

    fun list(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".ansi") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun newFile(command: String): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val slug = command.replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-').take(24).ifBlank { "session" }
        return File(dir(), "redterm-$stamp-$slug.ansi")
    }

    /**
     * @return the recording, or a failure explaining why capture is unavailable.
     */
    fun record(
        distroName: String,
        command: String,
        runner: DistroRunner,
        onOutput: ((String) -> Unit)? = null
    ): Result {
        if (!runner.hasBinary(distroName, "script")) {
            return Result(
                null,
                context.getString(R.string.record_script_missing, distroName)
            )
        }
        val out = newFile(command)
        // The path is inside the rootfs-visible /sdcard bind, and quoted so a
        // command containing quotes or spaces cannot break the wrapper.
        val quoted = "\"" + out.absolutePath.replace("\"", "") + "\""
        val result = runner.run(
            distroName = distroName,
            // -a appends, -q keeps the header quiet, -e returns the child's code.
            command = "script -q -a -e -c ${quoteForShell(command)} $quoted",
            timeoutMinutes = 30,
            onOutput = onOutput
        )
        // `script` reports the child's status; a non-zero exit here is the
        // command failing, not the capture failing.
        if (!out.exists() || out.length() == 0L) {
            out.delete()
            return Result(null, result.output.takeLast(300).ifBlank { "exit ${result.exitCode}" })
        }
        return Result(Recording(out, command), null)
    }

    private fun quoteForShell(command: String): String =
        "'" + command.replace("'", "'\\''") + "'"

    data class Result(val recording: Recording?, val error: String?)
}
