package me.rerere.rikkahub.service

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.contextTokens
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the compaction usage-strip data-loss bug (design #193 Stage 1).
 *
 * The kept-message rewrite in [ChatService.compressConversation] used to do `copy(usage = null)`,
 * which is persisted to Room by saveConversation. That permanently erased prompt/completion/cached
 * tokens — fields the lifetime aggregate (getTokenStats SUMs them straight out of the persisted JSON)
 * and the per-message nerd line read back. Only totalTokens is the stale pressure anchor that
 * compaction invalidates; the other three are real per-message stats and must survive.
 *
 * The transform is extracted as the pure function [invalidateStalePressureAnchor] (the same
 * JVM-unit-testable seam pattern as [nextAutoCompactFailureCount]); these properties exercise the
 * production decision logic without the Android/Koin/network stack.
 */
class CompactPressureAnchorTest {

    private fun keptMessage(usage: TokenUsage?) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("kept response")),
            usage = usage,
        )

    // ---- The data-loss regression: the three accounting fields must survive ----

    @Test
    fun `invalidation preserves prompt completion and cached tokens`() {
        val original = TokenUsage(
            promptTokens = 4000,
            completionTokens = 500,
            cachedTokens = 1200,
            totalTokens = 4500,
        )
        val result = invalidateStalePressureAnchor(original)
        assertNotNull("usage must not be nulled — that erases the lifetime aggregate", result)
        assertEquals("prompt tokens are a real per-message stat", 4000, result!!.promptTokens)
        assertEquals("completion tokens are a real per-message stat", 500, result.completionTokens)
        assertEquals("cached tokens are a real per-message stat", 1200, result.cachedTokens)
    }

    @Test
    fun `invalidation zeroes only the stale pressure anchor`() {
        val result = invalidateStalePressureAnchor(
            TokenUsage(promptTokens = 4000, completionTokens = 500, cachedTokens = 1200, totalTokens = 4500),
        )
        assertEquals("totalTokens is the stale anchor and must be zeroed", 0, result!!.totalTokens)
    }

    @Test
    fun `invalidation is null-safe`() {
        assertEquals(null, invalidateStalePressureAnchor(null))
    }

    @Test
    fun `the three accounting fields are preserved for any usage`() {
        runBlocking {
            checkAll(
                Arb.int(0..1_000_000),
                Arb.int(0..1_000_000),
                Arb.int(0..1_000_000),
                Arb.int(0..2_000_000),
            ) { prompt, completion, cached, total ->
                val result = invalidateStalePressureAnchor(
                    TokenUsage(prompt, completion, cached, total),
                )!!
                assertEquals(prompt, result.promptTokens)
                assertEquals(completion, result.completionTokens)
                assertEquals(cached, result.cachedTokens)
                assertEquals(0, result.totalTokens)
            }
        }
    }

    // ---- Metamorphic: the invalidated kept message no longer anchors the trigger ----

    @Test
    fun `after invalidation contextTokens stops anchoring on the kept message`() {
        // A compacted history: one summary user message, then a kept assistant message that recorded a
        // stale-high totalTokens from a request that included the now-summarized prefix.
        val summary = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("summary of prior turns")))
        val staleTotal = 80_000
        val keptStale = keptMessage(TokenUsage(promptTokens = 4000, completionTokens = 500, cachedTokens = 0, totalTokens = staleTotal))

        val beforeInvalidation = contextTokens(listOf(summary, keptStale))
        // With the stale anchor present, contextTokens reports (at least) the stale total.
        assertTrue(
            "precondition: the stale anchor inflates the measurement",
            beforeInvalidation >= staleTotal,
        )

        val keptFixed = keptStale.copy(usage = invalidateStalePressureAnchor(keptStale.usage))
        val afterInvalidation = contextTokens(listOf(summary, keptFixed))

        // No message now has totalTokens > 0, so contextTokens falls back to the conservative estimate
        // of the (small) post-compaction history — far below the stale anchor.
        assertTrue(
            "invalidating the anchor must drop the measurement below the stale total",
            afterInvalidation < staleTotal,
        )
    }
}
