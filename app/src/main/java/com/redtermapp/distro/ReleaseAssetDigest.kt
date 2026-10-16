package com.redtermapp.distro

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The checksum of a published asset, read from the release that holds it.
 *
 * ## Why the app asks the release instead of trusting the index
 *
 * The index was a second description of the release, produced elsewhere, published
 * separately. Any two things that describe the same bytes separately will eventually
 * disagree, and when they do every install of the affected image fails -- which is
 * what happened, repeatedly, to most of this catalogue.
 *
 * There is no window here to be wrong in. The release is asked *after* the download,
 * for the asset that was actually fetched, and the answer is the sha256 of the bytes
 * it is holding right now. If those match the file on disk, the file is correct, full
 * stop, and no index was consulted.
 *
 * ## Why it is a cache and not just a fetch
 *
 * This is only consulted when a hash the app already had did not match, so the normal
 * install path never touches the network here. The cache is keyed by tag and asset
 * name and is deliberately short-lived: an asset replaced under the same name within
 * the same minute is not a thing worth optimising for, and a stale answer here would
 * turn a correct download into a failure, which is the bug being fixed.
 */
object ReleaseAssetDigest {

    private const val API = "https://api.github.com/repos/%s/releases/tags/%s"
    private const val CACHE_MS = 60_000L

    private data class Key(val repo: String, val tag: String, val asset: String)

    private val cache = ConcurrentHashMap<Key, Pair<Long, String>>()

    /**
     * The sha256 the release currently reports for [assetName], or "" if it cannot be
     * determined. Never throws: an answer that cannot be got is simply no answer, and
     * the caller decides what to do without one.
     */
    fun of(repo: String, tag: String, assetName: String): String {
        val key = Key(repo, tag, assetName)
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, digest) -> if (now - at < CACHE_MS) return digest }

        val fetched = fetch(repo, tag, assetName)
        // A negative result is not cached: the rate limit is per hour and a failed
        // lookup should not be remembered for a minute as though it were an answer.
        if (fetched.isNotEmpty()) cache[key] = now to fetched
        return fetched
    }

    /** Visible for tests: a fresh process must not inherit a previous answer. */
    fun forget() = cache.clear()

    private fun fetch(repo: String, tag: String, assetName: String): String = try {
        val conn = URL(API.format(repo, tag)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                ""
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val assets = JSONObject(body).optJSONArray("assets") ?: return ""
                var digest = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == assetName) {
                        digest = a.optString("digest").removePrefix("sha256:")
                        break
                    }
                }
                digest.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }.orEmpty()
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        ""
    }
}