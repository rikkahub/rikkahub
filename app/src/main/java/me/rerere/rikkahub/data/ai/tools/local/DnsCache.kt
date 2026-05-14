package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny in-memory hostname → resolved-IP cache with a fixed TTL, used by the SSH tools so a
 * workflow that SSHes to the same host every few minutes doesn't pay a fresh DNS round-trip
 * on every connect.
 *
 * Deliberately pure / Android-free so the TTL behaviour is unit-testable: the clock is
 * injectable via [nowMs]. The default [Dns] singleton wires it to [System.currentTimeMillis]
 * and registers itself with `NetworkChangeMonitor` so a Wi-Fi ↔ cell handoff drops every
 * entry (a cached IP from the old network's split-horizon DNS would be wrong on the new one).
 *
 * Only positive results are cached — a failed lookup returns null and is never stored, so a
 * transient DNS failure can't poison the cache for the full TTL window.
 */
class DnsCache(
    private val ttlMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val ip: String, val expiresAtMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the cached IP for [host] if present and not past its TTL, else null. An expired
     * entry is evicted on access so the map doesn't accumulate dead keys for hosts that are
     * never looked up again.
     */
    fun get(host: String): String? {
        val entry = map[host] ?: return null
        if (nowMs() >= entry.expiresAtMs) {
            map.remove(host, entry)
            return null
        }
        return entry.ip
    }

    /** Cache [ip] for [host] for the configured TTL. */
    fun put(host: String, ip: String) {
        map[host] = Entry(ip, nowMs() + ttlMs)
    }

    /** Drop every entry. Called on a network change so stale per-network IPs can't linger. */
    fun invalidateAll() {
        map.clear()
    }

    /** Test/debug helper: number of entries currently held (including not-yet-evicted stale ones). */
    fun size(): Int = map.size
}
