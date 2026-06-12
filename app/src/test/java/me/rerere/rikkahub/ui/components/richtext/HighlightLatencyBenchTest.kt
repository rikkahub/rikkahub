package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import me.rerere.highlight.HighlightToken
import me.rerere.highlight.HighlightTokenSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Measure-only benchmark (SPEC M5, candidate #1): JVM-reproducible components of the
 * latency `HighlightCodeVisualTransformation.filter` imposes on the UI thread via
 * `runBlocking { highlighter.highlight(...) }` (HighlightCodeBlock.kt:522), at 1KB /
 * 10KB / 100KB inputs.
 *
 * `Highlighter.highlight` = QuickJS prism tokenization (native) + per-token
 * `stringify()`/JSON decode (JVM) reached through a runBlocking-over-single-thread-
 * executor handoff. The prism/QuickJS part is DEVICE-BOUND: `wang.harlon.quickjs`
 * ships natives only in `wrapper-android` (the `wrapper-java` jar in the dependency
 * graph carries no desktop .so), so the full end-to-end `highlight()` latency cannot
 * run in a plain JVM unit test without a new dependency (ask-first per SPEC). What IS
 * measured here, against real production code where it exists on the JVM:
 *
 *  1. The dispatch floor — per-call cost of the exact runBlocking +
 *     suspendCancellableCoroutine + executor.submit pattern `filter()` pays per
 *     keystroke even for a zero-cost highlight.
 *  2. The token decode stage — `HighlightTokenSerializer` (production serializer)
 *     decoding prism-shaped token JSON at token counts proportional to input size.
 *
 * Numbers are recorded as BEFORE-evidence only; no thresholds asserted (CI-stable).
 *
 * VERDICT (audit close-out): material at large inputs (token decode alone reaches
 * ~7 ms per filter() call at 100KB). Landed fix: HighlightCodeVisualTransformation
 * gained value semantics so CoreTextField's remember(value, visualTransformation)
 * cache stops re-running filter() on recompositions with unchanged text — see
 * HighlightCodeVisualTransformationCacheKeyTest for the guard and numbers. The full
 * async pre-highlight restructure is UX-visible (briefly unstyled text while typing)
 * and remains ask-first per the spec's Open Question 4 — deliberately not landed.
 */
class HighlightLatencyBenchTest {
    @Test
    fun `runBlocking-over-executor dispatch floor per filter call`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for ((label, sizeBytes) in INPUT_SIZES) {
                val payload = syntheticCode(sizeBytes)
                val nsPerCall = benchDispatchFloor(executor, payload, warmupCalls = 2000, measuredCalls = 500)
                println("[BENCH highlight dispatchFloor] input=$label nsPerCall=${"%.0f".format(nsPerCall)}")
                assertTrue("harness must have timed real work", nsPerCall > 0.0)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `token decode latency scales with input size`() {
        for ((label, sizeBytes) in INPUT_SIZES) {
            val tokenCount = sizeBytes / AVG_TOKEN_CHARS
            val tokenJsons = syntheticPrismTokenJsons(tokenCount)
            // Warmup is sized so even the smallest input JIT-compiles the decode path
            // (~4000 decodes) before measurement; rounds beyond that add nothing.
            val result = benchTokenDecode(
                tokenJsons = tokenJsons,
                warmupRounds = (WARMUP_DECODES / tokenCount).coerceAtLeast(1),
                measuredRounds = 5,
            )

            println(
                "[BENCH highlight tokenDecode] input=$label tokens=$tokenCount " +
                    "totalNsPerInput=${"%.0f".format(result.totalNsPerInput)} " +
                    "nsPerToken=${"%.0f".format(result.nsPerToken)}"
            )
            assertEquals(tokenCount, result.decodedTokens)
            assertTrue("harness must have timed real work", result.totalNsPerInput > 0.0)
        }
    }

    @Test
    fun `synthetic token json decodes through the production serializer`() {
        val tokens = syntheticPrismTokenJsons(10).map { json ->
            prismJson.decodeFromString(HighlightTokenSerializer, json)
        }
        assertEquals(10, tokens.size)
        assertTrue(tokens.any { it is HighlightToken.Token.StringContent })
        assertTrue(tokens.any { it is HighlightToken.Token.Nested })
    }

    private fun benchDispatchFloor(
        executor: ExecutorService,
        payload: String,
        warmupCalls: Int,
        measuredCalls: Int,
    ): Double {
        var sink = 0
        repeat(warmupCalls) { sink += dispatchViaExecutor(executor) { payload.length } }
        val start = System.nanoTime()
        repeat(measuredCalls) { sink += dispatchViaExecutor(executor) { payload.length } }
        val totalNs = System.nanoTime() - start
        check(sink > 0) { "payload round-trips must not be optimized away" }
        return totalNs.toDouble() / measuredCalls
    }

    private fun benchTokenDecode(
        tokenJsons: List<String>,
        warmupRounds: Int,
        measuredRounds: Int,
    ): DecodeResult {
        var sink = 0
        repeat(warmupRounds) {
            for (json in tokenJsons) {
                sink += decodeSink(prismJson.decodeFromString(HighlightTokenSerializer, json))
            }
        }

        var bestTotalNs = Long.MAX_VALUE
        var decodedTokens = 0
        repeat(measuredRounds) {
            var count = 0
            val start = System.nanoTime()
            for (json in tokenJsons) {
                sink += decodeSink(prismJson.decodeFromString(HighlightTokenSerializer, json))
                count++
            }
            val totalNs = System.nanoTime() - start
            if (totalNs < bestTotalNs) bestTotalNs = totalNs
            decodedTokens = count
        }
        check(sink > 0) { "decoded tokens must not be optimized away" }

        return DecodeResult(
            decodedTokens = decodedTokens,
            totalNsPerInput = bestTotalNs.toDouble(),
            nsPerToken = bestTotalNs.toDouble() / tokenJsons.size,
        )
    }

    private fun decodeSink(token: HighlightToken.Token): Int = when (token) {
        is HighlightToken.Token.Nested -> token.content.size
        is HighlightToken.Token.StringContent -> token.content.length
        is HighlightToken.Token.StringListContent -> token.content.size
    }

    /** Mirrors the exact dispatch shape of Highlighter.highlight (Highlighter.kt:52). */
    private fun <T> dispatchViaExecutor(executor: ExecutorService, block: () -> T): T = runBlocking {
        suspendCancellableCoroutine { continuation ->
            executor.submit {
                runCatching(block).fold(continuation::resume, continuation::resumeWithException)
            }
        }
    }

    private fun syntheticCode(sizeBytes: Int): String = buildString(sizeBytes) {
        var line = 0
        while (length < sizeBytes) {
            append("fun bench$line(value: Int): Int { return value + $line } // filler line\n")
            line++
        }
    }.substring(0, sizeBytes)

    /**
     * Prism-shaped token JSON as Highlighter receives it from `element.stringify()`:
     * mostly flat string-content tokens with a minority of nested tokens (mixed
     * object + primitive content), matching HighlightTokenSerializer's branches.
     */
    private fun syntheticPrismTokenJsons(count: Int): List<String> = (0 until count).map { index ->
        if (index % 5 == 4) {
            """{"type":"function","content":[{"type":"punctuation","content":"(","length":1},"bench$index"],"length":${AVG_TOKEN_CHARS}}"""
        } else {
            """{"type":"keyword","content":"token$index","length":${AVG_TOKEN_CHARS}}"""
        }
    }

    private data class DecodeResult(
        val decodedTokens: Int,
        val totalNsPerInput: Double,
        val nsPerToken: Double,
    )

    companion object {
        private val INPUT_SIZES = listOf("1KB" to 1024, "10KB" to 10240, "100KB" to 102400)
        private const val AVG_TOKEN_CHARS = 8
        private const val WARMUP_DECODES = 4000

        // Same configuration as Highlighter's private `format` (Highlighter.kt).
        private val prismJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
