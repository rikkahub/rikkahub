package me.rerere.ai.runtime.knowledge

/**
 * The single authority for *selection + ordering + budget* of knowledge context blocks (issue #141).
 * Pure and deterministic: same `(blocks, budgetTokens)` in, same list out — no IO, no clock, no
 * randomness. Rendering *placement* (system-prompt vs near-last-user-message) is a separate,
 * scope-driven step ([KnowledgeContextRenderer] + the transformer); this object never touches text.
 *
 * Invariants (the design's PBT P1-P7, [me.rerere.ai.runtime.knowledge] tests):
 *  - **Budget (P1):** `Sum(estimatedTokens(out)) <= budgetTokens`, always — over each block's
 *    DECLARED [KnowledgeContextBlock.estimatedTokens]. A block whose own estimate exceeds the budget
 *    is simply never selected; it is not truncated here. Truncation-with-elision is a render-time
 *    concern (see the seam note below), kept out of selection so this invariant holds unconditionally.
 *  - **Subset:** output is a subset of input (no fabricated blocks).
 *  - **Ordering (P6):** priority descending, with [KnowledgeSource] ordinal as a stable tiebreak for
 *    equal priorities; cross-source precedence is carried by the priority the emitter assigns.
 *  - **Determinism (P5):** [List.sortedWith] is a stable sort, so equal keys preserve input order →
 *    identical input yields identical output even with duplicate blocks.
 *  - **Token monotonicity (P3):** lowering the budget never increases the injected token total. NOTE
 *    this is the *token* clause only; block-*count* is deliberately NOT monotone, because a higher
 *    budget may admit a large high-priority block that displaces several small low-priority ones — an
 *    unavoidable consequence of preferring priority over count (the non-starvation choice below).
 *  - **No must-include-one (issue #141 Q1 = NO):** when the budget is too small for *any* block,
 *    the result is `emptyList()`. A block is never force-included over its own budget.
 *
 * **Render-time seam (single block > budget):** the design's "truncate a single over-budget block
 * with an elision marker" lives in the renderer/transformer path, NOT here — because Q1 resolves to
 * "no force-include", Phase 1 never force-selects an over-budget block, so [assemble] stays
 * pure-selection and the budget invariant is exact. If a future phase wants forced inclusion of the
 * top block, add the elision there and recompute its estimate to `<= budget` before it reaches this
 * function; do not relax the invariant here.
 */
object KnowledgeContextAssembler {

    /**
     * Selects the highest-priority subset of [blocks] whose summed [KnowledgeContextBlock.estimatedTokens]
     * fits within [budgetTokens], in priority order.
     *
     * Greedy with skip (not break): blocks are walked in `(priority desc, source ordinal)` order and
     * each is taken iff it still fits the running budget; an over-budget block is *skipped*, not a
     * stop point, so a later smaller block can still be included and a single large high-priority
     * block cannot starve everything below it. The walk order is the selection precedence, so
     * skipping a block never lets a lower-priority block evict a higher-priority one (P4).
     */
    fun assemble(blocks: List<KnowledgeContextBlock>, budgetTokens: Int): List<KnowledgeContextBlock> {
        if (budgetTokens <= 0) return emptyList()

        val ordered = blocks.sortedWith(
            compareByDescending<KnowledgeContextBlock> { it.priority }
                .thenBy { it.source.ordinal }
        )

        val selected = ArrayList<KnowledgeContextBlock>(ordered.size)
        // Long accumulator: a block's estimatedTokens can be large and an Int `running + est` could
        // overflow, wrap negative, and slip past the `<= budget` check — breaking P1. budgetTokens is
        // promoted to Long in the compare; a (pathological) negative estimate is skipped, never treated
        // as free budget. (cross-model gate: codex Int-overflow finding on #141 Phase 1.)
        var running = 0L
        for (block in ordered) {
            val cost = block.estimatedTokens.toLong()
            if (cost >= 0 && running + cost <= budgetTokens) {
                selected.add(block)
                running += cost
            }
        }
        return selected
    }
}
