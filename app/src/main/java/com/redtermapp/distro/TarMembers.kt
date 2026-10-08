package com.redtermapp.distro

import java.io.File

/**
 * Counts an archive's members by listing it, and nothing else.
 *
 * Both backup and restore need this, and both needed it for the same reason: a
 * non-empty archive is not a usable one, so each has to be able to read what it wrote
 * before trusting it.
 *
 * **stderr is drained on its own thread.** A process pipe holds about 64 KiB, and a
 * writer that fills it blocks forever while its reader waits for the writer to exit.
 * Counting stdout while leaving stderr unread is that deadlock, and it looks exactly
 * like a hang with no error anywhere — tar is not stuck, it is waiting for us to read a
 * pipe we never opened. It only bites on an archive tar has something to say about, so
 * it arrives on precisely the archives a user most needs to inspect.
 */
object TarMembers {

    /** The member count, or -1 when the archive cannot be listed at all. */
    fun count(archive: File): Int {
        if (!archive.isFile) return -1
        val process = ProcessBuilder("tar", "-tzf", archive.absolutePath)
            .redirectErrorStream(false)
            .start()
        val errors = StringBuilder()
        val drain = Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (errors.length < 2000) errors.append(line).append('\n')
                }
            } catch (_: Exception) {
                // Nothing to do: the listing's own result is what decides success.
            }
        }, "tar-stderr")
        drain.isDaemon = true
        drain.start()

        var count = 0
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotEmpty()) count++
            }
        } catch (_: Exception) {
            drain.join(2000)
            return -1
        }
        // A truncated gzip stream fails mid-list rather than returning a short count, but
        // a non-zero exit is the reliable signal, so it is checked rather than inferred.
        val code = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            // destroy(), not destroyForcibly(): the latter is API 26 and this app
            // supports 24. A plain destroy is enough to release the pipes.
            process.destroy()
            return -1
        }
        drain.join(2000)
        return if (code == 0) count else -1
    }
}