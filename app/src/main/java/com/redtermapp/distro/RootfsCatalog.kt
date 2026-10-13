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
    /**
     * How long a cached index is trusted.
     *
     * Images are rebuilt under the same tag -- v1.0.0 today and v1.0.0 again after
     * every recipe change -- so the version field cannot tell a current index from one
     * taken before the last rebuild. An index cached across launches is therefore
     * describing files that no longer exist, and applying it is worse than not
     * applying it: it overrides correct built-in URLs with correct-looking URLs whose
     * sha256 belongs to a previous build of the same name.
     *
     * That is what "Install failed: SHA-256 mismatch. Corrupt download discarded"
     * was, on downloads that downloaded perfectly. The checksum was right for the
     * index and wrong for the file.
     *
     * So a cache is only usable within the session that fetched it. Anything older
     * is deleted, and the built-in catalogue stands until a fresh fetch arrives.
     */
    private const val CACHE_TRUST_MS = 30 * 60 * 1000L

    fun applyCached(context: Context): Boolean {
        val file = RootfsIndex.cacheFile(context.cacheDir)
        if (!file.exists()) return false
        return try {
            val age = System.currentTimeMillis() - file.lastModified()
            if (age > CACHE_TRUST_MS) {
                // Older than one session. Deleted rather than trusted.
                file.delete()
                return false
            }
            val index = RootfsIndex.parse(file.readText())
            if (!RootfsIndex.isUsable(index)) {
                // A cache from a different release. It names images this build's
                // built-in URLs do not, and applying it replaces correct checksums
                // with ones for files that no longer match -- which surfaces as
                // "SHA-256 mismatch. Corrupt download discarded" on a download that
                // downloaded perfectly.
                file.delete()
                return false
            }
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
if (!RootfsIndex.isUsable(index)) {
                      // Nothing is cached or applied, so the next launch starts from
                      // the built-in catalogue rather than retrying the same index.
                      runCatching { RootfsIndex.cacheFile(context.cacheDir).delete() }
                      return@Thread
                  }
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

    /**
     * Fetches and applies the index on the calling thread.
     *
     * install() needs the answer before it can continue rather than after, and it needs
     * it on a rebuild: the release the APK was compiled against can be months old, so a
     * download that fails its checksum may simply be a newer image at the same URL.
     * Returns whether the catalogue now describes something the caller can retry with.
     */
    fun refreshNow(context: Context, url: String = RootfsIndex.DEFAULT_INDEX_URL): Boolean =
        try {
            val index = RootfsIndex.parse(fetch(url))
            if (!RootfsIndex.isUsable(index)) {
                false
            } else {
                DistroRegistry.applyIndex(index)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "forced index refresh failed: ${e.message}")
            false
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