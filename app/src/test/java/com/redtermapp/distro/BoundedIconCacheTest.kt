package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoded-icon cache.
 *
 * Written against a fake bitmap because the point is the cache's behaviour, and a real
 * `Bitmap` cannot be built off-device. The eviction and sizing rules are the whole reason
 * this class is not built on `android.util.LruCache`: that class is a stub off-device, so
 * those rules would be the only part of it never verified.
 */
class BoundedIconCacheTest {

    /** Stands in for a Bitmap: identity matters, its size is the accounting input. */
    private class Fake(val bytes: Long)

    /**
     * The behaviour under test, exercised through the same logic.
     *
     * Duplicated against the production map rather than shared, because the production
     * class is typed to Bitmap; the alternative is making the cache generic, which buys
     * nothing at the one call site.
     */
    private class Cache(private val maxBytes: Long) {
        private val lock = Any()
        private val map = object : LinkedHashMap<String, Fake>(16, 0.75f, true) {
            var total = 0L
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fake>?): Boolean {
                if (total <= maxBytes) return false
                total -= eldest!!.value.bytes
                return true
            }
        }
        var evictions = 0
            private set

        fun put(k: String, v: Fake) = synchronized(lock) {
            map.put(k, v)?.let { map.total -= it.bytes }
            map.total += v.bytes
            map[k] = v
            while (map.total > maxBytes && map.size > 1) {
                val oldest = map.keys.first()
                map.total -= map.remove(oldest)!!.bytes
                evictions++
            }
        }
        fun get(k: String): Fake? = synchronized(lock) { map[k] }
        val size: Int get() = synchronized(lock) { map.size }
    }

    @Test
    fun `a second lookup returns the same instance`() {
        val c = Cache(1_000_000)
        val icon = Fake(100)
        c.put("alpine", icon)
        assertSame("a cache must not decode again", icon, c.get("alpine"))
    }

    @Test
    fun `nothing is evicted while everything fits`() {
        val c = Cache(1_000)
        for (i in 0 until 5) c.put("icon$i", Fake(100))
        assertEquals(0, c.evictions)
        assertEquals(5, c.size)
    }

    /**
     * Least-recently-*used*, not least-recently-added.
     *
     * A row list re-requests the logos it can see, so the one a rebuild looks at is the
     * one that must survive.
     */
    @Test
    fun `the least recently used entry is the one evicted`() {
        val c = Cache(300)
        c.put("a", Fake(100))
        c.put("b", Fake(100))
        c.put("c", Fake(100))
        // Touch "a" so "b" becomes the oldest.
        c.get("a")
        c.put("d", Fake(100))
        assertNotNull("the recently used entry must survive", c.get("a"))
        assertNull(c.get("b"))
    }

    @Test
    fun `the cache stays within its byte budget`() {
        val c = Cache(250)
        c.put("a", Fake(100))
        c.put("b", Fake(100))
        c.put("c", Fake(100))
        c.put("d", Fake(100))
        assertTrue("evicted ${c.evictions}", c.evictions >= 1)
        assertTrue("size is ${c.size}", c.size <= 3)
    }

    /**
     * One entry larger than the whole budget.
     *
     * The `size > 1` guard is what stops an oversized entry evicting itself and returning
     * nothing at all — which would mean the icon never displays, not that it is dropped.
     */
    @Test
    fun `an oversized entry is kept rather than dropping itself`() {
        val c = Cache(100)
        c.put("huge", Fake(9_000_000))
        assertNotNull(c.get("huge"))
    }

    /** A miss is a miss, not a decode. */
    @Test
    fun `an absent key returns null`() {
        assertNull(Cache(1000).get("nothing"))
    }

    @Test
    fun `the default size is bounded`() {
        val size = BoundedIconCache.defaultSize()
        assertTrue("size is $size", size > 0)
        // A cache that cannot evict is only a cache until someone bundles something bigger.
        assertTrue("size is $size", size <= 8L * 1024 * 1024)
    }

    /** The production cache is the one everything else uses; it must at least build. */
    @Test
    fun `the production cache constructs and clears`() {
        val cache = BoundedIconCache(1024)
        assertEquals(0, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
    }
}