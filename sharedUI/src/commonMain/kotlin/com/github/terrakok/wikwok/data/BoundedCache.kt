package com.github.terrakok.wikwok.data

/**
 * Insertion-ordered map with a hard ceiling on the number of entries.
 *
 * The feed is infinite, so every cache in the data layer is eventually asked to hold an infinite
 * number of articles. An unbounded map answers that by growing until the process dies; this one
 * evicts the least recently used entry the moment the ceiling is crossed.
 *
 * Access order, not insertion order: re-reading a cached article marks it as still wanted, so the
 * eviction takes the card the reader left behind rather than the one behind them. The order is
 * maintained by hand, re-inserting on read, because the `accessOrder` constructor argument of
 * `LinkedHashMap` exists on the JVM only and this module also compiles for wasmJs and iOS.
 *
 * Not synchronised. Every call site holds the repository cache mutex, and a cache with its own lock
 * would nest two locks to protect one invariant.
 *
 * @param maxEntries hard ceiling. Must be positive.
 */
internal class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val entries: LinkedHashMap<K, V> = LinkedHashMap()

    /** Current number of entries. Exposed for tests and diagnostics. */
    val size: Int get() = entries.size

    /** Returns the value for [key] and marks it as recently used, or null when absent. */
    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        // Re-inserted at the tail: the head of the map is now always the next entry to evict.
        entries[key] = value
        return value
    }

    /** Stores [value] under [key], evicting the least recently used entry when the cache is full. */
    fun put(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        trim()
    }

    /** Drops every entry. */
    fun clear() {
        entries.clear()
    }

    private fun trim() {
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: return
            entries.remove(eldest)
        }
    }
}
