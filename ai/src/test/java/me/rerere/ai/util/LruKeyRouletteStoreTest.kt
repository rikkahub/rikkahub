package me.rerere.ai.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

class LruKeyRouletteStoreTest {

    private lateinit var dir: File
    private val errors = mutableListOf<Pair<String, Throwable>>()

    private val cacheFile: File get() = File(dir, "lru_key_roulette.json")
    private val tmpFile: File get() = File(dir, "lru_key_roulette.json.tmp")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lru-roulette").toFile()
        errors.clear()
    }

    private fun store() = LruKeyRouletteStore(dir) { msg, t -> errors.add(msg to t) }

    private fun seed(cache: Map<String, Map<String, Long>>) {
        cacheFile.writeText(Json.encodeToString(cache))
    }

    private fun readCache(): Map<String, Map<String, Long>> =
        Json.decodeFromString(cacheFile.readText())

    // (a) Load failure on corrupt file must be SURFACED (not silently swallowed) and REDACTED.
    @Test
    fun `corrupt cache file surfaces a redacted load error and does not crash`() {
        seed(mapOf("p" to mapOf("A" to System.currentTimeMillis())))
        // simulate crash mid-write: truncated/corrupt JSON in the live file
        cacheFile.writeText("{\"p\":{\"A\":12")

        val selected = store().next("A,B", "p")

        // selection still works (graceful degradation)
        assertTrue(selected == "A" || selected == "B")
        // FIX #2: failure must be visible — unfixed code swallows it, so this list stays empty
        assertEquals(1, errors.count { it.first.contains("load") })
        // redaction: message must not leak any key contents
        val msg = errors.first { it.first.contains("load") }.first
        assertFalse(msg.contains("A"))
        assertFalse(msg.contains("B"))

        // subsequent write produces valid, parseable JSON
        store().next("A,B", "p")
        assertNotNull(readCache())
    }

    // (a2) The throwable reported for a corrupt-cache load must NOT be able to reproduce the
    //      cache body. kotlinx-serialization's JsonDecodingException embeds a "JSON input: ..."
    //      window of the decoded input, and the decoded input here is {provider:{apiKey:ts}}.
    //      The default onError does Log.w(TAG, msg, throwable), so a leaky throwable spills the
    //      api key to logcat. Assert the throwable (message + toString + cause chain + full
    //      stack trace) contains no part of the seeded key material.
    @Test
    fun `corrupt cache load does not leak key material through the reported throwable`() {
        val secretKey = "sk-LEAKCANARY-0123456789"
        // a syntactically broken cache whose raw bytes contain the secret key
        cacheFile.writeText("{\"p\":{\"$secretKey\":123")

        store().next("$secretKey,OTHER", "p")

        val loadError = errors.first { it.first.contains("load") }.second
        val rendered = renderThrowable(loadError)
        assertFalse(
            "throwable reported on cache-parse failure must not echo the api key",
            rendered.contains("LEAKCANARY"),
        )
        // also assert the raw JSON snippet marker the serializer would emit is gone
        assertFalse(rendered.contains("JSON input"))
    }

    private fun renderThrowable(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return buildString {
            append(t.message ?: "")
            append('\n')
            append(t.toString())
            append('\n')
            append(sw.toString())
        }
    }

    // (b) Atomic write: target parses, no leftover .tmp file.
    @Test
    fun `successful write leaves valid target and no leftover temp file`() {
        store().next("K1,K2", "p")

        val cache = readCache()
        assertTrue(cache.isNotEmpty())
        assertTrue(cache.getValue("p").isNotEmpty())
        assertFalse("temp file must be renamed/cleaned, not left behind", tmpFile.exists())
    }

    // (b2) Crash-safety / atomicity (FIX #1, the headline of issue #120): if the save path
    //      cannot place the new cache, the existing live cache must survive UNTOUCHED.
    //      Make the cache dir read-only so the temp-write step fails. Atomic code never opens
    //      the live file, so it keeps the original content; the pre-fix in-place writeText
    //      overwrote the live file directly (the existing file stays writable even in a
    //      read-only dir), destroying the original content -> this test fails-before.
    @Test
    fun `save failure preserves the existing live cache file`() {
        val original = mapOf("p" to mapOf("KEEP" to 111L))
        seed(original)

        // Force placement to fail: a read-only dir blocks temp-file creation + rename.
        assumeTrue("cannot make dir read-only (running as root?)", dir.setWritable(false, false))
        try {
            store().next("NEW1,NEW2", "p")
        } finally {
            dir.setWritable(true, false)
        }

        // The save was reported as failed...
        assertTrue("save failure must be surfaced", errors.any { it.first.contains("save") || it.first.contains("rename") })
        // ...and the live cache still holds the ORIGINAL content (no truncation/overwrite).
        assertEquals(original, readCache())
    }

    // (c) Uniform expiry (FIX #3): stale entries are pruned per-key across providers,
    //     not only when an entire provider's entries are all stale.
    @Test
    fun `expiry prunes individual stale keys across all providers`() {
        val now = System.currentTimeMillis()
        val stale = now - EXPIRE_DURATION_MS_TEST - 1000L
        seed(
            mapOf(
                // unrelated provider p2: ONE stale + ONE fresh key.
                // Old removeIf only drops a provider when ALL its entries are stale, so it
                // would keep the stale "X" forever. Fixed code prunes "X" individually.
                "p2" to mapOf("X" to stale, "FRESH" to now - 1000L),
                // unrelated provider p3: only stale entries -> fully removed (works in old code too)
                "p3" to mapOf("Z" to stale),
            )
        )

        store().next("K1,K2", "p1")

        val cache = readCache()
        // p3 fully removed (all entries expired)
        assertFalse("expired-only provider must be removed", cache.containsKey("p3"))
        // p2's stale key pruned; fresh key kept. Unfixed code retains the stale "X" -> FAILS pre-fix.
        assertEquals(setOf("FRESH"), cache.getValue("p2").keys)
    }

    // (d) Selection algorithm preserved by the refactor (guard).
    @Test
    fun `prefers never-used key then least-recently-used`() {
        val s = store()
        val first = s.next("K1,K2", "p")
        // after using `first`, the other never-used key is preferred
        val second = s.next("K1,K2", "p")
        assertEquals(setOf("K1", "K2"), setOf(first, second))
        assertTrue(first != second)

        // both used now -> returns least-recently-used (the first one used)
        val third = s.next("K1,K2", "p")
        assertEquals(first, third)
    }

    private companion object {
        const val EXPIRE_DURATION_MS_TEST = 24 * 60 * 60 * 1000L
    }
}
