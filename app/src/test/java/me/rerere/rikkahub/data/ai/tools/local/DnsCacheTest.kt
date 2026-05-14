package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DnsCache] — proves the TTL window, expiry-on-access eviction, and
 * network-change invalidation behave as the SSH tools rely on. The clock is injected so the
 * TTL is tested deterministically without sleeping.
 */
class DnsCacheTest {

    @Test
    fun `get returns null for unknown host`() {
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { 0L })
        assertNull(cache.get("example.com"))
    }

    @Test
    fun `put then get within ttl returns cached ip`() {
        var now = 1_000L
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { now })
        cache.put("example.com", "203.0.113.7")
        now = 1_000L + 59_999L // just inside the 60s window
        assertEquals("203.0.113.7", cache.get("example.com"))
    }

    @Test
    fun `entry expires once ttl elapses`() {
        var now = 1_000L
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { now })
        cache.put("example.com", "203.0.113.7")
        now = 1_000L + 60_000L // exactly at expiry — expiresAtMs is exclusive
        assertNull(cache.get("example.com"))
    }

    @Test
    fun `expired entry is evicted on access`() {
        var now = 0L
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { now })
        cache.put("example.com", "203.0.113.7")
        assertEquals(1, cache.size())
        now = 120_000L
        assertNull(cache.get("example.com"))
        assertEquals(0, cache.size()) // stale key removed, not left to accumulate
    }

    @Test
    fun `put refreshes ttl for an existing host`() {
        var now = 0L
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { now })
        cache.put("example.com", "203.0.113.7")
        now = 50_000L
        cache.put("example.com", "203.0.113.8") // re-resolved, new ip + fresh ttl
        now = 100_000L // 50s after the second put — still inside ttl
        assertEquals("203.0.113.8", cache.get("example.com"))
    }

    @Test
    fun `invalidateAll drops every entry`() {
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { 0L })
        cache.put("a.example.com", "203.0.113.1")
        cache.put("b.example.com", "203.0.113.2")
        assertEquals(2, cache.size())
        cache.invalidateAll()
        assertEquals(0, cache.size())
        assertNull(cache.get("a.example.com"))
        assertNull(cache.get("b.example.com"))
    }

    @Test
    fun `independent hosts are cached independently`() {
        var now = 0L
        val cache = DnsCache(ttlMs = 60_000L, nowMs = { now })
        cache.put("a.example.com", "203.0.113.1")
        now = 30_000L
        cache.put("b.example.com", "203.0.113.2")
        now = 70_000L // a expired (70s), b still live (40s)
        assertNull(cache.get("a.example.com"))
        assertEquals("203.0.113.2", cache.get("b.example.com"))
    }
}
