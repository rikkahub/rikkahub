package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.runtime.contract.TaskBudgetClock
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

/**
 * Regression guard for the wall-time budget clock (review finding #1). The DI/secondary
 * [me.rerere.rikkahub.data.ai.task.TaskCoordinator] construction must bind a REAL monotonic
 * clock: the prior default of `{ Duration.ZERO }` made `elapsed` always 0, so the wall-time cap
 * could never trip in the shipped build. This pins that the production clock advances and never
 * reports a constant zero.
 */
class MonotonicTaskBudgetClockTest {

    @Test
    fun `monotonicNow advances across readings and is never a constant zero`() {
        val clock: TaskBudgetClock = MonotonicTaskBudgetClock()
        val first = clock.monotonicNow()
        // Busy-spin a hair so a second reading is strictly later than the first; a frozen ZERO
        // clock (the bug) would make this delta exactly Duration.ZERO forever.
        var spin = 0L
        while (clock.monotonicNow() - first <= Duration.ZERO) {
            spin++
            if (spin > 100_000_000L) break
        }
        val second = clock.monotonicNow()
        assertTrue("a real monotonic clock must advance: $first -> $second", second - first > Duration.ZERO)
    }
}
