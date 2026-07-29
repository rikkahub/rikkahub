package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class VoiceAgentCleanupMode {
    Replacement,
    GracefulEnd,
    Immediate,
}

internal sealed interface VoiceAgentCleanupResult {
    data object Completed : VoiceAgentCleanupResult
    data class Failed(val error: Throwable) : VoiceAgentCleanupResult
}

internal interface VoiceAgentCleanupOperation {
    val token: Any
    suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult
}

internal fun voiceAgentRouteCleanupOperation(
    routeLease: VoiceAgentRouteLease,
): VoiceAgentCleanupOperation = RouteCleanupOperation(routeLease)

internal fun voiceAgentSessionCleanupOperation(
    delegate: ManagedVoiceCallSession,
    routeLease: VoiceAgentRouteLease,
    endDrainTimeoutMillis: Long,
): VoiceAgentCleanupOperation {
    require(endDrainTimeoutMillis > 0) { "endDrainTimeoutMillis must be positive" }
    return SessionCleanupOperation(delegate, routeLease, endDrainTimeoutMillis)
}

internal fun activeVoiceAgentCallCleanupOperation(
    collector: Job,
    callJob: Job,
    sessionCleanup: VoiceAgentCleanupOperation,
): VoiceAgentCleanupOperation = ActiveCallCleanupOperation(collector, callJob, sessionCleanup)

private sealed interface CleanupAttemptState {
    data object Ready : CleanupAttemptState
    data class Running(val completion: CompletableDeferred<CleanupAttemptOutcome>) : CleanupAttemptState
    data object Completed : CleanupAttemptState
}

private sealed interface CleanupAttemptDecision {
    data object Completed : CleanupAttemptDecision
    data class Execute(val completion: CompletableDeferred<CleanupAttemptOutcome>) : CleanupAttemptDecision
    data class Join(val completion: CompletableDeferred<CleanupAttemptOutcome>) : CleanupAttemptDecision
}

internal sealed interface CleanupAttemptOutcome {
    data class Returned(val result: VoiceAgentCleanupResult) : CleanupAttemptOutcome
    data class Cancelled(val error: CancellationException) : CleanupAttemptOutcome
}

private enum class CleanupStageProgress {
    Pending,
    Completed,
}

internal abstract class JoinedCleanupOperation : VoiceAgentCleanupOperation {
    final override val token: Any = Any()

    private val lock = Any()
    private var state: CleanupAttemptState = CleanupAttemptState.Ready

    final override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        val decision = synchronized(lock) {
            when (val current = state) {
                CleanupAttemptState.Completed -> CleanupAttemptDecision.Completed
                CleanupAttemptState.Ready -> {
                    val completion = CompletableDeferred<CleanupAttemptOutcome>()
                    state = CleanupAttemptState.Running(completion)
                    CleanupAttemptDecision.Execute(completion)
                }
                is CleanupAttemptState.Running -> CleanupAttemptDecision.Join(current.completion)
            }
        }
        return when (decision) {
            CleanupAttemptDecision.Completed -> VoiceAgentCleanupResult.Completed
            is CleanupAttemptDecision.Join -> decision.completion.await().deliver()
            is CleanupAttemptDecision.Execute -> executeAndPublish(mode, decision.completion).deliver()
        }
    }

    protected abstract suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome

    protected abstract fun hasUnfinishedStages(): Boolean

    private suspend fun executeAndPublish(
        mode: VoiceAgentCleanupMode,
        completion: CompletableDeferred<CleanupAttemptOutcome>,
    ): CleanupAttemptOutcome {
        val outcome = try {
            executeAttempt(mode)
        } catch (cancellation: CancellationException) {
            CleanupAttemptOutcome.Cancelled(cancellation.canonicalVoiceAgentCancellation())
        } catch (error: Throwable) {
            CleanupAttemptOutcome.Returned(VoiceAgentCleanupResult.Failed(error))
        }
        synchronized(lock) {
            check((state as? CleanupAttemptState.Running)?.completion === completion) {
                "Cleanup attempt ownership changed before publication"
            }
            state = if (hasUnfinishedStages()) CleanupAttemptState.Ready else CleanupAttemptState.Completed
            check(completion.complete(outcome)) { "Cleanup attempt was already completed" }
        }
        return outcome
    }
}

