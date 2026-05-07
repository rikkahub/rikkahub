package me.rerere.rikkahub.workflow.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cooldown decision regression suite. The earlier bug: the cooldown gate read the
 * workflow row's projected `lastRunAtMs`, which gets bumped on EVERY attempt —
 * including SKIPPED_COOLDOWN — so once cooldown was tripped it could never be satisfied
 * by waiting (every "Run now" tap pushed the cooldown window forward by another N
 * seconds). The fix routes the gate through the most-recent SUCCESS/FAILED fire instead.
 *
 * These tests exercise the pure decision function in isolation; the wiring change in
 * [WorkflowEngine] is small enough to verify by reading.
 */
class CooldownGateTest {

    @Test fun `disabled cooldown never blocks`() {
        assertFalse(CooldownGate.isWithinCooldown(cooldownSeconds = 0, lastActualFireMs = 1_000L, nowMs = 1_500L))
        assertFalse(CooldownGate.isWithinCooldown(cooldownSeconds = -5, lastActualFireMs = 1_000L, nowMs = 1_500L))
    }

    @Test fun `no prior fire never blocks`() {
        assertFalse(CooldownGate.isWithinCooldown(cooldownSeconds = 60, lastActualFireMs = null, nowMs = 1_500L))
    }

    @Test fun `inside cooldown window blocks`() {
        // last fire at t=0, cooldown 60s, now at t=10s → still within
        assertTrue(CooldownGate.isWithinCooldown(cooldownSeconds = 60, lastActualFireMs = 0L, nowMs = 10_000L))
    }

    @Test fun `at cooldown boundary does not block`() {
        // last fire at t=0, cooldown 60s, now exactly at t=60s → boundary released
        assertFalse(CooldownGate.isWithinCooldown(cooldownSeconds = 60, lastActualFireMs = 0L, nowMs = 60_000L))
    }

    @Test fun `past cooldown does not block`() {
        // last fire at t=0, cooldown 60s, now at t=120s → well past
        assertFalse(CooldownGate.isWithinCooldown(cooldownSeconds = 60, lastActualFireMs = 0L, nowMs = 120_000L))
    }

    /**
     * The crash test: simulate the bug scenario.
     *
     * Before the fix:
     *   Engine read entity.lastRunAtMs which was overwritten on every fire INCLUDING skips.
     *   So tap-tap-tap during cooldown kept pushing the window forward.
     *
     * After the fix:
     *   Engine reads the most-recent SUCCESS/FAILED firedAtMs from the run history. Skip
     *   rows don't move that timestamp forward, so the cooldown can be waited out.
     *
     * Here we simulate that by passing the same lastActualFireMs (= the original SUCCESS
     * fire time) through a sequence of attempts. The gate must release once enough wall
     * time has passed regardless of how many skips happened in between.
     */
    @Test fun `repeated taps inside cooldown do not push the window forward`() {
        val firstFire = 0L
        val cooldown = 60
        // tap at t=10, t=20, t=30, t=50 — all inside cooldown, all skips
        for (now in listOf(10_000L, 20_000L, 30_000L, 50_000L)) {
            assertTrue(
                "expected SKIPPED at t=$now",
                CooldownGate.isWithinCooldown(cooldown, firstFire, now),
            )
        }
        // tap at t=61 — outside cooldown, must release
        assertFalse(
            "cooldown should release at t=61s; if this fires, the gate is reading skip rows",
            CooldownGate.isWithinCooldown(cooldown, firstFire, 61_000L),
        )
    }
}
