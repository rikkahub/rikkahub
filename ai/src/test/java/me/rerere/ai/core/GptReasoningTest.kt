package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            getSupportedGptReasoningLevels("gpt-5-preview")
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
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            getSupportedGptReasoningLevels("gpt-5.1-preview")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.2-preview")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.2-mini")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.3")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.3-codex")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-codex")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-mini")
        )
        assertEquals(
            listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-pro")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-2026-03-05")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.4-mini-2026-03-05")
        )
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            getSupportedGptReasoningLevels("gpt-5.9")
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

    @Test
    fun `should reject chat aliases`() {
        assertNull(getSupportedGptReasoningLevels("gpt-5-chat-latest"))
        assertNull(getSupportedGptReasoningLevels("gpt-5.2-chat-latest"))
        assertNull(getSupportedGptReasoningLevels("gpt-5.3-chat-latest"))
    }

    @Test
    fun `should resolve compatibility reasoning levels without gpt only presets`() {
        assertEquals(ReasoningLevel.AUTO, resolveCompatibilityReasoningLevel(null))
        assertEquals(ReasoningLevel.LOW, resolveCompatibilityReasoningLevel(1))
        assertEquals(ReasoningLevel.HIGH, resolveCompatibilityReasoningLevel(64_000))
        assertEquals(ReasoningLevel.LOW, resolveCompatibilityReasoningLevel(5_000))
    }

    @Test
    fun `should only normalize stored gpt only budgets for non gpt models`() {
        assertEquals(1024, normalizeStoredThinkingBudget("o3", 1))
        assertEquals(32_000, normalizeStoredThinkingBudget("o4-mini", 64_000))
        assertEquals(5_000, normalizeStoredThinkingBudget("o3", 5_000))
        assertEquals(1, normalizeStoredThinkingBudget("gpt-5", 1))
        assertEquals(64_000, normalizeStoredThinkingBudget("gpt-5.4-mini", 64_000))
        assertEquals(64_000, normalizeStoredThinkingBudget(null, 64_000))
    }
}
