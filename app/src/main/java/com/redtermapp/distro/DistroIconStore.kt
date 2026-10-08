package com.redtermapp.distro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches a distro's logo once and keeps it on disk.
 *
 * Three sources, in order, and the app is fully usable with none of them:
 *
 *  1. `assets/distro-icons/<name>.png` — bundled, if the project ships one.
 *  2. `filesDir/distro-icons/<name>.png` — downloaded earlier, on a previous visit.
 *  3. the lettered badge, which needs no network and never fails.
 *
 * **Nothing here blocks a card.** A fetch runs off the main thread and the badge is shown
 * immediately; if the device is offline, the host is down, or the image is not an image,
 * the card simply keeps its letters. That is the whole reason the monogram exists.
 *
 * **Costs worth knowing before pointing this at a host.** First launch needs the network,
 * and the icons come from somewhere the app does not control — so a compromised or
 * re-pointed URL serves whatever it likes. Serve them yourself. And fetching a logo does
 * not make it any less a trademark: whether the project may distribute one is a decision
 * for the project, unchanged by where it was loaded from.
 */
object DistroIconStore {

    private const val TAG = "DistroIcon"
    private const val DIR = "distro-icons"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val TIMEOUT_MS = 8000

    /**
     * Decoded down to this edge, which is all a 44dp badge can show.
     *
     * A full-resolution logo decoded at size is tens of megabytes per distro, and a list
     * of cards would take the process out with it.
     */
    private const val RENDER_PX = 144

    /**
     * Where the logos are served from.
     *
     * Deliberately a single constant rather than eleven URLs: pointing this at the
     * project's own hosting is one edit, and there is no list of third-party paths in the
     * source to go stale. Empty means "bundle them in assets instead", which is a
     * perfectly good option and the one with no network dependency at all.
     */
    const val BASE_URL = ""

    /**
     * The stamp written beside a cached file.
     *
     * An app update is the natural moment to refresh a logo, since it is the only time the
     * project can have changed what a URL points at.
     */
    private fun stamp(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionCode}"
    } catch (_: Exception) {
        "0"
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun file(context: Context, name: String) = File(dir(context), "$name.png")
    private fun stampFile(context: Context, name: String) = File(dir(context), "$name.stamp")

    /**
     * The icon to show, from wherever one already exists: memory first, then a bundled
     * asset, then a download. Null means "use the letters", which is always available.
     *
     * One call rather than separate bundle and download lookups, because callers only ever
     * want the first thing that is there — and asking twice meant the download path ran on
     * every row even when the logo had been bundled all along.
     *
     * Safe on the main thread once warm: a cache hit is a map lookup.
     */
    fun iconOrNull(context: Context, name: String): Bitmap? {
        cache.get(name)?.let { return it }

        bundledOrNull(context, name)?.let {
            cache.put(name, it)
            return it
        }
        downloadedOrNull(context, name)?.let {
            cache.put(name, it)
            return it
        }
        return null
    }

    /**
     * A bundled asset, if there is one.
     *
     * Read on the main thread, which is why these are small: the packaged logos are 192px,
     * a few kilobytes each, and a decode is well under a millisecond. Anything larger
     * belongs behind a downloaded icon, which is not read here.
     */
    fun bundledOrNull(context: Context, name: String): Bitmap? = try {
        context.assets.open(DistroBrand.assetPath(name)).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * A previously downloaded icon, or null.
     *
     * A stale stamp reads as absent, so an app update fetches the new logo rather than
     * showing the old one for ever.
     */
    fun downloadedOrNull(context: Context, name: String): Bitmap? {
        val f = file(context, name)
        if (!f.isFile || f.length() == 0L) return null
        val s = stampFile(context, name)
        if (!s.isFile || s.readText() != stampOf(context)) return null
        return decode(f)
    }

    /** Drops every decoded icon. For a low-memory callback and nothing else. */
    fun trim() = cache.clear()

    /**
     * Downloads [name] if it has not been fetched for this app version.
     *
     * [onReady] is called on the main thread with the icon, or not at all — a caller shows
     * its badge either way. Requests for the same distro collapse into one, because a
     * grid of cards would otherwise ask for the same file once per column rebuild.
     */
    fun fetch(context: Context, name: String, onReady: (Bitmap) -> Unit) {
        if (BASE_URL.isBlank()) return
        val url = BASE_URL.trimEnd('/') + "/$name.png"
        val key = "$name@$url"
        if (inFlight.putIfAbsent(key, true) != null) return

        val app = context.applicationContext
        Thread({
            try {
                if (downloadedOrNull(app, name) == null) {
                    val bytes = download(url) ?: return@Thread
                    val target = file(app, name)
                    // Written beside the real name and moved into it, so a process death
                    // mid-write cannot leave a half-file that reads as a valid icon.
                    val tmp = File(dir(app), "$name.png.part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) tmp.delete()
                    stampFile(app, name).writeText(stampOf(app))
                }
                downloadedOrNull(app, name)?.let { bitmap ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onReady(bitmap)
                    }
                }
            } catch (e: Exception) {
                // A missing icon is not worth an error dialog: the badge is the fallback
                // and it is already on screen.
                Log.d(TAG, "icon unavailable for $name: ${e.message}")
            } finally {
                inFlight.remove(key)
            }
        }, "distro-icon").apply { isDaemon = true }.start()
    }

    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * Decoded icons, kept so a row rebuild does not decode again.
     *
     * Every card, list row and picker entry asks for the same handful of logos, and rows are
     * rebuilt on every resume. Decoding from the asset each time is the main-thread cost
     * this removes.
     */
    private val cache = BoundedIconCache(BoundedIconCache.defaultSize())

    /**
     * The app version, read once.
     *
     * `getPackageInfo` is a call into the package manager, and it was being made once per
     * row per rebuild. It cannot change while the process is alive.
     */
    private var stampCache: String? = null

    private fun stampOf(context: Context): String =
        stampCache ?: stamp(context).also { stampCache = it }

    /** @return the body, or null if the response was not a usable image. */
    private fun download(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            // Redirects matter here: a logo host that moves a file should not leave the
            // card permanently lettered.
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val type = connection.contentType.orEmpty()
            // Checked before decoding: a host that answers an image request with HTML
            // should not be able to fill the cache with something that is not an icon.
            if (!type.startsWith("image/")) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return null
            return bytes.takeIf { looksLikeImage(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** PNG, JPEG or WebP by magic number; SVG is not, and cannot be decoded here. */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        fun starts(vararg magic: Int) =
            magic.size <= bytes.size && magic.withIndex().all { (i, b) ->
                bytes[i].toInt() and 0xFF == b
            }
        return starts(0x89, 0x50, 0x4E, 0x47) ||       // PNG
            starts(0xFF, 0xD8, 0xFF) ||                   // JPEG
            starts(0x52, 0x49, 0x46, 0x46) ||             // RIFF (WebP)
            starts(0x47, 0x49, 0x46, 0x38)               // GIF
    }

    /** Decoded subsampled, so a 4 MB logo never arrives as a 4000x4000 bitmap. */
    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > RENDER_PX * 2 || bounds.outHeight / sample > RENDER_PX * 2) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }
}
