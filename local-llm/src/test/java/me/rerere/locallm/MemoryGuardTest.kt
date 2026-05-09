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
        // requiredFreeBytes ≈ 1.9 GB / 0.7 = ~2.71 GB. Must always exceed avail in the
        // TooLarge branch so the UI message reads correctly.
        assertTrue(
            "requiredFreeBytes (${decision.requiredFreeBytes}) must exceed availMemBytes (${decision.availMemBytes})",
            decision.requiredFreeBytes > decision.availMemBytes,
        )
        assertTrue(
            "requiredFreeBytes (${decision.requiredFreeBytes}) must exceed modelFileBytes (${decision.modelFileBytes})",
            decision.requiredFreeBytes > decision.modelFileBytes,
        )
    }

    @Test fun `zero available memory returns TooLarge for any non-zero model`() {
        val decision = MemoryGuard.decide(modelFileBytes = 1L, availMemBytes = 0L)
        assertTrue(decision is MemoryGuard.Decision.TooLarge)
    }

    @Test fun `zero-byte model is always Ok`() {
        val decision = MemoryGuard.decide(modelFileBytes = 0L, availMemBytes = 100L)
        assertEquals(MemoryGuard.Decision.Ok, decision)
    }

    @Test fun `large model on a device with barely enough memory just fits at budget boundary`() {
        // Exact boundary: 3.5 GB model, 5 GB avail → budget = 3.5 GB → fits.
        val avail = 5_000_000_000L
        val model = (avail * 0.7).toLong()  // == 3_500_000_000
        val decision = MemoryGuard.decide(modelFileBytes = model, availMemBytes = avail)
        assertEquals(MemoryGuard.Decision.Ok, decision)
    }

    @Test fun `TooLarge carries the exact model and avail sizes`() {
        val modelBytes = 4_000_000_000L
        val availBytes = 3_000_000_000L
        val decision = MemoryGuard.decide(modelFileBytes = modelBytes, availMemBytes = availBytes)
        assertTrue(decision is MemoryGuard.Decision.TooLarge)
        decision as MemoryGuard.Decision.TooLarge
        assertEquals(modelBytes, decision.modelFileBytes)
        assertEquals(availBytes, decision.availMemBytes)
    }

    @Test fun `requiredFreeBytes lines up with the 1800 MB on a 2105 MB device repro`() {
        // The exact case that surfaced the inverted UI message on 2026-05-09. The user
        // saw "needs 1800 MB but only 2105 MB available" — looked contradictory because
        // the 30% headroom budget was hidden. Required-free should be ~2571 MB so the
        // refusal "need 2571 MB but only 2105 MB" reads consistently.
        val decision = MemoryGuard.decide(
            modelFileBytes = 1_800_000_000L,
            availMemBytes = 2_105_000_000L,
        )
        assertTrue(decision is MemoryGuard.Decision.TooLarge)
        decision as MemoryGuard.Decision.TooLarge
        // 1.8 GB / 0.7 ≈ 2.571 GB; allow ±5 MB tolerance for the rounding.
        val expected = 2_571_000_000L
        val tolerance = 5_000_000L
        assertTrue(
            "requiredFreeBytes ${decision.requiredFreeBytes} should be near $expected (±$tolerance)",
            kotlin.math.abs(decision.requiredFreeBytes - expected) <= tolerance,
        )
    }
}
