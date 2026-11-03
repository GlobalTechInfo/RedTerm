package com.redtermapp.util.api

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-download`. The system download manager is a content provider
 * request, so no dangerous permission is required. The request returns
 * the download's ID so the guest can watch it, or an ERROR line if the
 * URL is malformed. The download shows a notification and completes
 * in the user's Downloads folder unless a destination path is given.
 */
object DownloadApi : ApiFeature {

    override val name = "download"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val url = request.str("url") ?: return "ERROR: no URL provided\n"
        val uri = url.toUri()
        if (!uri.isAbsolute) return "ERROR: the URL must be absolute (include the scheme)\n"

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val r = DownloadManager.Request(uri)
        val title = request.str("title") ?: "Downloaded by RedTerm"
        r.setTitle(title)
        r.setDescription(request.str("description") ?: title)
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        r.setVisibleInDownloadsUi(true)
        request.str("path")?.let {
            r.setDestinationUri(Uri.fromFile(File(it)))
        }

        val id = manager.enqueue(r)
        return "{\"downloadId\":$id}\n"
    }
}
