package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.Job
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent

internal class VoiceReconnectController(
    private val policy: VoiceReconnectPolicy,
    private val nowMs: () -> Long,
) {
    private val lock = Any()
    private var state: VoiceReconnectState = VoiceReconnectState.Idle

    fun markEligible(sessionId: Long): Boolean = synchronized(lock) {
        state = VoiceReconnectState.Eligible(sessionId = sessionId, retry = retryFrom(state), job = jobFrom(state))
        true
    }

    fun clearEligibility() = synchronized(lock) {
        if (state is VoiceReconnectState.Eligible) {
            state = VoiceReconnectState.Idle
        }
    }

    fun reserveActivation(sessionId: Long): Boolean = synchronized(lock) {
        if (!belongsToActiveSession(sessionId)) return@synchronized false
        state = VoiceReconnectState.Activating(
            sessionId = sessionId,
            retry = retryFrom(state),
            pending = null,
            job = jobFrom(state),
        )
        true
    }

    fun consumePendingActivation(sessionId: Long): AutomaticReconnectPlan? = synchronized(lock) {
        val current = state as? VoiceReconnectState.Activating ?: return@synchronized null
        if (current.sessionId != sessionId) return@synchronized null
        val pending = current.pending
        if (pending != null) {
            state = VoiceReconnectState.Idle
        }
        pending
    }

    fun finishActivation(sessionId: Long): AutomaticReconnectPlan? = synchronized(lock) {
        val current = state as? VoiceReconnectState.Activating ?: return@synchronized null
        if (current.sessionId != sessionId) return@synchronized null
        state = if (current.pending == null) {
            VoiceReconnectState.Eligible(sessionId = sessionId, retry = current.retry, job = current.job)
        } else {
            VoiceReconnectState.Idle
        }
        current.pending
    }

    fun planReconnect(
        failedSessionId: Long,
        event: GeminiLiveEvent,
        reason: VoiceSessionStopReason,
    ): VoiceReconnectDecision = synchronized(lock) {
        if (!reason.autoReconnectEligible) return@synchronized VoiceReconnectDecision.Ignore
        if (!belongsToActiveSession(failedSessionId) && !isAttemptFor(failedSessionId)) {
            return@synchronized VoiceReconnectDecision.Ignore
        }

        val now = nowMs()
        val currentRetry = retryFrom(state) ?: VoiceReconnectRetry(attempts = 0, firstFailureAtMs = now)
        val attempt = currentRetry.attempts + 1
        val elapsedMs = now - currentRetry.firstFailureAtMs
        val delayMs = policy.delayMsForAttempt(attempt = attempt, elapsedMs = elapsedMs)
        if (delayMs == null) {
            state = VoiceReconnectState.Idle
            return@synchronized VoiceReconnectDecision.Exhausted(
                reason = reason,
                attempts = currentRetry.attempts,
                elapsedMs = elapsedMs,
                event = event,
            )
        }

        val plan = AutomaticReconnectPlan(
            event = event,
            reason = reason,
            attempt = attempt,
            delayMs = delayMs,
        )
        val nextRetry = currentRetry.copy(attempts = attempt)
        state = when (val current = state) {
            is VoiceReconnectState.Activating -> current.copy(retry = nextRetry, pending = plan)
            else -> VoiceReconnectState.Planned(retry = nextRetry, plan = plan)
        }
        if (state is VoiceReconnectState.Activating) {
            VoiceReconnectDecision.DeferredForActivation
        } else {
            VoiceReconnectDecision.Schedule(plan)
        }
    }

    fun setScheduled(job: Job) = synchronized(lock) {
        val planned = state as? VoiceReconnectState.Planned
        state = VoiceReconnectState.Scheduled(
            retry = planned?.retry ?: retryFrom(state),
            plan = planned?.plan,
            job = job,
        )
    }

    fun isCurrentJob(job: Job): Boolean = synchronized(lock) {
        (state as? VoiceReconnectState.Scheduled)?.job === job ||
            (state as? VoiceReconnectState.Attempting)?.job === job ||
            (state as? VoiceReconnectState.Eligible)?.job === job ||
            (state as? VoiceReconnectState.Activating)?.job === job
    }

    fun beginAttempt(job: Job, newSessionId: Long): Boolean = synchronized(lock) {
        val scheduled = state as? VoiceReconnectState.Scheduled ?: return@synchronized false
        if (scheduled.job !== job) return@synchronized false
        state = VoiceReconnectState.Attempting(
            retry = scheduled.retry,
            plan = scheduled.plan,
            job = job,
            sessionId = newSessionId,
        )
        true
    }

    fun completeAttempt(job: Job): Int? = synchronized(lock) {
        when (val current = state) {
            is VoiceReconnectState.Attempting -> {
                if (current.job !== job) return@synchronized null
                state = VoiceReconnectState.Idle
                current.retry?.attempts
            }
            is VoiceReconnectState.Eligible -> {
                if (current.job !== job) return@synchronized null
                state = current.copy(retry = null, job = null)
                current.retry?.attempts
            }
            is VoiceReconnectState.Activating -> {
                if (current.job !== job) return@synchronized null
                state = VoiceReconnectState.Eligible(sessionId = current.sessionId, retry = null, job = null)
                current.retry?.attempts
            }
            else -> null
        }
    }

    fun cancel(): VoiceReconnectCancellation = synchronized(lock) {
        val currentState = state
        val job = when (currentState) {
            is VoiceReconnectState.Scheduled -> currentState.job
            is VoiceReconnectState.Attempting -> currentState.job
            is VoiceReconnectState.Eligible -> currentState.job
            is VoiceReconnectState.Activating -> currentState.job
            else -> null
        }
        val hadAutomaticReconnect = currentState.hasAutomaticReconnect()
        job?.cancel()
        state = VoiceReconnectState.Idle
        VoiceReconnectCancellation(job = job, hadAutomaticReconnect = hadAutomaticReconnect)
    }

    fun hasPendingReconnect(): Boolean = synchronized(lock) {
        state.hasPendingReconnect()
    }

    fun retryAttempt(): Int? = synchronized(lock) {
        retryFrom(state)?.attempts
    }

    private fun belongsToActiveSession(sessionId: Long): Boolean =
        when (val current = state) {
            is VoiceReconnectState.Eligible -> current.sessionId == sessionId
            is VoiceReconnectState.Activating -> current.sessionId == sessionId
            else -> false
        }

    private fun isAttemptFor(sessionId: Long): Boolean =
        (state as? VoiceReconnectState.Attempting)?.sessionId == sessionId

    private fun retryFrom(state: VoiceReconnectState): VoiceReconnectRetry? =
        when (state) {
            VoiceReconnectState.Idle -> null
            is VoiceReconnectState.Eligible -> state.retry
            is VoiceReconnectState.Planned -> state.retry
            is VoiceReconnectState.Scheduled -> state.retry
            is VoiceReconnectState.Attempting -> state.retry
            is VoiceReconnectState.Activating -> state.retry
        }

    private fun jobFrom(state: VoiceReconnectState): Job? =
        when (state) {
            is VoiceReconnectState.Scheduled -> state.job
            is VoiceReconnectState.Attempting -> state.job
            is VoiceReconnectState.Eligible -> state.job
            is VoiceReconnectState.Activating -> state.job
            else -> null
        }

    private fun VoiceReconnectState.hasAutomaticReconnect(): Boolean =
        when (this) {
            VoiceReconnectState.Idle -> false
            is VoiceReconnectState.Eligible -> job != null
            is VoiceReconnectState.Planned -> true
            is VoiceReconnectState.Scheduled -> true
            is VoiceReconnectState.Attempting -> true
            is VoiceReconnectState.Activating -> job != null || pending != null
        }

    private fun VoiceReconnectState.hasPendingReconnect(): Boolean =
        when (this) {
            VoiceReconnectState.Idle -> false
            is VoiceReconnectState.Eligible -> job != null
            is VoiceReconnectState.Planned -> false
            is VoiceReconnectState.Scheduled -> true
            is VoiceReconnectState.Attempting -> true
            is VoiceReconnectState.Activating -> job != null || pending != null
        }
}

