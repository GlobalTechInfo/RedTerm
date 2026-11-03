package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.net.HttpURLConnection
import java.net.URL

/**
 * `termux-callback`: deliver a payload to a URL the guest names. The
 * reference's callback is the app pushing a result back to a listener
 * the guest ran; over this bridge the guest supplies that listener
 * (a local HTTP server in the distro) as the URL, and the request
 * thread makes the delivery, so a round trip is a real answer.
 */
object CallbackApi : ApiFeature {

    override val name = "callback"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val url = request.str("url") ?: return "ERROR: callback needs a url\n"
        val method = (request.str("method") ?: "POST").uppercase()
        val payload = request.str("data") ?: ""
        val timeout = (request.int("timeout") ?: 10) * 1000
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.requestMethod = method
            conn.setInstanceFollowRedirects(true)
            if (method != "GET") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText().take(512)
            conn.disconnect()
            "HTTP $code $body\n"
        } catch (e: Exception) {
            "ERROR: callback failed: ${e.message}\n"
        }
    }
}
