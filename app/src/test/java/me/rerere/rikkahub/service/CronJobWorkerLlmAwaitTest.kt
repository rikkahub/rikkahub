package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the await-pattern bug that misclassified every successful LLM-mode cron run
 * as `timed_out` for 15 minutes.
 *
 * The naive form
 *
 *     val finished = withTimeoutOrNull(cap) { flow.first { it == null } }
 *     if (finished == null) /* timeout */
 *
 * is broken: `.first { it == null }` returns the matched value (null), so the block's
 * return value is null on SUCCESS — and `withTimeoutOrNull` also returns null on
 * TIMEOUT. The two paths are indistinguishable without a sentinel.
 *
 * [awaitGenerationTerminal] uses a `Unit` sentinel so success returns `Unit` and timeout
 * returns `null`. Reverting the helper to the naive form fails these tests.
 */
class CronJobWorkerLlmAwaitTest {

    @Test
    fun `success when flow value is already null at subscribe time`() = runBlocking {
        val state = MutableStateFlow<Job?>(null)
        assertTrue(
            "an already-terminal generation flow must report success",
            awaitGenerationTerminal(state, timeoutMs = 1_000L),
        )
    }

    @Test
    fun `success when flow transitions from running job to null before cap`() = runBlocking {
        val state = MutableStateFlow<Job?>(Job())
        val deferred = async { awaitGenerationTerminal(state, timeoutMs = 5_000L) }
        // Yield long enough for the deferred to subscribe before the flow flips.
        delay(50L)
        state.value = null
        assertTrue(
            "a generation that finishes inside the cap must report success",
            deferred.await(),
        )
    }

    @Test
    fun `timeout when flow never goes null`() = runBlocking {
        val state = MutableStateFlow<Job?>(Job())
        assertFalse(
            "a generation that never terminates must report timeout",
            awaitGenerationTerminal(state, timeoutMs = 100L),
        )
    }

    @Test
    fun `naive pattern returns null on BOTH success and timeout — documents the bug`() = runBlocking {
        // Success path: predicate matches the null value → block returns null.
        val successFlow = MutableStateFlow<Job?>(null)
        val finishedOnSuccess = withTimeoutOrNull(1_000L) { successFlow.first { it == null } }
        assertNull("naive form returns null on success — that's the bug", finishedOnSuccess)

        // Timeout path: predicate never matches → withTimeoutOrNull returns null.
        val timeoutFlow = MutableStateFlow<Job?>(Job())
        val finishedOnTimeout = withTimeoutOrNull(50L) { timeoutFlow.first { it == null } }
        assertNull("naive form returns null on timeout — same value, different meaning", finishedOnTimeout)
        // From the return value alone the two outcomes cannot be distinguished.
    }
}
