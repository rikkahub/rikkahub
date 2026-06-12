package me.rerere.ai.runtime.task

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * TASK_BUDGET_MONOTONE (SPEC.md M1): budget usage counters never decrease no matter how child
 * usage reports interleave or replay, a cap breach is permanent under further usage, and a
 * breach drives any active task state into the absorbing [TaskState.BudgetExhausted].
 */
class TaskBudgetPropertyTest {

    private val arbUsage: Arb<TaskBudgetUsage> = Arb.bind(
        Arb.int(0..10_000),
        Arb.long(0L..10_000_000L),
        Arb.long(0L..7_200_000L).map { it.milliseconds },
    ) { steps, tokens, elapsed -> TaskBudgetUsage(steps = steps, tokens = tokens, elapsed = elapsed) }

    private val arbBudget: Arb<TaskBudget> = Arb.bind(
        Arb.int(1..200),
        Arb.long(1L..1_000_000L).orNull(0.3),
        Arb.long(1L..3_600_000L).map { it.milliseconds },
    ) { steps, tokens, wall -> TaskBudget(maxSteps = steps, maxTokens = tokens, wallTime = wall) }

    private fun assertNeverBelow(low: TaskBudgetUsage, merged: TaskBudgetUsage) {
        assertTrue("steps decreased: ${merged.steps} < ${low.steps}", merged.steps >= low.steps)
        assertTrue("tokens decreased: ${merged.tokens} < ${low.tokens}", merged.tokens >= low.tokens)
        assertTrue("elapsed decreased: ${merged.elapsed} < ${low.elapsed}", merged.elapsed >= low.elapsed)
    }

    @Test
    fun `defaults match the approved design - steps 64 depth 1 per-parent 1 global 1 wall 10 of 30 min`() {
        // Global concurrency is 1 since the OQ1 resolution: Android 15 caps the cumulative
        // backgrounded dataSync FGS budget (6h/24h), and a second concurrent SSE stream doubles
        // radio/wake-lock pressure under that shared budget. Raising it again should be an
        // adaptive (charging + unmetered) or user decision, not a constant.
        val budget = TaskBudget()
        assertEquals(64, budget.maxSteps)
        assertEquals(1, budget.maxDepth)
        assertEquals(1, budget.perParentConcurrency)
        assertEquals(1, budget.globalConcurrency)
        assertEquals(10.minutes, budget.wallTime)
        assertEquals(30.minutes, TaskBudget.HARD_MAX_WALL_TIME)
        assertNull(budget.maxTokens)
    }

    @Test
    fun `effective wall time is the requested time clamped to the 30 minute hard cap`() {
        runBlocking {
            checkAll(500, Arb.long(0L..7_200_000L).map { it.milliseconds }) { requested ->
                val effective = TaskBudget(wallTime = requested).effectiveWallTime
                assertTrue(effective <= TaskBudget.HARD_MAX_WALL_TIME)
                assertTrue(effective <= requested)
                if (requested <= TaskBudget.HARD_MAX_WALL_TIME) {
                    assertEquals(requested, effective)
                }
            }
        }
    }

    @Test
    fun `recording a child usage report never decreases any counter`() {
        runBlocking {
            checkAll(1_000, arbUsage, arbUsage) { current, reported ->
                val merged = current.record(reported)
                assertNeverBelow(current, merged)
                assertNeverBelow(reported, merged)
            }
        }
    }

    @Test
    fun `recording is idempotent and order-insensitive - stale or replayed reports are harmless`() {
        runBlocking {
            checkAll(1_000, arbUsage, arbUsage) { a, b ->
                assertEquals(a, a.record(a))
                assertEquals(a.record(b), b.record(a))
                assertEquals(a.record(b), a.record(b).record(b))
            }
        }
    }

    @Test
    fun `folding any report sequence yields counters that never decrease at any step`() {
        runBlocking {
            checkAll(500, Arb.list(arbUsage, 0..12)) { reports ->
                var current = TaskBudgetUsage()
                for (report in reports) {
                    val next = current.record(report)
                    assertNeverBelow(current, next)
                    current = next
                }
            }
        }
    }

    @Test
    fun `usage within every cap is not a breach`() {
        runBlocking {
            checkAll(1_000, arbBudget) { budget ->
                val withinAll = TaskBudgetUsage(
                    steps = budget.maxSteps,
                    tokens = budget.maxTokens ?: Long.MAX_VALUE,
                    elapsed = budget.effectiveWallTime,
                )
                assertNull(budget.firstBreach(withinAll))
            }
        }
    }

    @Test
    fun `exceeding a cap is a breach naming that cap`() {
        runBlocking {
            checkAll(1_000, arbBudget) { budget ->
                val stepBreach = budget.firstBreach(TaskBudgetUsage(steps = budget.maxSteps + 1))
                assertEquals(TaskBudgetCap.Steps, stepBreach?.cap)

                budget.maxTokens?.let { maxTokens ->
                    val tokenBreach = budget.firstBreach(TaskBudgetUsage(tokens = maxTokens + 1))
                    assertEquals(TaskBudgetCap.Tokens, tokenBreach?.cap)
                }

                val wallBreach = budget.firstBreach(
                    TaskBudgetUsage(elapsed = budget.effectiveWallTime + 1.milliseconds),
                )
                assertEquals(TaskBudgetCap.WallTime, wallBreach?.cap)
            }
        }
    }

    @Test
    fun `a breach is permanent - recording more usage never clears it`() {
        runBlocking {
            checkAll(1_000, arbBudget, arbUsage, arbUsage) { budget, usage, more ->
                if (budget.firstBreach(usage) != null) {
                    assertNotNull(budget.firstBreach(usage.record(more)))
                }
            }
        }
    }

    @Test
    fun `lowering caps never turns a breached run into a passing one`() {
        runBlocking {
            checkAll(1_000, arbBudget, arbUsage, Arb.int(0..50), Arb.long(0L..10_000L)) { budget, usage, lessSteps, lessTokens ->
                val tightened = budget.copy(
                    maxSteps = (budget.maxSteps - lessSteps).coerceAtLeast(1),
                    maxTokens = budget.maxTokens?.let { (it - lessTokens).coerceAtLeast(1L) },
                )
                if (budget.firstBreach(usage) != null) {
                    assertNotNull(tightened.firstBreach(usage))
                }
            }
        }
    }

    @Test
    fun `a cap breach event drives every active state into BudgetExhausted`() {
        runBlocking {
            val arbActiveState: Arb<TaskState> = Arb.choice(
                Arb.constant(TaskState.Created),
                Arb.constant(TaskState.Queued),
                Arb.constant(TaskState.Starting),
                Arb.constant(TaskState.Running),
                Arb.constant(TaskState.Resuming),
                Arb.bind(
                    Arb.constant("call"),
                    Arb.constant("tool"),
                ) { c, t -> TaskState.WaitingApproval(TaskApprovalRequest(childToolCallId = c, toolName = t)) },
            )
            checkAll(1_000, arbActiveState, arbBudget, arbUsage) { state, budget, usage ->
                val breach = budget.firstBreach(usage) ?: return@checkAll
                assertEquals(
                    TaskState.BudgetExhausted(breach),
                    TaskStateReducer.reduce(state, TaskEvent.BudgetExceeded(breach)),
                )
            }
        }
    }
}
