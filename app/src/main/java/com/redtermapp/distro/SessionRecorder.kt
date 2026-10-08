package com.redtermapp.distro

import android.content.Context
import com.redtermapp.R
import com.redtermapp.util.AppLog
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

    /**
     * Where recordings live.
     *
     * App-specific external storage rather than `/sdcard/RedTerm`. The shared
     * directory needs "all files access" to write or list on Android 11 and later,
     * and without that grant every read comes back empty and every write goes
     * nowhere — so the list was permanently empty and every recording failed, with
     * nothing on screen to say why.
     *
     * `getExternalFilesDir` is the app's own area, which needs no grant at all, and
     * `/sdcard` is bind-mounted into the distro rootfs, so `script` running inside
     * proot writes to the same file the app reads. Falls back to internal storage
     * when no external volume is mounted.
     */
    fun dir(): File = (
        context.getExternalFilesDir(null)
            ?.let { File(it, "RedTerm/recordings") }
            ?: File(context.filesDir, "RedTerm/recordings")
        ).apply { if (!exists()) mkdirs() }

    /** Where the directory actually ended up, for telling the user where to find it. */
    fun describeDir(): String = dir().absolutePath

    fun list(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".ansi") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Whether the directory can be written to, checked rather than assumed. */
    fun isWritable(): Boolean = dir().canWrite()

    /**
     * The command a shell runs under so the whole session is captured, or null when
     * the distro cannot.
     *
     * The shell is started as `script <file>` rather than wrapped after the fact. The
     * terminal library offers no hook on what a session writes, so there is nothing
     * to attach to later — `script` allocates a pty and the session's output passes
     * through it, which is also what keeps colour and cursor control in the capture.
     *
     * `-f` flushes after every write, so a session that ends badly still leaves a
     * usable file rather than an empty one.
     *
     * Null when the distro has no `script`. Reported rather than silently recording
     * nothing, which is what the previous version did.
     */
    fun sessionCommand(distroName: String, runner: DistroRunner): String? {
        if (!runner.hasBinary(distroName, "script")) return null
        val out = newFile(distroName)
        val quoted = quoteForShell(out.absolutePath)
        // -q no header, -a append so a restart does not truncate, -f flush.
        return "script -q -a -f $quoted"
    }

    /** Where the next recording for [distroName] would land. */
    fun newFile(distroName: String): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val safe = distroName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "distro" }
        return File(dir(), "redterm-$stamp-$safe.ansi")
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    /** How many recordings there are. */
    fun count(): Int = list().size

    /**
     * Whether a capture finished with something in it.
     *
     * Called when a recorded session ends: `script` has exited by then and flushed,
     * but a shell that was killed rather than exited can leave a file with no content,
     * and an empty file in the list is a worse thing than no file.
     */
    fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L

    private fun quoteForShell(command: String): String =
        "'" + command.replace("'", "'\\''") + "'"

    data class Result(val recording: Recording?, val error: String?)
}
