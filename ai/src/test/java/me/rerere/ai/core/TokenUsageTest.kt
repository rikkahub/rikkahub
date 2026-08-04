package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenUsageTest {
    @Test
    fun `merge combines fragmented usage from one request`() {
        val usage = TokenUsage(promptTokens = 120, cachedTokens = 40)
            .merge(TokenUsage(completionTokens = 30))

        assertEquals(
            TokenUsage(
                promptTokens = 120,
                completionTokens = 30,
                cachedTokens = 40,
                totalTokens = 150,
            ),
            usage,
        )
    }

    @Test
    fun `merge replaces cumulative snapshots instead of adding them`() {
        val usage = TokenUsage(promptTokens = 120, completionTokens = 10)
            .merge(TokenUsage(promptTokens = 120, completionTokens = 30))

        assertEquals(120, usage.promptTokens)
        assertEquals(30, usage.completionTokens)
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun `a new request accumulator does not retain previous cached usage`() {
        val previousRequest = TokenUsage(promptTokens = 120, cachedTokens = 40)
        val nextRequest = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 80, completionTokens = 20)
        )

        assertEquals(40, previousRequest.cachedTokens)
        assertEquals(0, nextRequest.cachedTokens)
        assertEquals(100, nextRequest.totalTokens)
    }
}
