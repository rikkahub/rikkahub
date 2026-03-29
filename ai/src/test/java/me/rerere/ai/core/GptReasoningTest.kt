package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GptReasoningTest {
    @Test
    fun `fromBudgetTokens should keep exact minimal and xhigh presets`() {
        assertEquals(ReasoningLevel.AUTO, ReasoningLevel.fromBudgetTokens(null))
        assertEquals(ReasoningLevel.MINIMAL, ReasoningLevel.fromBudgetTokens(1))
        assertEquals(ReasoningLevel.XHIGH, ReasoningLevel.fromBudgetTokens(64_000))
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.fromBudgetTokens(1_000))
    }

    @Test
    fun `should resolve supported gpt reasoning levels for latest model buckets`() {
        assertEquals(
            listOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            getSupportedGptReasoningLevels("gpt-5")
        )
        assertEquals(
            listOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            getSupportedGptReasoningLevels("gpt-5-mini")
        )
        assertEquals(
            listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.1-codex-max")
        )
        assertEquals(
            listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.3-codex")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-mini")
        )
        assertEquals(
            listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-pro")
        )
    }

    @Test
    fun `should clamp unsupported gpt reasoning levels upward within supported matrix`() {
        assertEquals(ReasoningLevel.MINIMAL, resolveGptReasoningLevel("gpt-5", 0))
        assertEquals(ReasoningLevel.LOW, resolveGptReasoningLevel("gpt-5.1", 1))
        assertEquals(ReasoningLevel.MEDIUM, resolveGptReasoningLevel("gpt-5.1-codex", 1024))
        assertEquals(ReasoningLevel.HIGH, resolveGptReasoningLevel("gpt-5", 64_000))
        assertEquals(ReasoningLevel.MEDIUM, resolveGptReasoningLevel("gpt-5.4-pro", 0))
    }
}
