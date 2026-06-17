package me.rerere.ai.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class StreamRetryControllerTest {

    /** A fake connection handle that records how many times it was cancelled. */
    private class FakeSource : StreamRetryController.Cancellable {
        val cancelCount = AtomicInteger(0)
        override fun cancel() {
            cancelCount.incrementAndGet()
        }
    }

    private enum class PreFrameTerminalEvent {
        Failure,
        Closed,
    }

    /** Wires the controller to a queue of [FakeSource]s; records what was opened. */
    private class Harness(
        maxRetries: Int,
        replaySafety: StreamRetryController.ReplaySafety = StreamRetryController.ReplaySafety.NonIdempotent,
    ) {
        val opened = mutableListOf<FakeSource>()
        val controller = StreamRetryController(
            maxRetries = maxRetries,
            replaySafety = replaySafety,
        ) {
            FakeSource().also { opened += it }
        }
    }

    private val zeroBackoff: (Int) -> Long = { 0L }

    @Test
    fun `pre-frame transient failure retries up to the bound and then terminates when replay-safe`() = runBlocking {
        val h = Harness(
            maxRetries = 2,
            replaySafety = StreamRetryController.ReplaySafety.IdempotentOrResumable,
        )
        h.controller.start()
        assertEquals(1, h.opened.size)

        // attempt 1
        var outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(outcome is StreamRetryController.Outcome.Retry)
        assertEquals(1, (outcome as StreamRetryController.Outcome.Retry).attempt)
        assertTrue(h.controller.reopen())
        assertEquals(2, h.opened.size)

        // attempt 2
        outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(outcome is StreamRetryController.Outcome.Retry)
        assertEquals(2, (outcome as StreamRetryController.Outcome.Retry).attempt)
        assertTrue(h.controller.reopen())
        assertEquals(3, h.opened.size)

        // budget exhausted (2 retries used): next failure terminates with the error
        val err = IOException("final")
        outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = err)
        assertTrue(outcome is StreamRetryController.Outcome.Terminate)
        assertSame(err, (outcome as StreamRetryController.Outcome.Terminate).error)
        assertEquals(3, h.opened.size)
    }

    @Test
    fun `invariant NonIdempotent pre-frame terminals never retry and return non-null error`() {
        runBlocking {
            checkAll(100, Arb.int(1..4), Arb.int(0..1)) { maxRetries, eventCode ->
                val h = Harness(maxRetries)
                h.controller.start()

                val outcome = when (if (eventCode == 0) PreFrameTerminalEvent.Failure else PreFrameTerminalEvent.Closed) {
                    PreFrameTerminalEvent.Failure -> h.controller.onFailure(
                        transient = true,
                        backoffFor = zeroBackoff,
                        error = null,
                    )
                    PreFrameTerminalEvent.Closed -> h.controller.onClosed(zeroBackoff)
                }

                assertEquals(1, h.opened.size)
                assertTrue(outcome is StreamRetryController.Outcome.Terminate)
                assertNotNull((outcome as StreamRetryController.Outcome.Terminate).error)
                assertTrue(outcome.error is StreamEndedBeforeFirstFrameException)
            }
        }
    }

    @Test
    fun `non-idempotent onFailure without throwable is surfaced as a close-able terminal error`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()

        // Mimics the listener non-retry path when body/raw throwable is absent.
        val outcome = h.controller.onFailure(
            transient = true,
            backoffFor = zeroBackoff,
            error = null,
        )

        assertTrue(
            "non-idempotent pre-frame failures must terminate and never go through retry",
            outcome is StreamRetryController.Outcome.Terminate,
        )
        val terminal = outcome as StreamRetryController.Outcome.Terminate
        val closeError: Throwable? = terminal.error

        assertNotNull("downstream close must receive a non-null terminal error", closeError)
        assertTrue(closeError is StreamEndedBeforeFirstFrameException)
    }

    @Test
    fun `metamorphic replay safety toggles pre-frame retry eligibility`() {
        runBlocking {
            checkAll(100, Arb.int(1..4), Arb.int(0..1)) { maxRetries, eventCode ->
                val nonIdempotent = Harness(maxRetries)
                val idempotent = Harness(
                    maxRetries,
                    StreamRetryController.ReplaySafety.IdempotentOrResumable,
                )
                nonIdempotent.controller.start()
                idempotent.controller.start()

                val nonIdempotentOutcome = when (if (eventCode == 0) PreFrameTerminalEvent.Failure else PreFrameTerminalEvent.Closed) {
                    PreFrameTerminalEvent.Failure -> nonIdempotent.controller.onFailure(
                        transient = true,
                        backoffFor = zeroBackoff,
                        error = IOException(),
                    )
                    PreFrameTerminalEvent.Closed -> nonIdempotent.controller.onClosed(zeroBackoff)
                }
                val idempotentOutcome = when (if (eventCode == 0) PreFrameTerminalEvent.Failure else PreFrameTerminalEvent.Closed) {
                    PreFrameTerminalEvent.Failure -> idempotent.controller.onFailure(
                        transient = true,
                        backoffFor = zeroBackoff,
                        error = IOException(),
                    )
                    PreFrameTerminalEvent.Closed -> idempotent.controller.onClosed(zeroBackoff)
                }

                assertTrue(nonIdempotentOutcome is StreamRetryController.Outcome.Terminate)
                assertNotNull((nonIdempotentOutcome as StreamRetryController.Outcome.Terminate).error)

                assertTrue(idempotentOutcome is StreamRetryController.Outcome.Retry)
                assertEquals(1, (idempotentOutcome as StreamRetryController.Outcome.Retry).attempt)
                assertTrue(idempotent.controller.reopen())
                assertEquals(2, idempotent.opened.size)
                assertEquals(1, nonIdempotent.opened.size)
            }
        }
    }

    @Test
    fun `post-frame failure never retries`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()
        h.controller.onFrame()

        val err = IOException("boom")
        val outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = err)
        assertTrue("a frame disarms the gate; a later transient failure must propagate, not retry",
            outcome is StreamRetryController.Outcome.Terminate)
        assertSame(err, (outcome as StreamRetryController.Outcome.Terminate).error)
    }

    @Test
    fun `non-transient pre-frame failure terminates immediately`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()

        val err = IllegalStateException("not transient")
        val outcome = h.controller.onFailure(transient = false, backoffFor = zeroBackoff, error = err)
        assertTrue(outcome is StreamRetryController.Outcome.Terminate)
        assertSame(err, (outcome as StreamRetryController.Outcome.Terminate).error)
    }

    @Test
    fun `pre-frame clean close fails with terminal error when replay-unsafe`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()

        val outcome = h.controller.onClosed(zeroBackoff)
        assertTrue("clean EOF before the first frame is treated as terminal failure for non-idempotent replay",
            outcome is StreamRetryController.Outcome.Terminate)
        assertNotNull((outcome as StreamRetryController.Outcome.Terminate).error)
        assertTrue(outcome.error is StreamEndedBeforeFirstFrameException)
    }

    @Test
    fun `post-frame clean close terminates normally`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()
        h.controller.onFrame()

        val outcome = h.controller.onClosed(zeroBackoff)
        assertTrue(outcome is StreamRetryController.Outcome.Terminate)
        assertNull((outcome as StreamRetryController.Outcome.Terminate).error)
    }

    @Test
    fun `pre-frame clean close with replay-safe policy retries once with budget`() {
        val h = Harness(
            maxRetries = 1,
            replaySafety = StreamRetryController.ReplaySafety.IdempotentOrResumable,
        )
        h.controller.start()

        var outcome = h.controller.onClosed(zeroBackoff)
        assertTrue(outcome is StreamRetryController.Outcome.Retry)
        assertEquals(1, (outcome as StreamRetryController.Outcome.Retry).attempt)
        assertTrue(h.controller.reopen())

        outcome = h.controller.onClosed(zeroBackoff)
        assertTrue("budget exhausted: non-idempotent-like terminal once the replay-safe budget is gone",
            outcome is StreamRetryController.Outcome.Terminate)
        assertNotNull((outcome as StreamRetryController.Outcome.Terminate).error)
    }

    @Test
    fun `close during backoff does not reopen and never leaks a source`() {
        val h = Harness(
            maxRetries = 2,
            replaySafety = StreamRetryController.ReplaySafety.IdempotentOrResumable,
        )
        h.controller.start()
        val first = h.opened[0]

        // transient failure schedules a retry...
        val outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(outcome is StreamRetryController.Outcome.Retry)

        // ...but the flow is torn down DURING the backoff window, before reopen() runs.
        h.controller.close()

        // reopen must observe the closed state and open NOTHING — the leak race.
        assertFalse(h.controller.reopen())
        assertEquals("no new source may be created after close()", 1, h.opened.size)
        // The retry path already cancelled the dead source once; close() must not double-cancel a
        // handle the controller has thrown away.
        assertEquals("the dead source is cancelled exactly once across retry+close", 1, first.cancelCount.get())
    }

    @Test
    fun `terminal failure does not cancel the live source so the listener can read the error body`() {
        // REGRESSION (Finding 1): okhttp-sse delivers onFailure for an HTTP error with the body
        // still UNREAD; the body is closed only after the listener returns. If the controller
        // cancels the Call inside onFailure(), the listener's body read throws, the exception is
        // swallowed, and a real 429/502/504 becomes a silent empty success. The terminal path must
        // therefore NOT cancel — the live handle stays for close() to release on teardown.
        val h = Harness(maxRetries = 2)
        h.controller.start()
        val live = h.opened[0]

        // post-frame transient failure -> Terminate (no retry).
        h.controller.onFrame()
        val outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(outcome is StreamRetryController.Outcome.Terminate)
        assertEquals(
            "terminal onFailure must not cancel the source before the body is read",
            0, live.cancelCount.get()
        )

        // teardown still releases it exactly once.
        h.controller.close()
        assertEquals("close() releases the live source on teardown", 1, live.cancelCount.get())
    }

    @Test
    fun `retry path cancels the dead source exactly once without a foreign handle`() {
        // REGRESSION (Finding 2): the controller owns `current`; the retry path must cancel and
        // clear it (so a racing close() can't double-cancel a handle being thrown away) WITHOUT the
        // caller feeding back a foreign Cancellable. The previous identity guard (current === failed)
        // was dead because the listener wrapped the EventSource in a distinct Cancellable
        // instance.
        val h = Harness(
            maxRetries = 2,
            replaySafety = StreamRetryController.ReplaySafety.IdempotentOrResumable,
        )
        h.controller.start()
        val first = h.opened[0]

        val outcome = h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(outcome is StreamRetryController.Outcome.Retry)
        assertEquals("retry cancels the dead source", 1, first.cancelCount.get())

        assertTrue(h.controller.reopen())
        val second = h.opened[1]

        // close() cancels only the new live source, never re-touches the discarded one.
        h.controller.close()
        assertEquals("the discarded source is not cancelled again by close()", 1, first.cancelCount.get())
        assertEquals("close() cancels the current (post-retry) source", 1, second.cancelCount.get())
    }

    @Test
    fun `close cancels the current source`() {
        val h = Harness(maxRetries = 2)
        h.controller.start()

        h.controller.close()
        assertEquals(1, h.opened[0].cancelCount.get())
    }

    @Test
    fun `reopen after a successful retry makes the new source the one close cancels`() {
        val h = Harness(
            maxRetries = 2,
            replaySafety = StreamRetryController.ReplaySafety.IdempotentOrResumable,
        )
        h.controller.start()

        h.controller.onFailure(transient = true, backoffFor = zeroBackoff, error = IOException())
        assertTrue(h.controller.reopen())
        val second = h.opened[1]

        h.controller.close()
        // awaitClose must cancel whatever source is current, including one created mid-retry.
        assertEquals(1, second.cancelCount.get())
    }

    @Test
    fun `start after close opens nothing`() {
        val h = Harness(maxRetries = 2)
        h.controller.close()
        h.controller.start()
        assertEquals(0, h.opened.size)
    }
}
