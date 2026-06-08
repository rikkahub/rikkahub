package me.rerere.ai.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trigger-math properties for design #193 Stage 1 (P1-P9, excluding P6/P10/M1 which are tested
 * alongside their own units). All pure: [tokenPressure] + [shouldAutoCompact] depend only on Ints
 * and Floats.
 */
class TokenPressureTest {

    private val windows = listOf(8_000, 32_000, 128_000, 200_000, 1_000_000)

    private fun pressureFor(
        contextTokens: Int,
        window: Int,
        thresholdFraction: Float = 0.8f,
        reserveOutput: Int = DEFAULT_MAX_OUTPUT,
        safetyBuffer: Int = SAFETY_BUFFER_TOKENS,
    ) = tokenPressure(contextTokens, window, thresholdFraction, reserveOutput, safetyBuffer)

    private fun fire(
        pressure: TokenPressure,
        enabled: Boolean = true,
        compressible: Boolean = true,
        breaker: Boolean = false,
    ) = shouldAutoCompact(enabled, compressible, breaker, pressure)

    // ---- P9: reserve sanity ----

    @Test
    fun `P9 allowed tokens strictly inside the window and decreasing in each reserve`() {
        runBlocking {
            checkAll(
                Arb.element(windows),
                Arb.int(0..30_000),
                Arb.int(0..30_000),
            ) { window, reserve, buffer ->
                val allowed = computeAllowedTokens(window, reserve, buffer)
                assertTrue("0 < allowed (window=$window)", allowed > 0)
                assertTrue("allowed < window (window=$window)", allowed < window)

                // Monotone non-increasing as reserveOutput grows.
                val allowedBigger = computeAllowedTokens(window, reserve + 1, buffer)
                assertTrue(allowedBigger <= allowed)
                // Monotone non-increasing as safetyBuffer grows.
                val bufferBigger = computeAllowedTokens(window, reserve, buffer + 1)
                assertTrue(bufferBigger <= allowed)
            }
        }
    }

    @Test
    fun `P9 reserve resolution caps and defaults`() {
        assertEquals(DEFAULT_MAX_OUTPUT, resolveReserveOutput(null))
        assertEquals(DEFAULT_MAX_OUTPUT, resolveReserveOutput(0))
        assertEquals(DEFAULT_MAX_OUTPUT, resolveReserveOutput(-5))
        assertEquals(4_000, resolveReserveOutput(4_000))
        assertEquals(MAX_OUTPUT_RESERVE_CAP, resolveReserveOutput(64_000)) // capped
    }

    // ---- P1: boundary ----

    @Test
    fun `P1 hard guard flips exactly at allowedTokens`() {
        val window = 128_000
        val allowed = computeAllowedTokens(window, DEFAULT_MAX_OUTPUT, SAFETY_BUFFER_TOKENS)
        // false AT allowedTokens, true AT allowedTokens + 1 (strict >).
        assertFalse(pressureFor(allowed, window).hardOver)
        assertTrue(pressureFor(allowed + 1, window).hardOver)
    }

    @Test
    fun `P1 soft threshold flips at ceil window times fraction`() {
        val window = 100_000
        val fraction = 0.8f
        val softLimit = kotlin.math.ceil(window * fraction.toDouble()).toInt() // 80_000
        assertFalse(pressureFor(softLimit - 1, window, thresholdFraction = fraction).softOver)
        assertTrue(pressureFor(softLimit, window, thresholdFraction = fraction).softOver)
    }

    // ---- P2: no-op guards ----

    @Test
    fun `P2 disabled or no history or breaker or zero context never fires`() {
        val window = 128_000
        // Force both soft and hard over by using a context above the window.
        val overPressure = pressureFor(window + 5_000, window)
        assertTrue(overPressure.softOver && overPressure.hardOver)

        assertFalse(fire(overPressure, enabled = false))
        assertFalse(fire(overPressure, compressible = false))
        assertFalse(fire(overPressure, breaker = true))
        // Zero context (cold start / missing usage) -> no decision regardless of thresholds.
        assertFalse(fire(pressureFor(0, window, thresholdFraction = MIN_THRESHOLD_FRACTION)))
    }

