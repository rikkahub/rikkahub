package me.rerere.rikkahub.data.ai.knowledge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.estimateTokens
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * High-value PBT target for issue #141: the pure [KnowledgeContextAssembler]. These properties are
 * exactly the design's P1-P7 (section 7). Each fails on a deliberately-wrong assembler — e.g. P1
 * fails if the greedy bound is `<` instead of `<=` or if a block is force-included (Q1 violation);
 * P7 fails if a generous budget drops or reorders the input set (the no-regression guard that
 * protects today's RAG/attachment flows).
 *
 * [KnowledgeContextBlock.estimatedTokens] for each generated block is the SHARED #193 estimator over
 * the block's content, so the suite exercises the real cost function (issue #141 Q4: build on #193,
 * no second estimator).
 */
class KnowledgeContextAssemblerPropertyTest {

    private fun costOf(content: String): Int =
        estimateTokens(listOf(UIMessagePart.Text(content)))

    private val arbBlock: Arb<KnowledgeContextBlock> = arbitrary {
        val content = Arb.string(0..400).bind()
        KnowledgeContextBlock(
            source = Arb.enum<KnowledgeSource>().bind(),
            scope = Arb.enum<KnowledgeScope>().bind(),
            title = Arb.string(0..20).orNull(0.3).bind(),
            content = content,
            priority = Arb.int(-100..100).bind(),
            estimatedTokens = costOf(content),
        )
    }

    private val arbBlocks: Arb<List<KnowledgeContextBlock>> = Arb.list(arbBlock, 0..12)

    /** Blocks + a budget spanning [0, 2*sum] so empty, partial, and generous regimes are all hit. */
    private val arbCase: Arb<Pair<List<KnowledgeContextBlock>, Int>> = arbitrary {
        val blocks = arbBlocks.bind()
        val total = blocks.sumOf { it.estimatedTokens }
        val budget = Arb.int(0..(total * 2 + 1)).bind()
        blocks to budget
    }

    /** Blocks + TWO budgets, for the monotonicity property (P3). */
    private val arbTwoBudgetCase: Arb<Triple<List<KnowledgeContextBlock>, Int, Int>> = arbitrary {
        val blocks = arbBlocks.bind()
        val total = blocks.sumOf { it.estimatedTokens }
        val b1 = Arb.int(0..(total * 2 + 1)).bind()
        val b2 = Arb.int(0..(total * 2 + 1)).bind()
        Triple(blocks, b1, b2)
    }

    // ---- P1 / P2 : core safety ------------------------------------------------------------------

    @Test
    fun `P1 - sum of estimated tokens never exceeds the budget`() {
        runBlocking {
            checkAll(400, arbCase) { (blocks, budget) ->
                val out = KnowledgeContextAssembler.assemble(blocks, budget)
                val sum = out.sumOf { it.estimatedTokens }
                assertTrue("sum=$sum must be <= budget=$budget", sum <= budget)
                // Q1 = NO must-include-one: a budget too small for any block yields empty.
                if (blocks.isNotEmpty() && budget < blocks.minOf { it.estimatedTokens }) {
                    assertTrue("budget too small for any block must select none", out.isEmpty())
                }
            }
        }
    }

    @Test
    fun `P1 boundary - near-MAX estimatedTokens never overflow past the budget`() {
        // Regression for the Int-overflow gate finding (codex): with Int accumulation, two
        // near-MAX_VALUE blocks both passing `running + est <= budget` would wrap negative and slip
        // through, breaking P1. The Long accumulator must admit at most the one that fits.
        val big = KnowledgeContextBlock(
            source = KnowledgeSource.ATTACHMENT,
            scope = KnowledgeScope.MESSAGE,
            title = null,
            content = "x",
            priority = 0,
            estimatedTokens = Int.MAX_VALUE - 10,
        )
        val out = KnowledgeContextAssembler.assemble(
            listOf(big, big.copy(priority = -1)),
            Int.MAX_VALUE,
        )
        // Sum as Long — an Int sum of two near-MAX blocks would itself overflow.
        val sum = out.sumOf { it.estimatedTokens.toLong() }
        assertTrue("sum=$sum must be <= budget=${Int.MAX_VALUE}", sum <= Int.MAX_VALUE.toLong())
        assertEquals("only one near-MAX block fits the budget", 1, out.size)
    }

    @Test
    fun `P2 - every output block has a known source and scope and came from the input`() {
        runBlocking {
            checkAll(200, arbCase) { (blocks, budget) ->
                val out = KnowledgeContextAssembler.assemble(blocks, budget)
                out.forEach {
                    assertTrue("source must be a known value", it.source in KnowledgeSource.entries)
                    assertTrue("scope must be a known value", it.scope in KnowledgeScope.entries)
                }
                assertTrue("output must be a subset of input", out.all { it in blocks })
            }
        }
    }

