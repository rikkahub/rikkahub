package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class VoiceCaptureStartController(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val canStart: (sessionId: Long) -> Boolean,
    private val canHandleFailure: (sessionId: Long) -> Boolean,
    private val startCapture: suspend (sessionId: Long) -> Unit,
    private val onFailure: (sessionId: Long, failure: Throwable) -> Unit,
) {
    private var state: CaptureStartState = CaptureStartState.Idle

    fun launch(sessionId: Long) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                startCapture(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (claimFailure(job, sessionId)) {
                    onFailure(sessionId, failure)
                }
            }
        }
        var accepted = false
        val previous = synchronized(lock) {
            if (state is CaptureStartState.HandlingFailure || !canStart(sessionId)) {
                null
            } else {
                accepted = true
                (state as? CaptureStartState.Starting)?.job.also {
                    state = CaptureStartState.Starting(job = job, sessionId = sessionId)
                }
            }
        }
        if (!accepted) {
            job.cancel()
            return
        }
        previous?.cancel()
        job.invokeOnCompletion {
            synchronized(lock) {
                if (state.owns(job)) state = CaptureStartState.Idle
            }
        }
        job.start()
    }

    fun cancel() {
        val job = synchronized(lock) {
            state.ownedJob()?.also { state = CaptureStartState.Idle }
        }
        job?.cancel()
    }

    internal fun hasOwnedJob(): Boolean = synchronized(lock) {
        state !is CaptureStartState.Idle
    }

    private fun claimFailure(job: Job, sessionId: Long): Boolean = synchronized(lock) {
        val current = state
        if (
            current !is CaptureStartState.Starting ||
            current.job !== job ||
            current.sessionId != sessionId ||
            !canHandleFailure(sessionId)
        ) {
            false
        } else {
            state = CaptureStartState.HandlingFailure(job = job, sessionId = sessionId)
            true
        }
    }
}

private sealed interface CaptureStartState {
    data object Idle : CaptureStartState

    data class Starting(
        val job: Job,
        val sessionId: Long,
    ) : CaptureStartState

    data class HandlingFailure(
        val job: Job,
        val sessionId: Long,
    ) : CaptureStartState
}

private fun CaptureStartState.ownedJob(): Job? = when (this) {
    CaptureStartState.Idle -> null
    is CaptureStartState.Starting -> job
    is CaptureStartState.HandlingFailure -> job
}

private fun CaptureStartState.owns(job: Job): Boolean = ownedJob() === job
