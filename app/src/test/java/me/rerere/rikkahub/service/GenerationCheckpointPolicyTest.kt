package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCheckpointPolicyTest {
    @Test
    fun `first update is checkpointed and rapid streaming updates are throttled`() {
        val policy = GenerationCheckpointPolicy(intervalMillis = 1_000L)

        assertTrue(policy.shouldCheckpoint(nowMillis = 100L))
        assertFalse(policy.shouldCheckpoint(nowMillis = 1_099L))
        assertTrue(policy.shouldCheckpoint(nowMillis = 1_100L))
    }

    @Test
    fun `terminal update always checkpoints and resets the interval`() {
        val policy = GenerationCheckpointPolicy(intervalMillis = 1_000L)

        assertTrue(policy.shouldCheckpoint(nowMillis = 100L))
        assertTrue(policy.shouldCheckpoint(nowMillis = 500L, force = true))
        assertFalse(policy.shouldCheckpoint(nowMillis = 1_499L))
        assertTrue(policy.shouldCheckpoint(nowMillis = 1_500L))
    }
}
