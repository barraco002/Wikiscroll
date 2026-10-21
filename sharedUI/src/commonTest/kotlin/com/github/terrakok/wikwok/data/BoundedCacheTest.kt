package com.github.terrakok.wikwok.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The repository caches every article it serves and the feed never ends, so the ceiling is the only
 * thing between a long scroll and an out-of-memory crash. These tests pin the two properties the
 * eviction depends on: that it happens at all, and that it takes the entry the reader stopped
 * looking at rather than the one they are on.
 */
class BoundedCacheTest {

    @Test
    fun theCacheStopsGrowingAtItsCeiling() {
        val cache = BoundedCache<String, String>(maxEntries = 3)
        repeat(10) { index -> cache.put("k$index", "v$index") }

        assertEquals(3, cache.size)
        assertNull(cache["k0"], "the oldest entry should have been evicted")
        assertEquals("v9", cache["k9"])
    }

    @Test
    fun readingAnEntryKeepsItAlive() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        // Touching "a" makes "b" the least recently used entry, so the next insert evicts "b".
        assertEquals("1", cache["a"])
        cache.put("c", "3")

        assertEquals("1", cache["a"])
        assertEquals("3", cache["c"])
        assertNull(cache["b"])
    }

    @Test
    fun writingTheSameKeyTwiceDoesNotEvictAnything() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache.put("a", "1")
        cache.put("a", "2")

        assertEquals(1, cache.size)
        assertEquals("2", cache["a"])
    }

    @Test
    fun clearDropsEverything() {
        val cache = BoundedCache<String, String>(maxEntries = 4)
        cache.put("a", "1")
        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache["a"])
    }
}