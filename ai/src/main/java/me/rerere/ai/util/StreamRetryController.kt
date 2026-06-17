package me.rerere.ai.util

import java.io.IOException

class StreamEndedBeforeFirstFrameException(
    override val message: String,
) : IOException(message)

/**
 * Thread-safe orchestrator for pre-first-frame SSE retry. Owns ALL mutable retry state
 * (first-frame gate, attempt counter, the live connection handle, the closed flag) behind
 * a single lock so the okhttp callback threads, the retry coroutine, and the flow's
 * awaitClose cannot race on it.
 *
 * WHY a dedicated controller instead of captured vars in the listener:
 *  - Visibility/ordering: the listener fires on okhttp dispatcher threads, the retry runs on
 *    a coroutine, and awaitClose runs on the flow's cancellation path — three different threads
 *    with no happens-before edge between them. Plain vars are not safely published across them.
 *  - Leak race: a naive `delay -> isActive -> newEventSource -> currentSource = it` lets the
 *    flow close DURING the backoff window; awaitClose cancels the OLD source, then the retry
 *    assigns a NEW source that nobody cancels. Serializing open+assign and cancel+close under
 *    one lock closes that window: an open requested after [close] is cancelled immediately and
 *    never stored.
 *
 * The class is pure (no okhttp, no coroutines) so the orchestration is unit-testable on the JVM.
 * The caller injects:
 *  - [open]: opens a new connection and returns a [Cancellable] handle for it.
 *  - [maxRetries]: the retry bound ([STREAM_MAX_RETRIES]).
 */
class StreamRetryController(
    private val maxRetries: Int,
    private val replaySafety: ReplaySafety = ReplaySafety.NonIdempotent,
    private val open: () -> Cancellable,
) {
    enum class ReplaySafety {
        NonIdempotent,
        IdempotentOrResumable
    }
    fun interface Cancellable {
        fun cancel()
    }

    /** What the controller decided a failure/EOF should trigger. */
    sealed interface Outcome {
        /** Caller must schedule a retry after [backoffMillis], then call [reopen]. */
        data class Retry(val attempt: Int, val backoffMillis: Long) : Outcome

        /** Terminal: caller must close the flow (optionally with [error]). */
        data class Terminate(val error: Throwable?) : Outcome
    }

    private val lock = Any()
    private var current: Cancellable? = null
    private var firstFrameReceived = false
    private var attempt = 0
    private var closed = false

    private fun preFrameTerminalError(rawError: Throwable?): Throwable = rawError
        ?: StreamEndedBeforeFirstFrameException("stream ended before first SSE frame")

    /** Open the initial connection. No-op (and cancels nothing) if already closed. */
    fun start() {
        openLocked()
    }

    /** A stream frame arrived; disarm the retry gate permanently. */
    fun onFrame() {
        synchronized(lock) { firstFrameReceived = true }
    }

    /**
     * A connection failure occurred. Returns the [Outcome] under the lock so the decision and the
     * attempt-increment are atomic. [transient] answers "is this throwable/status transient?" and
     * [backoffFor] computes the delay; both are computed by the caller from pure, injected policy so
     * production wires in the real policy while tests wire in deterministic stubs.
     *
     * Resource discipline differs by outcome, and this is load-bearing:
     *  - Retry: the dead connection is OURS to discard, so cancel [current] and clear it before the
     *    backoff so a racing [close] can't double-cancel a handle we're throwing away.
     *  - Terminate: do NOT cancel here. okhttp-sse delivers onFailure for an HTTP error with the
     *    error body still UNREAD (RealEventSource.processResponse closes the body only after the
     *    listener returns). Cancelling the Call now makes the listener's body read throw, the
     *    exception gets swallowed, and a real 429/502/504 is reported as a silent empty success.
     *    The live handle stays in [current]; the flow's [close] cancels it on teardown.
     */
    fun onFailure(
        transient: Boolean,
        backoffFor: (attempt: Int) -> Long,
        error: Throwable?,
    ): Outcome = synchronized(lock) {
        if (
            closed ||
            firstFrameReceived ||
            !transient ||
            attempt >= maxRetries ||
            replaySafety != ReplaySafety.IdempotentOrResumable
        ) {
            return Outcome.Terminate(preFrameTerminalError(error))
        }
        current?.cancel()
        current = null
        attempt += 1
        Outcome.Retry(attempt, backoffFor(attempt))
    }

    /**
     * The connection closed cleanly (okhttp onClosed). Before the first frame this is still a
     * pre-first-frame death and is retried like a transient failure if budget remains; otherwise it
     * terminates with the non-idempotent terminal error. The retry path cancels [current] (it's a dead
     * handle we're replacing); the terminal path leaves it for [close] — a clean EOF carries no
     * error body to preserve, but keeping cancel ownership in one place stays uniform.
     */
    fun onClosed(backoffFor: (attempt: Int) -> Long): Outcome =
        synchronized(lock) {
            if (firstFrameReceived) {
                return Outcome.Terminate(null)
            }
            if (
                closed ||
                attempt >= maxRetries ||
                replaySafety != ReplaySafety.IdempotentOrResumable
            ) {
                return Outcome.Terminate(preFrameTerminalError(null))
            }
            current?.cancel()
            current = null
            attempt += 1
            Outcome.Retry(attempt, backoffFor(attempt))
        }

    /**
     * Open the next connection after a backoff. Returns false (opening nothing) if the flow was
     * closed during the backoff window — the leak-race fix. On success the new handle is stored
     * so a subsequent [close] cancels it.
     */
    fun reopen(): Boolean = openLocked()

    /**
     * The flow is being torn down. Mark closed and cancel the live connection. Any [reopen] that
     * races in after this point sees closed==true and cancels its fresh handle instead of storing it.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            current?.cancel()
            current = null
        }
    }

    private fun openLocked(): Boolean = synchronized(lock) {
        if (closed) return false
        val source = open()
        // Re-check under the same lock: open() cannot have flipped `closed` (it's just a factory
        // call), but keeping the assignment inside the critical section is what guarantees a
        // concurrent close() either cancels THIS source or is ordered-after this store.
        current = source
        true
    }
}
