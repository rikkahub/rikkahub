package me.rerere.rikkahub.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A simple thread-safe cache implementation with expiration support.
 * This is a lightweight alternative to Guava Cache to avoid concurrency issues.
 */
class SimpleCache<K, V>(
    private val expireAfterWriteMillis: Long
) {
    private data class CacheEntry<V>(
        val value: V,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(expireAfterWriteMillis: Long): Boolean {
            return System.currentTimeMillis() - timestamp > expireAfterWriteMillis
        }
    }

    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()

    fun getIfPresent(key: K): V? {
        val entry = cache[key] ?: return null
        return if (entry.isExpired(expireAfterWriteMillis)) {
            cache.remove(key)
            null
        } else {
            entry.value
        }
    }

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(value)
    }

    fun invalidate(key: K) {
        cache.remove(key)
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun cleanUp() {
        cache.entries.removeIf { it.value.isExpired(expireAfterWriteMillis) }
    }

    fun size(): Int = cache.size

    companion object {
        fun <K, V> builder() = Builder<K, V>()
    }

    class Builder<K, V> {
        private var expireAfterWriteMillis: Long = Long.MAX_VALUE

        fun expireAfterWrite(duration: Long, unit: TimeUnit): Builder<K, V> {
            expireAfterWriteMillis = unit.toMillis(duration)
            return this
        }

        fun build(): SimpleCache<K, V> {
            return SimpleCache(expireAfterWriteMillis)
        }
    }
}
