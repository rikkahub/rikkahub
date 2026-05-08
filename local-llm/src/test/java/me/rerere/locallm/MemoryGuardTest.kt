package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGuardTest {

    @Test fun `model fits comfortably under 70 percent of available memory returns Ok`() {
        val decision = MemoryGuard.decide(modelFileBytes = 1_000_000_000L, availMemBytes = 4_000_000_000L)
        assertEquals(MemoryGuard.Decision.Ok, decision)
    }

    @Test fun `model exactly at 70 percent budget returns Ok`() {
        val decision = MemoryGuard.decide(modelFileBytes = 700_000_000L, availMemBytes = 1_000_000_000L)
        assertEquals(MemoryGuard.Decision.Ok, decision)
    }

    @Test fun `model just over 70 percent budget returns TooLarge with the right numbers`() {
        val decision = MemoryGuard.decide(modelFileBytes = 1_900_000_000L, availMemBytes = 2_400_000_000L)
        assertTrue(decision is MemoryGuard.Decision.TooLarge)
        decision as MemoryGuard.Decision.TooLarge
        assertEquals(1_900_000_000L, decision.modelFileBytes)
        assertEquals(2_400_000_000L, decision.availMemBytes)
    }

    @Test fun `zero available memory returns TooLarge for any non-zero model`() {
        val decision = MemoryGuard.decide(modelFileBytes = 1L, availMemBytes = 0L)
        assertTrue(decision is MemoryGuard.Decision.TooLarge)
    }

    @Test fun `zero-byte model is always Ok`() {
        val decision = MemoryGuard.decide(modelFileBytes = 0L, availMemBytes = 100L)
        assertEquals(MemoryGuard.Decision.Ok, decision)
    }
}
