package com.redtermapp.util.api

import android.content.Context
import android.media.MediaScannerConnection
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-media-scan`: hand the given paths to the media content provider
 * so they show up in gallery/players. The scan is fired through
 * [MediaScannerConnection.scanFile]; a directory is walked only when
 * recursive was requested.
 */
object MediaScannerApi : ApiFeature {

    override val name = "media-scan"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        // An empty element is a malformed request from the guest side (an
        // empty PATHS still arrives as one empty string, and File("")
        // resolves to the process cwd — a warning about "/" that points at
        // nothing). Filter before it can masquerade as a real path.
        val paths = (request.extras.stringArrays["paths"] ?: emptyArray())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paths.isEmpty()) return "ERROR: no paths given\n"
        val recursive = request.bool("recursive") == true
        val verbose = request.bool("verbose") == true
        var total = 0
        val out = StringBuilder()
        for (path in paths) {
            val file = File(path)
            when {
                file.isFile -> {
                    scan(context, file, out, verbose)
                    total++
                }
                file.isDirectory -> {
                    val queue = ArrayDeque<File>()
                    queue.add(file)
                    while (queue.isNotEmpty()) {
                        val dir = queue.removeFirst()
                        for (entry in dir.listFiles().orEmpty()) {
                            if (entry.isDirectory && recursive) queue.add(entry)
                            else if (entry.isFile) {
                                scan(context, entry, out, verbose)
                                total++
                            }
                        }
                    }
                }
                else -> out.append("Warning: does not exist: ").append(path).append('\n')
            }
        }
        out.append("Finished scanning ").append(total).append(" file(s)\n")
        return out.toString()
    }

    private fun scan(context: Context, file: File, out: StringBuilder, verbose: Boolean) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
        ) { _, _ ->
            // The completion listener runs on the main thread after the
            // response is already sent; it only tells us the scan did not
            // happen, which is where verbose output would belong.
        }
        if (verbose) out.append("Scanned: ").append(file.absolutePath).append('\n')
    }
}
