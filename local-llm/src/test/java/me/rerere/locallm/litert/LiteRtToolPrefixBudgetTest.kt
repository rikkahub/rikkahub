package me.rerere.locallm.litert

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtToolPrefixBudgetTest {

    @Test
    fun `4k context model gets the small-context cap`() {
        val budget = LiteRtToolPrefix.budgetForContext(maxNumTokens = 4096)
        assertEquals(25, budget.maxTools)
        assertEquals(2000, budget.maxChars)
    }

    @Test
    fun `8k context model gets a generous middle cap`() {
        val budget = LiteRtToolPrefix.budgetForContext(maxNumTokens = 8192)
        assertEquals(60, budget.maxTools)
        assertEquals(4500, budget.maxChars)
    }

    @Test
    fun `32k context model gets every tool with no cap`() {
        val budget = LiteRtToolPrefix.budgetForContext(maxNumTokens = 32000)
        assertEquals(Int.MAX_VALUE, budget.maxTools)
        assertEquals(12000, budget.maxChars)
    }

    @Test
    fun `over-32k context still capped at the same large band`() {
        val budget = LiteRtToolPrefix.budgetForContext(maxNumTokens = 131072)
        assertEquals(Int.MAX_VALUE, budget.maxTools)
        assertEquals(12000, budget.maxChars)
    }

    @Test
    fun `tiny context (1k) still gets the small cap`() {
        val budget = LiteRtToolPrefix.budgetForContext(maxNumTokens = 1024)
        assertEquals(25, budget.maxTools)
        assertEquals(2000, budget.maxChars)
    }
}
