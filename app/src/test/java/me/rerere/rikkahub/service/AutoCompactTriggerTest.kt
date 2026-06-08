package me.rerere.rikkahub.service

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Circuit-breaker state-machine properties for design #193 Stage 1 (CB1-CB3), the one real
 * availability fix: without it, a conversation that is irrecoverably over its context window would
 * fire auto-compact every turn and each attempt is a doomed model call. The breaker stops retrying
 * after [MAX_AUTO_COMPACT_FAILURES] consecutive non-cancellation failures.
 *
 * The transition is extracted as a pure function ([nextAutoCompactFailureCount]) and the trip test as
 * [autoCompactBreakerTripped], both consumed by [ChatService.maybeAutoCompact], so these properties
 * exercise the production decision logic on the JVM without the Android/Koin stack. The token-based
 * trigger predicate itself (P1-P10, M1) is tested in the :ai module where it lives.
 */
class AutoCompactTriggerTest {

    // ---- CB1: monotonicity ----

    @Test
    fun `CB1 failure increments and success resets`() {
        assertEquals(1, nextAutoCompactFailureCount(0, AutoCompactOutcome.FAILURE))
        assertEquals(3, nextAutoCompactFailureCount(2, AutoCompactOutcome.FAILURE))
        assertEquals(0, nextAutoCompactFailureCount(5, AutoCompactOutcome.SUCCESS))
        assertEquals(0, nextAutoCompactFailureCount(0, AutoCompactOutcome.SUCCESS))
    }

    @Test
    fun `CB1 counter only moves by the documented rule for every outcome`() {
        runBlocking {
            checkAll(
                Arb.int(0..100),
                Arb.element(AutoCompactOutcome.entries),
            ) { current, outcome ->
                val next = nextAutoCompactFailureCount(current, outcome)
                when (outcome) {
                    AutoCompactOutcome.SUCCESS -> assertEquals(0, next)
                    AutoCompactOutcome.FAILURE -> assertEquals(current + 1, next)
                    AutoCompactOutcome.CANCELLATION -> assertEquals(current, next)
                }
            }
        }
    }

    // ---- CB3: cancellation neutrality ----

    @Test
    fun `CB3 cancellation leaves the counter unchanged`() {
        assertEquals(0, nextAutoCompactFailureCount(0, AutoCompactOutcome.CANCELLATION))
        assertEquals(2, nextAutoCompactFailureCount(2, AutoCompactOutcome.CANCELLATION))
        // A run of cancellations never trips the breaker on its own.
        var failures = 0
        repeat(10) { failures = nextAutoCompactFailureCount(failures, AutoCompactOutcome.CANCELLATION) }
        assertFalse(autoCompactBreakerTripped(failures))
    }

    // ---- CB2: trip dominance / idempotent skip ----

    @Test
    fun `CB2 breaker trips exactly at the max failure count`() {
        assertFalse(autoCompactBreakerTripped(MAX_AUTO_COMPACT_FAILURES - 1))
        assertTrue(autoCompactBreakerTripped(MAX_AUTO_COMPACT_FAILURES))
        assertTrue(autoCompactBreakerTripped(MAX_AUTO_COMPACT_FAILURES + 5))
    }

    @Test
    fun `CB2 consecutive failures trip then a success clears the trip`() {
        var failures = 0
        // Drive up to the trip point with failures.
        repeat(MAX_AUTO_COMPACT_FAILURES) {
            assertFalse("must not be tripped before reaching max", autoCompactBreakerTripped(failures))
            failures = nextAutoCompactFailureCount(failures, AutoCompactOutcome.FAILURE)
        }
        assertTrue("tripped at max", autoCompactBreakerTripped(failures))

        // A single success resets and clears the trip (CB1/CB2).
        failures = nextAutoCompactFailureCount(failures, AutoCompactOutcome.SUCCESS)
        assertFalse(autoCompactBreakerTripped(failures))
    }
}