    // ---- P3: monotonicity ----

    @Test
    fun `P3 increasing context never flips shouldAutoCompact from true back to false`() {
        runBlocking {
            checkAll(
                Arb.element(windows),
                Arb.int(0..2_000_000),
                Arb.int(0..2_000_000),
                Arb.float(0.05f, 1.0f),
            ) { window, c1raw, deltaRaw, frac ->
                val c1 = c1raw % (2 * window)
                val c2 = c1 + (deltaRaw % window) // c2 >= c1
                val p1 = pressureFor(c1, window, thresholdFraction = frac)
                val p2 = pressureFor(c2, window, thresholdFraction = frac)

                // usedFraction non-decreasing.
                assertTrue(p2.usedFraction >= p1.usedFraction)
                // trigger monotone: if it fired at c1, it must still fire at the larger c2.
                if (fire(p1)) assertTrue(fire(p2))
            }
        }
    }

    // ---- P4: model awareness (metamorphic) ----

    @Test
    fun `P4 larger window never makes firing more likely for fixed context and fraction`() {
        runBlocking {
            checkAll(
                Arb.int(0..2_000_000),
                Arb.float(0.05f, 1.0f),
            ) { ctx, frac ->
                // Sort two windows; the larger window's trigger must not be "more on" than the smaller.
                for (i in windows.indices) {
                    for (j in i until windows.size) {
                        val small = windows[i]
                        val large = windows[j]
                        val firedSmall = fire(pressureFor(ctx, small, thresholdFraction = frac))
                        val firedLarge = fire(pressureFor(ctx, large, thresholdFraction = frac))
                        // larger window => firing implies the smaller also fired (monotone in window).
                        if (firedLarge) {
                            assertTrue(
                                "ctx=$ctx frac=$frac small=$small large=$large: larger fired but smaller did not",
                                firedSmall
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- P7: pending-turn monotonicity (via contextTokens growth) ----

    @Test
    fun `P7 for fixed anchor increasing pending estimate never flips true to false`() {
        // contextTokens = anchor + pendingEstimate. Model that as ctx growing by the pending estimate.
        runBlocking {
            checkAll(
                Arb.element(windows),
                Arb.int(0..1_500_000), // anchor (real last-turn total)
                Arb.int(0..500_000),   // pending estimate
            ) { window, anchor, pending ->
                val withoutPending = pressureFor(anchor, window)
                val withPending = pressureFor(anchor + pending, window)
                if (fire(withoutPending)) assertTrue(fire(withPending))
            }
        }
    }

    // ---- P8: hard-guard dominance ----

    @Test
    fun `P8 hard over forces firing regardless of soft fraction`() {
        runBlocking {
            checkAll(
                Arb.element(windows),
                Arb.float(0.05f, 1.0f),
            ) { window, frac ->
                // Context just above allowedTokens => hardOver true, even with a very high soft %.
                val allowed = computeAllowedTokens(window, DEFAULT_MAX_OUTPUT, SAFETY_BUFFER_TOKENS)
                val p = pressureFor(allowed + 1, window, thresholdFraction = frac)
                assertTrue(p.hardOver)
                // Given enabled + compressible + !breaker, hard guard must fire regardless of fraction.
                assertTrue(fire(p))
            }
        }
    }

    @Test
    fun `P8 high soft fraction with sub-allowed context does not fire when soft not crossed`() {
        // Sanity companion to P8: when neither soft nor hard is crossed, the trigger stays off.
        val window = 200_000
        val allowed = computeAllowedTokens(window, DEFAULT_MAX_OUTPUT, SAFETY_BUFFER_TOKENS)
        // Pick a context below both the soft limit (fraction=1.0 -> softLimit=window) and allowed.
        val ctx = (allowed - 1).coerceAtLeast(1)
        val p = pressureFor(ctx, window, thresholdFraction = 1.0f)
        assertFalse(p.softOver)
        assertFalse(p.hardOver)
        assertFalse(fire(p))
    }
}
