package com.github.terrakok.wikwok.data

/**
 * Insertion-ordered map with a hard ceiling on the number of entries.
 *
 * The feed is infinite, so every cache in the data layer is eventually asked to hold an infinite
 * number of articles. An unbounded map answers that by growing until the process dies; this one
 * evicts the least recently used entry the moment the ceiling is crossed.
 *
 * Access order, not insertion order: re-reading a cached article marks it as still wanted, so the
 * eviction takes the card the reader left behind rather than the one behind them.
 *
 * Not synchronised. Every call site holds the repository cache mutex, and a cache with its own lock
 * would nest two locks to protect one invariant.
 *
 * @param maxEntries hard ceiling. Must be positive.
 */
internal class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {

    private val entries: LinkedHashMap<K, V> = LinkedHashMap(maxEntries, LOAD_FACTOR, ACCESS_ORDER)

    /** Current number of entries. Exposed for tests and diagnostics. */
    val size: Int get() = entries.size

    /** Returns the value for [key] and marks it as recently used, or null when absent. */
    operator fun get(key: K): V? = entries[key]

    /** Stores [value] under [key], evicting the least recently used entry when the cache is full. */
    fun put(key: K, value: V) {
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

    private companion object {
        /** Load factor of the backing map. It does not affect eviction, which is size driven. */
        const val LOAD_FACTOR = 0.75f

        /** True so that a read refreshes the entry position in the eviction order. */
        const val ACCESS_ORDER = true
    }
}