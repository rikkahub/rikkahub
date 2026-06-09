package me.rerere.rikkahub.data.ai.knowledge

import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import kotlin.math.floor

/**
 * Derives the token budget the [KnowledgeContextAssembler] is allowed to spend on injected knowledge
 * (issue #141), as a model-relative slice of the context window MINUS what the system prompt already
 * costs.
 *
 * Reuses #193's primitives rather than inventing a second estimator (issue #141 Q4): the window comes
 * from [ModelRegistry.getContextWindowForModel] (explicit override -> registry family -> conservative
 * default), and the caller computes [systemPromptTokens] with the shared `me.rerere.ai.core.estimateTokens`
 * over the already-materialized system message. Taking the precomputed Int (not a Model+Settings pair)
 * keeps this object trivially unit-testable and avoids reaching into the GenerationHandler system-prompt
 * builder — that is the Phase 2 surface and stays untouched here.
 */
object KnowledgeBudget {

    /**
     * Fraction of the context window reserved for injected knowledge (issue #141 Q3). 0.25 leaves
     * three quarters of the window for the conversation itself and the model's reply.
     */
    const val KNOWLEDGE_FRACTION = 0.25

    /**
     * @param model the target model; its window is resolved via #193.
     * @param systemPromptTokens conservative token cost of the already-built system prompt, from the
     *   shared estimator. Subtracted so knowledge + system prompt together stay within the slice.
     * @return a non-negative budget. [coerceAtLeast] keeps it >= 0 even when the system prompt alone
     *   exceeds the slice; the assembler then selects nothing (safe, matches Q1 = no force-include).
     */
    fun of(model: Model, systemPromptTokens: Int): Int {
        val window = ModelRegistry.getContextWindowForModel(model)
        // Long math + clamp to a valid Int budget: keeps the subtraction overflow-safe for any
        // window / systemPromptTokens pair (cross-model gate). systemPromptTokens is a non-negative
        // estimate in practice; the clamp also tolerates a bad caller without producing a bogus budget.
        val slice = floor(window * KNOWLEDGE_FRACTION).toLong()
        return (slice - systemPromptTokens).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