private class RouteCleanupOperation(
    private val routeLease: VoiceAgentRouteLease,
) : JoinedCleanupOperation() {
    private var routeProgress = CleanupStageProgress.Pending

    override suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome {
        val failures = CleanupAttemptFailures()
        failures.captureCallerCancellation()
        if (routeProgress == CleanupStageProgress.Pending) {
            try {
                routeLease.retire()
                routeProgress = CleanupStageProgress.Completed
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
        return failures.outcome()
    }

    override fun hasUnfinishedStages(): Boolean = routeProgress == CleanupStageProgress.Pending
}

private enum class DelegateCleanupProgress {
    Initial,
    Immediate,
    Completed,
}

private class SessionCleanupOperation(
    private val delegate: ManagedVoiceCallSession,
    private val routeLease: VoiceAgentRouteLease,
    private val endDrainTimeoutMillis: Long,
) : JoinedCleanupOperation() {
    private var routeProgress = CleanupStageProgress.Pending
    private var delegateProgress = DelegateCleanupProgress.Initial

    override suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome {
        val failures = CleanupAttemptFailures()
        failures.captureCallerCancellation()
        retireRoute(failures)
        cleanDelegate(mode, failures)
        return failures.outcome()
    }

    override fun hasUnfinishedStages(): Boolean =
        routeProgress == CleanupStageProgress.Pending || delegateProgress != DelegateCleanupProgress.Completed

    private fun retireRoute(failures: CleanupAttemptFailures) {
        if (routeProgress == CleanupStageProgress.Completed) return
        try {
            routeLease.retire()
            routeProgress = CleanupStageProgress.Completed
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private suspend fun cleanDelegate(
        mode: VoiceAgentCleanupMode,
        failures: CleanupAttemptFailures,
    ) {
        when (delegateProgress) {
            DelegateCleanupProgress.Completed -> Unit
            DelegateCleanupProgress.Immediate -> closeDelegate(failures)
            DelegateCleanupProgress.Initial -> when (mode) {
                VoiceAgentCleanupMode.Replacement -> drainDelegate(failures)
                VoiceAgentCleanupMode.GracefulEnd -> drainDelegate(failures)
                VoiceAgentCleanupMode.Immediate -> closeDelegate(failures)
            }
        }
    }

    private suspend fun drainDelegate(failures: CleanupAttemptFailures) {
        var drainFailed = false
        val completedNormally = try {
            withTimeoutOrNull(endDrainTimeoutMillis) {
                delegate.endAndDrain()
                true
            } ?: false
        } catch (error: Throwable) {
            drainFailed = true
            failures.add(error)
            false
        }
        if (completedNormally) {
            delegateProgress = DelegateCleanupProgress.Completed
            return
        }
        if (!drainFailed) {
            failures.add(VoiceAgentEndDrainTimeoutException(endDrainTimeoutMillis))
        }
        delegateProgress = DelegateCleanupProgress.Immediate
        closeDelegate(failures)
    }

    private fun closeDelegate(failures: CleanupAttemptFailures) {
        try {
            delegate.closeNow()
            delegateProgress = DelegateCleanupProgress.Completed
        } catch (error: Throwable) {
            delegateProgress = DelegateCleanupProgress.Immediate
            failures.add(error)
        }
    }
}

private class ActiveCallCleanupOperation(
    private val collector: Job,
    private val callJob: Job,
    private val sessionCleanup: VoiceAgentCleanupOperation,
) : JoinedCleanupOperation() {
    private var sessionProgress = CleanupStageProgress.Pending
    private var collectorProgress = CleanupStageProgress.Pending
    private var callJobProgress = CleanupStageProgress.Pending

    override suspend fun executeAttempt(mode: VoiceAgentCleanupMode): CleanupAttemptOutcome {
        val failures = CleanupAttemptFailures()
        failures.captureCallerCancellation()
        if (sessionProgress == CleanupStageProgress.Pending) {
            try {
                when (val result = sessionCleanup.run(mode)) {
                    VoiceAgentCleanupResult.Completed -> sessionProgress = CleanupStageProgress.Completed
                    is VoiceAgentCleanupResult.Failed -> failures.add(result.error)
                }
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
        if (collectorProgress == CleanupStageProgress.Pending) {
            collectorProgress = cleanJob(collector, failures)
        }
        if (callJobProgress == CleanupStageProgress.Pending) {
            callJobProgress = cleanJob(callJob, failures)
        }
        return failures.outcome()
    }

    override fun hasUnfinishedStages(): Boolean =
        sessionProgress == CleanupStageProgress.Pending ||
            collectorProgress == CleanupStageProgress.Pending ||
            callJobProgress == CleanupStageProgress.Pending

    private suspend fun cleanJob(job: Job, failures: CleanupAttemptFailures): CleanupStageProgress {
        return try {
            if (failures.hasCancellation) {
                withContext(NonCancellable) {
                    job.cancel()
                    job.join()
                }
            } else {
                job.cancel()
                job.join()
            }
            CleanupStageProgress.Completed
        } catch (cancellation: CancellationException) {
            failures.add(cancellation)
            try {
                withContext(NonCancellable) {
                    job.cancel()
                    job.join()
                }
                CleanupStageProgress.Completed
            } catch (error: Throwable) {
                failures.add(error)
                CleanupStageProgress.Pending
            }
        } catch (error: Throwable) {
            failures.add(error)
            CleanupStageProgress.Pending
        }
    }
}

internal class CleanupAttemptFailures {
    private val failures = mutableListOf<Throwable>()
    private var cancellation: CancellationException? = null

    val hasCancellation: Boolean
        get() = cancellation != null

    suspend fun captureCallerCancellation() {
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            add(error)
        }
    }

    fun add(error: Throwable) {
        if (error is CancellationException) {
            val canonical = error.canonicalVoiceAgentCancellation()
            val current = cancellation
            if (current == null) {
                cancellation = canonical
            } else if (current !== canonical && canonical !in failures) {
                failures += canonical
            }
        } else if (error !in failures) {
            failures += error
        }
    }

    suspend fun outcome(): CleanupAttemptOutcome {
        captureCallerCancellation()
        cancellation?.let { canonical ->
            failures.forEach { failure ->
                canonical.addVoiceAgentSuppressedDistinct(failure)
            }
            return CleanupAttemptOutcome.Cancelled(canonical)
        }
        val primary = failures.firstOrNull() ?: return CleanupAttemptOutcome.Returned(
            VoiceAgentCleanupResult.Completed,
        )
        failures.drop(1).forEach { failure ->
            primary.addVoiceAgentSuppressedDistinct(failure)
        }
        return CleanupAttemptOutcome.Returned(VoiceAgentCleanupResult.Failed(primary))
    }
}

private fun CleanupAttemptOutcome.deliver(): VoiceAgentCleanupResult = when (this) {
    is CleanupAttemptOutcome.Returned -> result
    is CleanupAttemptOutcome.Cancelled -> throw error
}

internal class VoiceAgentEndDrainTimeoutException(
    timeoutMillis: Long,
) : RuntimeException("Voice Agent end drain timed out after ${timeoutMillis}ms")
