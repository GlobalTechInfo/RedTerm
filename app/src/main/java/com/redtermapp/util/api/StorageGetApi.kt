package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-storage-get`: describe a file the guest names. The reference
 * speaks it over SAF; RedTerm's bridge runs as the app's uid inside the
 * distro's own storage, so a plain `java.io.File` read is the honest
 * answer. The result is a one-line JSON object the guest can parse.
 */
object StorageGetApi : ApiFeature {

    override val name = "storage-get"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val guestPath = request.str("file") ?: return "ERROR: file path not passed\n"
        val path = ApiPaths.hostPath(request, guestPath)
            ?: return ApiPaths.unreachable(guestPath)
        val f = File(path)
        if (!f.exists()) return "ERROR: $guestPath does not exist\n"
        val sb = StringBuilder("{")
        sb.append("\"name\":").append(json(f.name))
        // The answer speaks the guest's namespace: the caller named a
        // guest path and gets a guest path back, not the app's internal
        // projection of it.
        sb.append(",\"uri\":\"file://$guestPath\"")
        sb.append(",\"type\":").append(if (f.isDirectory) "\"dir\"" else "\"file\"")
        if (f.isFile) {
            sb.append(",\"length\":").append(f.length())
            sb.append(",\"last_modified\":").append(f.lastModified())
        }
        sb.append('}')
        return sb.toString() + "\n"
    }

    private fun json(s: String): String = "\"${s.replace("\"", "\\\"")}\""
}