    // ---- P6 : ordering --------------------------------------------------------------------------

    @Test
    fun `P6 - output is priority-descending with stable source-ordinal tiebreak`() {
        runBlocking {
            checkAll(300, arbCase) { (blocks, budget) ->
                val out = KnowledgeContextAssembler.assemble(blocks, budget)
                out.zipWithNext { a, b ->
                    val ordered = a.priority > b.priority ||
                        (a.priority == b.priority && a.source.ordinal <= b.source.ordinal)
                    assertTrue(
                        "ordering broken: (${a.priority},${a.source}) then (${b.priority},${b.source})",
                        ordered,
                    )
                }
            }
        }
    }

    // ---- P3 / P4 : budget monotonicity ----------------------------------------------------------

    /**
     * P3 — token monotonicity: lowering the budget never increases the injected TOKEN total. This is
     * the safety-relevant clause (it is what makes "less budget never injects more content" true, the
     * companion of P1's hard bound) and it holds unconditionally for the chosen greedy-with-skip
     * selection.
     *
     * The design also listed a *count* clause ("never increases emitted block count"). That clause is
     * provably FALSE for the non-starving greedy-with-skip the design mandates, and is intentionally
     * NOT asserted here. Exhaustive small-space search produced a minimal counterexample
     * `[(p=0,est=1),(p=0,est=1),(p=1,est=3)]`, lo=2 hi=3: at budget 2 the two low-priority size-1
     * blocks fit (count=2); at budget 3 the single higher-priority size-3 block is admitted first and
     * fills the budget (count=1) — so a *higher* budget yields *fewer* blocks. Count-monotonicity is
     * fundamentally incompatible with "a large high-priority block must not be skipped to fit many
     * tiny low-priority ones": you cannot both prefer priority AND maximize block count. We keep
     * non-starvation (the design's explicit requirement) and the token+priority invariants (P1/P4/P6),
     * and drop the contradictory count clause. See PR body.
     */
    @Test
    fun `P3 - lowering the budget never increases total tokens`() {
        runBlocking {
            checkAll(300, arbTwoBudgetCase) { (blocks, b1, b2) ->
                val lo = minOf(b1, b2)
                val hi = maxOf(b1, b2)
                val outLo = KnowledgeContextAssembler.assemble(blocks, lo)
                val outHi = KnowledgeContextAssembler.assemble(blocks, hi)
                assertTrue(
                    "token sum must not grow when budget shrinks",
                    outLo.sumOf { it.estimatedTokens } <= outHi.sumOf { it.estimatedTokens },
                )
            }
        }
    }

    @Test
    fun `P4 - adding a strictly lower-priority candidate never evicts an included block`() {
        runBlocking {
            checkAll(300, arbCase) { (blocks, budget) ->
                val selected = KnowledgeContextAssembler.assemble(blocks, budget)
                if (selected.isNotEmpty()) {
                    // A candidate strictly below every selected block's priority.
                    val minPriority = selected.minOf { it.priority }
                    val intruder = KnowledgeContextBlock(
                        source = KnowledgeSource.RAG,
                        scope = KnowledgeScope.MESSAGE,
                        title = null,
                        content = "x",
                        priority = minPriority - 1,
                        estimatedTokens = 1,
                    )
                    val reassembled = KnowledgeContextAssembler.assemble(blocks + intruder, budget)
                    // Every previously-selected block must still be present (no higher-priority eviction).
                    selected.forEach { block ->
                        assertTrue(
                            "lower-priority addition evicted an included block: $block",
                            reassembled.contains(block),
                        )
                    }
                }
            }
        }
    }

    // ---- P5 : determinism / idempotence ---------------------------------------------------------

    @Test
    fun `P5 - assemble is deterministic and re-assembling the selection is stable`() {
        runBlocking {
            checkAll(300, arbCase) { (blocks, budget) ->
                val a = KnowledgeContextAssembler.assemble(blocks, budget)
                val b = KnowledgeContextAssembler.assemble(blocks, budget)
                assertEquals("same input must yield identical output", a, b)
                // Re-assembling the already-selected set at the same budget is a fixpoint.
                val again = KnowledgeContextAssembler.assemble(a, budget)
                assertEquals("re-assembling the selection must be stable", a, again)
            }
        }
    }

    // ---- P7 : no-regression / conservation ------------------------------------------------------

