package me.rerere.ai.runtime.hooks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property suite over [aggregate] — the pure fold the hook dispatcher uses to combine the
 * outputs of every matched handler into one [AggregatedHookResult] the agent loop consumes.
 *
 * Properties (spec §Testing Strategy):
 * - INVARIANT: decision is the most restrictive across outputs — deny > ask > allow.
 * - METAMORPHIC: appending a passthrough Allow output never changes the aggregate.
 * - BOUNDARY: empty output set aggregates to the Allow passthrough.
 * - CONSERVATION: additionalContext is the order-stable concatenation of all contributors.
 * - DETERMINISM: updatedInput chaining is deterministic for a fixed handler order.
 */
class HookAggregatePropertyTest {

    private val arbDecision: Arb<HookDecision> = Arb.choice(
        Arb.of(listOf<HookDecision>(HookDecision.Allow)),
        Arb.of(listOf<HookDecision>(HookDecision.Ask)),
        Arb.string(0..8).map { HookDecision.Deny(it) },
    )

    private val arbOutput: Arb<HookOutput> = arbitrary {
        HookOutput(
            decision = arbDecision.bind(),
            updatedInput = Arb.string(0..8).orNull(0.4).bind(),
            additionalContext = Arb.string(0..8).orNull(0.4).bind(),
            preventContinuation = Arb.boolean().bind(),
        )
    }

    private val arbOutputs: Arb<List<HookOutput>> = Arb.list(arbOutput, 0..8)

    @Test
    fun `empty output set aggregates to the Allow passthrough`() {
        val result = aggregate(emptyList())
        assertEquals(HookDecision.Allow, result.decision)
        assertNull(result.updatedInput)
        assertNull(result.additionalContext)
        assertFalse(result.preventContinuation)
    }

    @Test
    fun `decision is the most restrictive across outputs - deny over ask over allow`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                val decision = aggregate(outputs).decision
                when {
                    outputs.any { it.decision is HookDecision.Deny } ->
                        assertTrue(decision is HookDecision.Deny)

                    outputs.any { it.decision == HookDecision.Ask } ->
                        assertEquals(HookDecision.Ask, decision)

                    else ->
                        assertEquals(HookDecision.Allow, decision)
                }
            }
        }
    }

    @Test
    fun `deny reason is the first deny in handler order`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                val firstDeny = outputs.map { it.decision }
                    .firstOrNull { it is HookDecision.Deny }
                if (firstDeny != null) {
                    assertEquals(firstDeny, aggregate(outputs).decision)
                }
            }
        }
    }

    @Test
    fun `appending a passthrough Allow output never changes the aggregate`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                val withAllow = outputs + HookOutput(decision = HookDecision.Allow)
                // Stronger than the decision-only requirement: a passthrough Allow contributes
                // no rewrite, no context, and no preventContinuation, so the whole result holds.
                assertEquals(aggregate(outputs), aggregate(withAllow))
                assertEquals(aggregate(outputs).decision, aggregate(withAllow).decision)
            }
        }
    }

    @Test
    fun `additionalContext is the order-stable concatenation of all contributors`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                val contributors = outputs.mapNotNull { it.additionalContext }
                val expected = if (contributors.isEmpty()) null else contributors.joinToString("\n")
                assertEquals(expected, aggregate(outputs).additionalContext)
            }
        }
    }

    @Test
    fun `updatedInput chaining resolves to the last rewrite in handler order`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                val expected = outputs.mapNotNull { it.updatedInput }.lastOrNull()
                assertEquals(expected, aggregate(outputs).updatedInput)
            }
        }
    }

    @Test
    fun `aggregate is deterministic for a fixed handler order`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                assertEquals(aggregate(outputs), aggregate(outputs))
            }
        }
    }

    @Test
    fun `preventContinuation aggregates as logical or`() {
        runBlocking {
            checkAll(500, arbOutputs) { outputs ->
                assertEquals(
                    outputs.any { it.preventContinuation },
                    aggregate(outputs).preventContinuation,
                )
            }
        }
    }
}
