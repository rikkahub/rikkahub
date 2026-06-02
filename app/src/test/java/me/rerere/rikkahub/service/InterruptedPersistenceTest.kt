package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the cancel-path persistence bug in [ChatService.handleMessageComplete].
 *
 * stopGeneration() cancels the generation job ([ChatService] ~1265 `job.cancel()`); the flow's
 * `.onCompletion` then runs the finalization that must durably persist the cleaned, valid state.
 * That persistence calls the suspend `saveConversation`, whose FIRST statement is
 * `existsConversationById` (ConversationRepository:201) — a Room suspend DAO call, i.e. a
 * cancellable suspension point. When the owning coroutine is already cancelled, an unguarded
 * suspend persist aborts at that point with CancellationException BEFORE writing, so the
 * finalized state is never stored (neither DB nor the in-memory update that follows it).
 *
 * The fix wraps the persistence in `withContext(NonCancellable)`. This test reproduces the exact
 * mechanism (cancelled coroutine -> onCompletion -> suspend body with a cancellable suspension
 * point before the write) and proves the wrapper is load-bearing: the unguarded control case
 * drops the write, the guarded case persists it.
 */
class InterruptedPersistenceTest {

    /**
     * Mirrors saveConversation: the first suspension point ([yield], standing in for the Room
     * `existsConversationById` DAO call) is cancellable; the actual write happens only after it.
     */
    private suspend fun persist(onWrite: () -> Unit) {
        yield() // cancellable suspension point — analog of existsConversationById()
        onWrite()
    }

    /** Run a flow, then cancel the collecting coroutine to enter `.onCompletion` on the cancel path. */
    private fun runCancelledThenComplete(onCompletionBody: suspend () -> Unit): Unit = runBlocking {
        coroutineScope {
            val job = launch(Dispatchers.Unconfined) {
                flow<Int> {
                    // Never completes on its own; cancellation is what drives onCompletion.
                    kotlinx.coroutines.delay(Long.MAX_VALUE)
                }.onCompletion {
                    onCompletionBody()
                }.collect()
            }
            yield() // let the collector reach the suspended flow
            job.cancel()
            job.join()
        }
    }

    @Test
    fun `unguarded persistence on the cancel path drops the write`() {
        var written = false
        runCancelledThenComplete {
            // No NonCancellable: the suspension point inside persist() throws CancellationException
            // before onWrite runs.
            runCatching { persist { written = true } }
        }
        assertFalse(
            "without NonCancellable the cancelled coroutine aborts persistence at its first " +
                "cancellable suspension point, before the durable write — this is the bug",
            written
        )
    }

    @Test
    fun `NonCancellable persistence on the cancel path completes the write`() {
        var written = false
        runCancelledThenComplete {
            // The production fix: NonCancellable lets the suspend persistence run to completion
            // even though the owning coroutine was cancelled.
            withContext(NonCancellable) {
                persist { written = true }
            }
        }
        assertTrue(
            "with NonCancellable the finalized state is durably persisted on the cancel path",
            written
        )
    }
}
