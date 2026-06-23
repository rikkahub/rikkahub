package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/goal` autonomous loop bound (#364). [shouldContinueGoal] is the ONLY thing standing between a
 * bounded autonomous loop and an unbounded token-spend runaway, so its boundary is pinned directly:
 * `maxIterations <= 0` is the explicit user opt-in to UNLIMITED; any positive cap stops the loop the
 * moment the iteration count reaches it.
 */
class GoalLoopBoundTest {

    @Test
    fun `max of zero or negative means unlimited`() {
        assertTrue(shouldContinueGoal(iteration = 0, maxIterations = 0))
        assertTrue(shouldContinueGoal(iteration = 10_000, maxIterations = 0))
        assertTrue(shouldContinueGoal(iteration = 10_000, maxIterations = -1))
    }

    @Test
    fun `a positive cap stops exactly at the cap`() {
        // cap = 3 → continuations at iteration 0, 1, 2; iteration 3 and beyond stop.
        assertTrue(shouldContinueGoal(iteration = 0, maxIterations = 3))
        assertTrue(shouldContinueGoal(iteration = 2, maxIterations = 3))
        assertFalse(shouldContinueGoal(iteration = 3, maxIterations = 3))
        assertFalse(shouldContinueGoal(iteration = 4, maxIterations = 3))
    }

    @Test
    fun `a cap of one allows exactly one continuation`() {
        assertTrue(shouldContinueGoal(iteration = 0, maxIterations = 1))
        assertFalse(shouldContinueGoal(iteration = 1, maxIterations = 1))
    }
}