internal sealed class VoiceReconnectDecision {
    data object Ignore : VoiceReconnectDecision()
    data object DeferredForActivation : VoiceReconnectDecision()
    data class Schedule(val plan: AutomaticReconnectPlan) : VoiceReconnectDecision()
    data class Exhausted(
        val reason: VoiceSessionStopReason,
        val attempts: Int,
        val elapsedMs: Long,
        val event: GeminiLiveEvent,
    ) : VoiceReconnectDecision()
}

internal data class VoiceReconnectCancellation(
    val job: Job?,
    val hadAutomaticReconnect: Boolean,
)

private sealed class VoiceReconnectState {
    data object Idle : VoiceReconnectState()
    data class Eligible(
        val sessionId: Long,
        val retry: VoiceReconnectRetry?,
        val job: Job?,
    ) : VoiceReconnectState()
    data class Planned(val retry: VoiceReconnectRetry?, val plan: AutomaticReconnectPlan?) : VoiceReconnectState()
    data class Scheduled(
        val retry: VoiceReconnectRetry?,
        val plan: AutomaticReconnectPlan?,
        val job: Job,
    ) : VoiceReconnectState()
    data class Attempting(
        val retry: VoiceReconnectRetry?,
        val plan: AutomaticReconnectPlan?,
        val job: Job,
        val sessionId: Long,
    ) : VoiceReconnectState()
    data class Activating(
        val sessionId: Long,
        val retry: VoiceReconnectRetry?,
        val pending: AutomaticReconnectPlan?,
        val job: Job?,
    ) : VoiceReconnectState()
}

private data class VoiceReconnectRetry(
    val attempts: Int,
    val firstFailureAtMs: Long,
)

internal data class AutomaticReconnectPlan(
    val event: GeminiLiveEvent,
    val reason: VoiceSessionStopReason,
    val attempt: Int,
    val delayMs: Long,
)
