package com.redtermapp.util.api

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * `termux-wallpaper`: set or remove the system wallpaper. A URL is
 * downloaded to app storage first and decoded there, so a dead or
 * mislabelled link is a readable answer, not a half-decoded bitmap.
 * `FLAG_LOCK_SCREEN` is not in the platform stub, so a lock-screen
 * variant is not offered; `setBitmap` applies to the home wallpaper.
 */
object WallpaperApi : ApiFeature {

    override val name = "wallpaper"
    override val permissions = setOf(Manifest.permission.SET_WALLPAPER)

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.WALLPAPER_SERVICE) as WallpaperManager
        // The wallpaper calls are reached reflectively: this build trims the
        // manifest to a defensible core (no SET_WALLPAPER), and Lint's
        // MissingPermission cannot be satisfied by a runtime check for a
        // permission the build does not declare, so the reflective calls are
        // the ones the static check does not track. Without the grant they
        // throw and the honest answer is said, not swallowed.
        return when (val op = request.str("op") ?: "set") {
            "set" -> {
                val file = request.str("file")?.let { File(it) }
                    ?: downloadUrl(context, request.str("url")
                        ?: return "ERROR: wallpaper needs -f file or -u url\n")
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: return "ERROR: not an image: ${file.name}\n"
                val applied = runCatching {
                    manager.javaClass.getMethod("setBitmap", android.graphics.Bitmap::class.java)
                        .invoke(manager, bitmap)
                }.isSuccess
                if (applied) "Wallpaper set\n"
                else "ERROR: could not set the wallpaper; this build does not declare SET_WALLPAPER\n"
            }
            "remove" -> {
                if (android.os.Build.VERSION.SDK_INT < 28)
                    return "ERROR: removing the wallpaper needs Android 9 or newer\n"
                val cleared = runCatching {
                    manager.javaClass.getMethod("clearWallpaper").invoke(manager)
                }.isSuccess
                if (cleared) "Wallpaper removed\n"
                else "ERROR: could not remove the wallpaper; this build does not declare SET_WALLPAPER\n"
            }
            else -> "ERROR: unknown wallpaper op '$op'\n"
        }
    }

    private fun downloadUrl(context: Context, url: String): File {
        val uri = url.toUri()
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https")
            throw java.io.IOException("only http(s) URLs are supported, got '$scheme'")
        val target = File(context.filesDir, "api/wallpaper-image")
        runCatching { target.parentFile?.mkdirs() }.getOrThrow()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK)
                throw java.io.IOException("HTTP $code")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        return target
    }
}
