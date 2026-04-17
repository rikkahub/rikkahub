package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicThinkingBudgetWarningTest {
    @Test
    fun `Claude 4_7 custom positive budgets should show warning`() {
        assertEquals(
            CLAUDE_47_THINKING_BUDGET_WARNING,
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4.7-20260301",
                thinkingBudget = 5000
            )
        )
        assertEquals(
            CLAUDE_47_THINKING_BUDGET_WARNING,
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = 20_000
            )
        )
    }

    @Test
    fun `Claude 4_7 off auto and preset budgets should not show warning`() {
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = 0
            )
        )
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = -1
            )
        )
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = 1024
            )
        )
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = 16_000
            )
        )
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-opus-4-7-20260301",
                thinkingBudget = 32_000
            )
        )
    }

    @Test
    fun `legacy Claude and Claude 4_6 should not show warning`() {
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-sonnet-4-5-20250929",
                thinkingBudget = 20_000
            )
        )
        assertNull(
            buildAnthropicThinkingBudgetWarning(
                modelId = "claude-sonnet-4-6-20251001",
                thinkingBudget = 20_000
            )
        )
    }
}
