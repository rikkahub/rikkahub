package me.rerere.ai.runtime.knowledge

import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

/**
 * Unit coverage for [KnowledgeBudget.of] (issue #141): the 0.25 fraction (Q3), reuse of #193's
 * [ModelRegistry.getContextWindowForModel] (Q4) including the explicit override and the conservative
 * default fallback, and the `coerceAtLeast(0)` floor when the system prompt alone exceeds the slice.
 */
class KnowledgeBudgetTest {

    @Test
    fun `budget is a quarter of the explicit context window minus the system prompt`() {
        val model = Model(contextWindow = 200_000)
        val systemPromptTokens = 1_234

        val expected = floor(200_000 * 0.25).toInt() - systemPromptTokens // 50_000 - 1_234
        assertEquals(expected, KnowledgeBudget.of(model, systemPromptTokens))
        assertEquals(48_766, KnowledgeBudget.of(model, systemPromptTokens))
    }

    @Test
    fun `budget reuses getContextWindowForModel for the explicit override`() {
        // Asserting equality with the #193 primitive proves we route through it (Q4), not a constant.
        val model = Model(contextWindow = 64_000)
        val window = ModelRegistry.getContextWindowForModel(model)

        val expected = floor(window * KnowledgeBudget.KNOWLEDGE_FRACTION).toInt() - 100
        assertEquals(expected, KnowledgeBudget.of(model, 100))
        assertEquals(64_000, window)
    }

    @Test
    fun `budget falls back to the conservative default window for an unknown model`() {
        // No contextWindow override and no registry family match -> DEFAULT_CONTEXT_WINDOW (128k).
        val model = Model()
        val window = ModelRegistry.getContextWindowForModel(model)
        assertEquals(ModelRegistry.DEFAULT_CONTEXT_WINDOW, window)

        val expected = floor(128_000 * 0.25).toInt() // 32_000, no system prompt
        assertEquals(expected, KnowledgeBudget.of(model, 0))
        assertEquals(32_000, KnowledgeBudget.of(model, 0))
    }

    @Test
    fun `system prompt larger than the slice yields a zero budget, never negative`() {
        val model = Model() // 128k window -> 32k slice
        // A 40k system prompt exceeds the 32k knowledge slice.
        assertEquals(0, KnowledgeBudget.of(model, 40_000))
    }
}
