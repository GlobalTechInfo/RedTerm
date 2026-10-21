package com.redtermapp.ui

import android.content.Context
import java.io.File

/**
 * Owns the recordings directory: listing, naming and deletion.
 *
 * App-specific external storage rather than `/sdcard/RedTerm`. The shared directory
 * needs "all files access" to write or list on Android 11 and later, and without
 * that grant every read comes back empty and every write goes nowhere — so the list
 * was permanently empty and every recording failed, with nothing on screen to say
 * why. `getExternalFilesDir` is the app's own area, which needs no grant at all.
 * Falls back to internal storage when no external volume is mounted.
 *
 * What goes *into* the files is decided elsewhere: the frame recorder in
 * [TerminalActivity] captures the emulator, so a recording can be started on any
 * session, running or not, and needs nothing from the distro it was made in.
 */
class SessionRecorder(private val context: Context) {

    /** Where recordings live. */
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
     * The file the next recording lands in.
     *
     * [label] names what is being recorded (a distro or an ssh host, or whatever the
     * session is labelled); it goes in the file name, so a listing tells the user
     * what they recorded without opening anything.
     */
    fun newFile(label: String): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val safe = label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "session" }
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
     * A session that ends before its first drawn frame leaves an empty file, and an
     * empty file in the list is a worse thing than no file.
     */
    fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L
}
