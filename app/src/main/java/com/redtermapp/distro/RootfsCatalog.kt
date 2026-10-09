package com.redtermapp.distro

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches, caches and applies the published rootfs index.
 *
 * Two rules shape this, both from the same place — an install must work when the network
 * does not:
 *
 *  * The cached copy is applied at startup, synchronously and without a network call. The
 *    catalogue a user sees is therefore never empty and never blocks on I/O.
 *  * A refresh happens on a background thread and only replaces the catalogue if it parses
 *    and if it actually changes something.
 *
 * Everything here is best-effort. A failure is logged and discarded; there is no user-facing
 * error, because there is nothing the user can do about a catalogue that could not be
 * refreshed and the built-in list is a working answer.
 */
object RootfsCatalog {

    private const val TAG = "RootfsCatalog"

    /** An index larger than this is not an index. Guards against a captive portal or an HTML error page. */
    private const val MAX_INDEX_BYTES = 2L * 1024 * 1024

    private const val TIMEOUT_MS = 15_000

    /**
     * Applies a previously cached index, if there is a usable one.
     *
     * Called before the UI reads [DistroRegistry.allDistros]. Returns true when an index
     * was applied. A cached file that does not parse is deleted, because leaving it there
     * means every subsequent launch retries the same broken parse.
     */
    fun applyCached(context: Context): Boolean {
        val file = RootfsIndex.cacheFile(context.cacheDir)
        if (!file.exists()) return false
        return try {
            val index = RootfsIndex.parse(file.readText())
            DistroRegistry.applyIndex(index)
            true
        } catch (e: Exception) {
            Log.w(TAG, "cached index unusable, discarding it: ${e.message}")
            runCatching { file.delete() }
            DistroRegistry.clearIndex()
            false
        }
    }

    /**
     * Fetches the index on a background thread and applies it if it is usable.
     *
     * Nothing is written to the cache when the fetch fails, so a network outage cannot
     * replace a good cached index with nothing.
     */
    fun refresh(context: Context, url: String = RootfsIndex.DEFAULT_INDEX_URL, onDone: () -> Unit = {}) {
        Thread {
            var applied = false
            try {
                val body = fetch(url)
                if (body.length > MAX_INDEX_BYTES) {
                    throw IllegalStateException("index is ${body.length} bytes, implausibly large")
                }
                val index = RootfsIndex.parse(body)
                DistroRegistry.applyIndex(index)
                RootfsIndex.cacheFile(context.cacheDir).writeText(body)
                applied = true
                Log.i(TAG, "applied index ${index.version} with ${index.images.size} image(s)")
            } catch (e: Exception) {
                // Silent to the user on purpose: the built-in catalogue still works, and a
                // refresh failure is not something they can act on.
                Log.w(TAG, "index refresh failed, keeping the built-in catalogue: ${e.message}")
            }
            onDone()
        }.apply { name = "rootfs-index-refresh" }.start()
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            // The index is the one request whose response is worth caching in HTTP terms,
            // since it changes only when a release is published.
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code from $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Whether a cached index exists, for the diagnostics screen. */
    fun cachedIndex(context: Context): File? =
        RootfsIndex.cacheFile(context.cacheDir).takeIf { it.exists() }
}