    @Test
    fun `P7 - a generous budget keeps the whole input set`() {
        runBlocking {
            checkAll(300, arbBlocks) { blocks ->
                val generous = blocks.sumOf { it.estimatedTokens } + 1
                val out = KnowledgeContextAssembler.assemble(blocks, generous)
                // Order is normalized by the assembler; compare as multisets (duplicates allowed).
                assertEquals(
                    "generous budget must conserve the input set",
                    blocks.groupingBy { it }.eachCount(),
                    out.groupingBy { it }.eachCount(),
                )
            }
        }
    }

    // ---- Combined cross-surface budget (issue #141 Phase 2) -------------------------------------

    /**
     * Phase 2 routes MEMORY through the assembler on the SYSTEM-prompt surface while Phase 1's
     * KnowledgeContextTransformer keeps RAG/attachments on the MESSAGE surface. The design's
     * memory-first / message-auto-remainder split must keep their COMBINED selected token total within
     * the same `floor(0.25*window)` slice — the assembler is invoked twice, once per surface, and the
     * message budget recomputes its systemPromptTokens as `base + renderedMemory`, so the memory it
     * selects is subtracted from the message slice rather than added on top.
     *
     * This models that arithmetic purely:
     *   memBudget = floor(0.25w) - base                      (system surface)
     *   memTokens = sum(assemble(memoryBlocks, memBudget))   (<= memBudget, P1)
     *   msgBudget = KnowledgeBudget.of(model, base + memTokens) = floor(0.25w) - (base + memTokens)
     *   msgTokens = sum(assemble(messageBlocks, msgBudget))  (<= msgBudget, P1)
     *   => memTokens + msgTokens <= floor(0.25w) - base <= floor(0.25w)
     *
     * It FAILS if KnowledgeBudget.of stopped subtracting systemPromptTokens, or if the message budget
     * were modeled as floor(0.25w) - base (i.e. the auto-remainder forgot to include the rendered
     * memory) — exactly the regressions Phase 2 must not introduce.
     */
    @Test
    fun `combined memory(system) + message budgets never exceed the knowledge slice`() {
        runBlocking {
            checkAll(400, arbCombinedCase) { (model, base, memoryBlocks, messageBlocks) ->
                val window = ModelRegistryWindow.of(model)
                val slice = floor(window * KnowledgeBudget.KNOWLEDGE_FRACTION).toInt()

                val memBudget = KnowledgeBudget.of(model, base)
                val selMem = KnowledgeContextAssembler.assemble(memoryBlocks, memBudget)
                val memTokens = selMem.sumOf { it.estimatedTokens }

                // The Phase 1 transformer recomputes systemPromptTokens over the materialized SYSTEM
                // message = base + rendered memory; model that as the message-surface budget.
                val msgBudget = KnowledgeBudget.of(model, base + memTokens)
                val selMsg = KnowledgeContextAssembler.assemble(messageBlocks, msgBudget)
                val msgTokens = selMsg.sumOf { it.estimatedTokens }

                // The real contract is the TIGHTER `<= slice - base` (the available knowledge budget),
                // floored at 0 when the base alone exceeds the slice. Asserting only `<= slice` would
                // pass even if the auto-remainder forgot to subtract the rendered memory (the exact
                // regression Phase 2 must not introduce). (cross-model gate: codex.)
                val availableKnowledge = (slice - base).coerceAtLeast(0)
                assertTrue(
                    "combined mem=$memTokens + msg=$msgTokens must be <= slice-base=$availableKnowledge (slice=$slice base=$base)",
                    memTokens + msgTokens <= availableKnowledge,
                )
            }
        }
    }

    /**
     * (model, base, memoryBlocks, messageBlocks) where `base` is a valid system-prompt cost in
     * `[0, floor(0.25w)]` and each block carries the shared-estimator cost. Uses an explicit
     * contextWindow so the window is known (and `getContextWindowForModel` returns it verbatim,
     * locked by [KnowledgeBudgetTest]).
     */
    private val arbCombinedCase:
        Arb<Quad<Model, Int, List<KnowledgeContextBlock>, List<KnowledgeContextBlock>>> = arbitrary {
        val window = Arb.int(1_000..1_000_000).bind()
        val model = Model(contextWindow = window)
        val slice = floor(window * KnowledgeBudget.KNOWLEDGE_FRACTION).toInt()
        val base = Arb.int(0..slice).bind()
        val memoryBlocks = arbBlocks.bind()
        val messageBlocks = arbBlocks.bind()
        Quad(model, base, memoryBlocks, messageBlocks)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Local mirror of the window resolution the budget uses, so the property can compute the slice. */
    private object ModelRegistryWindow {
        fun of(model: Model): Int = me.rerere.ai.registry.ModelRegistry.getContextWindowForModel(model)
    }
}
