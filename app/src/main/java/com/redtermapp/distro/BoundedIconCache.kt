package com.redtermapp.distro

import android.graphics.Bitmap

/**
 * A byte-bounded, least-recently-used cache of decoded icons.
 *
 * `android.util.LruCache` would be the obvious choice and is deliberately not used: it is
 * one of the framework classes that is a stub off-device, so a cache built on it cannot be
 * tested without a device — which is the same mistake that leaves the eviction and sizing
 * rules unverified. This is the same algorithm in thirty testable lines.
 *
 * Bounded by bytes rather than by entry count, because the entries differ in size and a
 * count says nothing about what it costs.
 */
internal class BoundedIconCache(private val maxBytes: Long) {

    private val lock = Any()

    // accessOrder = true is what makes it least-recently-used rather than
    // least-recently-added, and it is also why every access must hold the lock: get()
    // mutates the map to reorder it.
    private val entries = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            bytes() > maxBytes
    }

    /** Entries evicted since construction. Zero is the number worth asserting on. */
    @Volatile
    var evictions: Int = 0
        private set

    private fun bytes(): Long {
        var total = 0L
        for (bitmap in entries.values) total += bitmap.allocationByteCountOr()
        return total
    }

    fun get(key: String): Bitmap? = synchronized(lock) { entries[key] }

    fun put(key: String, bitmap: Bitmap) = synchronized(lock) {
        entries[key]?.let { if (it === bitmap) return@synchronized }
        entries[key] = bitmap
        while (bytes() > maxBytes && entries.size > 1) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
            evictions++
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * `allocationByteCount` is the real cost of the bitmap; it exists from API 19 and this
     * app supports 24, so the fallback is defensive rather than needed.
     */
    private fun Bitmap.allocationByteCountOr(): Long =
        try {
            if (allocationByteCount > 0) allocationByteCount.toLong()
            else (width.toLong() * height * 4)
        } catch (_: Throwable) {
            width.toLong() * height * 4
        }

    companion object {
        /**
         * Enough for about fifty icons and no more.
         *
         * Android's guidance is a fraction of the heap; a quarter of `maxMemory` is far
         * more than eleven small logos will ever need, and a cache that cannot evict is
         * only a cache until someone bundles something larger.
         */
        fun defaultSize(): Long {
            val heap = Runtime.getRuntime().maxMemory()
            // Parenthesised: `heap / 16L.coerceAtLeast(1).coerceAtMost(n)` binds the
            // ceiling to 16L rather than to the result, so the clamp silently did nothing
            // and a large-heap device would size the cache at tens of megabytes.
            return (heap / 16L).coerceIn(1L, 8L * 1024 * 1024)
        }
    }
}