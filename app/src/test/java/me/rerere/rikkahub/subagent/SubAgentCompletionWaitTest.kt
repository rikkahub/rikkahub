package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for the sub-agent TIMED_OUT bug.
 *
 * The old engine code:
 *   val finished = withTimeoutOrNull(N) { flow.first { it == null } }
 *   if (finished == null) markTerminal(TIMED_OUT)
 *
 * `.first { it == null }` on a Flow<Job?> returns the matched value — which is null on
 * SUCCESSFUL completion (the Job? went to null). So `finished == null` was true on BOTH
 * timeout AND success, and every sub-agent looked timed out despite actually finishing.
 *
 * The fix is a Unit sentinel: `withTimeoutOrNull(N) { ...first { it == null }; Unit }`.
 * On success the lambda returns Unit (non-null). On timeout it returns null. The two
 * outcomes are now distinguishable. These tests pin that behaviour.
 */
class SubAgentCompletionWaitTest {

    @Test fun `Unit sentinel distinguishes successful completion from timeout`() = runBlocking {
        val flow = MutableStateFlow<Job?>(Job())  // initially "running"

        coroutineScope {
            // Spawn a "completion" coroutine that flips the flow to null after a small delay.
            launch {
                kotlinx.coroutines.delay(50)
                flow.value = null
            }
            val outcome: Unit? = withTimeoutOrNull(2_000L) {
                flow.first { it == null }
                Unit
            }
            assertNotNull("successful completion must return Unit, not null", outcome)
            assertEquals(Unit, outcome)
        }
    }

    @Test fun `timeout returns null with sentinel`() = runBlocking {
        val flow = MutableStateFlow<Job?>(Job())  // never goes to null

        val outcome: Unit? = withTimeoutOrNull(50L) {
            flow.first { it == null }
            Unit
        }
        assertNull("timeout must return null", outcome)
    }

    /**
     * The buggy form, kept as a regression witness — proves WHY the old code was broken.
     * If this test ever flips to "passes by accident" we know someone has changed Flow's
     * .first semantics in a way we should know about.
     */
    @Test fun `OLD buggy form returns null on both success AND timeout — explains the bug`() = runBlocking {
        // Success path: flow goes to null → first(predicate) returns null
        val flowSuccess = MutableStateFlow<Job?>(null)  // already complete
        val finishedSuccess = withTimeoutOrNull(500L) {
            flowSuccess.first { it == null }
        }
        assertNull("success path: bug returns null (matched value)", finishedSuccess)

        // Timeout path: flow stays non-null → first never returns
        val flowStuck = MutableStateFlow<Job?>(Job())
        val finishedTimeout = withTimeoutOrNull(50L) {
            flowStuck.first { it == null }
        }
        assertNull("timeout path: returns null", finishedTimeout)

        // Both null → indistinguishable. That's the bug.
    }
}
