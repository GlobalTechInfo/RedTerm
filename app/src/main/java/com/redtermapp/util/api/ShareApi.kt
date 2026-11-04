package com.redtermapp.util.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-share`: hands the given text or files to the system share
 * sheet. Nothing here needs a permission that is not already declared:
 * an `INTERNET`/`ACTION_SEND` share of the app's own files goes through
 * the FileProvider the app already exposes.
 */
object ShareApi : ApiFeature {

    override val name = "share"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val text = request.str("text")
        val files = (request.extras.stringArrays["files"] ?: emptyArray())
            .filter { it.isNotEmpty() }
            .toList()
        if (text.isNullOrEmpty() && files.isEmpty()) {
            return "ERROR: share needs text and/or files\n"
        }
        val intent = if (files.isNotEmpty()) {
            val uris = filesToUris(context, request, files)
                ?: return "ERROR: a file could not be shared (see the app log for which)\n"
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(request.str("mime") ?: "*/*")
                // ACTION_SEND_MULTIPLE's contract expects the streams in an
                // ArrayList extra; a typed array is what receivers that
                // follow the contract omit or reject.
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { if (!text.isNullOrEmpty()) putExtra(Intent.EXTRA_TEXT, text) }
        } else {
            Intent(Intent.ACTION_SEND)
                .setType(request.str("mime") ?: "text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        }
        request.str("subject")?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ""
    }

    /** The provider URIs for the guest-named files, or null when one cannot be projected. */
    private fun filesToUris(context: Context, request: ApiRequest, paths: List<String>): List<Uri>? {
        val uris = ArrayList<Uri>(paths.size)
        for (path in paths) {
            val host = ApiPaths.hostPath(context, request, path)
            if (host == null) {
                AppLog.w(context, "api-share", "cannot share $path: ${ApiPaths.unreachable(path)}")
                return null
            }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(host)))
        }
        return uris
    }
}
