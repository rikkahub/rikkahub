package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext

internal fun voiceAgentStartOperation(
    request: VoiceAgentCallRequest,
    appScope: CoroutineScope,
    factory: VoiceAgentCallFactory,
    endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
    resolveRoute: suspend () -> VoiceAgentRouteLease,
    onFinished: (VoiceAgentStartOperation, VoiceAgentStartOutcome) -> Unit,
    onSessionState: (ActiveVoiceAgentCall, VoiceAgentUiState, Boolean) -> Unit,
): VoiceAgentStartOperation = DefaultVoiceAgentStartOperation(
    request = request,
    appScope = appScope,
    factory = factory,
    endDrainTimeoutMillis = endDrainTimeoutMillis,
    resolveRoute = resolveRoute,
    onFinished = onFinished,
    onSessionState = onSessionState,
)

private class DefaultVoiceAgentStartOperation(
    override val request: VoiceAgentCallRequest,
    private val appScope: CoroutineScope,
    private val factory: VoiceAgentCallFactory,
    private val endDrainTimeoutMillis: Long,
    private val resolveRoute: suspend () -> VoiceAgentRouteLease,
    private val onFinished: (VoiceAgentStartOperation, VoiceAgentStartOutcome) -> Unit,
    private val onSessionState: (ActiveVoiceAgentCall, VoiceAgentUiState, Boolean) -> Unit,
) : VoiceAgentStartOperation {
    override val token: Any = Any()
    private val phaseLock = Any()
    private var currentPhase: VoiceAgentStartPhase = VoiceAgentStartPhase.Admitted(request)
    private val startupCleanup = StartupCleanupOperation()

    override val phase: VoiceAgentStartPhase
        get() = synchronized(phaseLock) { currentPhase }

    override val cleanup: VoiceAgentCleanupOperation
        get() = startupCleanup

    override fun start() {
        val reservation = startupCleanup.reserveWorkerCreation() ?: return
        val callJob = SupervisorJob(appScope.coroutineContext[Job])
        val callScope = CoroutineScope(appScope.coroutineContext.withJob(callJob))
        updatePhase(VoiceAgentStartPhase.PreparingRoute(request, callScope, callJob))
        val worker = callScope.launch(start = CoroutineStart.LAZY) {
            val outcome = runStartup(callScope, callJob)
            onFinished(this@DefaultVoiceAgentStartOperation, outcome)
            startupCleanup.releaseLocalCleanupAfterPublication()
            if (outcome !is VoiceAgentStartOutcome.Ready) {
                callJob.complete()
            }
        }
        val shouldStart = startupCleanup.attachWorker(reservation, StartupWorker(worker, callJob))
        if (shouldStart) worker.start() else worker.cancel()
    }

    override fun cancel() {
        startupCleanup.cancelWorker()
    }

    private suspend fun runStartup(
        callScope: CoroutineScope,
        callJob: CompletableJob,
    ): VoiceAgentStartOutcome {
        return try {
            val routeLease = resolveRoute()
            startupCleanup.installDelegate(voiceAgentRouteCleanupOperation(routeLease))
            updatePhase(VoiceAgentStartPhase.CreatingSession(request, callScope, callJob))
            yield()
            currentCoroutineContext().ensureActive()
            when (val creation = factory.createOwned(
                request,
                routeLease,
                callScope,
                endDrainTimeoutMillis,
            )) {
                is VoiceAgentSessionCreationResult.Created -> startSession(creation.session, callScope, callJob)
                is VoiceAgentSessionCreationResult.FailedClean -> {
                    startupCleanup.clearDelegate()
                    VoiceAgentStartOutcome.FailedClean(creation.error)
                }
                is VoiceAgentSessionCreationResult.FailedDirty -> {
                    startupCleanup.installDelegate(creation.cleanup)
                    VoiceAgentStartOutcome.FailedDirty(creation.error, startupCleanup)
                }
            }
        } catch (cancellation: CancellationException) {
            val canonical = cancellation.canonicalVoiceAgentCancellation()
            finishCancellation(canonical)
        } catch (error: Throwable) {
            finishFailure(error)
        }
    }

    private suspend fun startSession(
        session: RouteOwnedManagedVoiceCallSession,
        callScope: CoroutineScope,
        callJob: CompletableJob,
    ): VoiceAgentStartOutcome {
        startupCleanup.installDelegate(session.cleanupOperation)
        updatePhase(VoiceAgentStartPhase.StartingSession(request, callScope, callJob, session))
        return try {
            yield()
            currentCoroutineContext().ensureActive()
            session.start()
            yield()
            val initialState = session.state.value
            val routeMetadata = session.routeMetadata
            lateinit var call: ActiveVoiceAgentCall
            val collector = callScope.launch(start = CoroutineStart.LAZY) {
                session.state.collect { state ->
                    onSessionState(call, state, session.isRouteUsable)
                }
            }
            val activeCleanup = activeVoiceAgentCallCleanupOperation(
                collector = collector,
                callJob = callJob,
                sessionCleanup = session.cleanupOperation,
            )
            startupCleanup.installDelegate(activeCleanup)
            call = ActiveVoiceAgentCall(
                token = token,
                request = request,
                route = routeMetadata,
                session = session,
                callScope = callScope,
                callJob = callJob,
                collector = collector,
                cleanup = startupCleanup,
            )
            yield()
            currentCoroutineContext().ensureActive()
            VoiceAgentStartOutcome.Ready(call, initialState)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            finishFailure(error)
        }
    }

    private suspend fun finishFailure(error: Throwable): VoiceAgentStartOutcome {
        return when (val claim = startupCleanup.claimLocalCleanup()) {
            StartupLocalCleanupDecision.Clean -> VoiceAgentStartOutcome.FailedClean(error)
            StartupLocalCleanupDecision.External -> VoiceAgentStartOutcome.Cancelled
            is StartupLocalCleanupDecision.Execute -> finishClaimedFailure(error, claim)
        }
    }

    private suspend fun finishCancellation(error: CancellationException): VoiceAgentStartOutcome {
        return when (val claim = startupCleanup.claimLocalCleanup()) {
            StartupLocalCleanupDecision.Clean -> VoiceAgentStartOutcome.CancelledClean(error)
            StartupLocalCleanupDecision.External -> VoiceAgentStartOutcome.Cancelled
            is StartupLocalCleanupDecision.Execute -> finishClaimedCancellation(error, claim)
        }
    }

    private suspend fun finishClaimedCancellation(
        error: CancellationException,
        claim: StartupLocalCleanupDecision.Execute,
    ): VoiceAgentStartOutcome {
        val result = withContext(NonCancellable) {
            try {
                claim.cleanup.run(VoiceAgentCleanupMode.Immediate)
            } catch (cleanupError: Throwable) {
                VoiceAgentCleanupResult.Failed(cleanupError)
            }
        }
        if (startupCleanup.completeLocalCleanup(claim, result)) {
            return VoiceAgentStartOutcome.Cancelled
        }
        return when (result) {
            VoiceAgentCleanupResult.Completed -> VoiceAgentStartOutcome.CancelledClean(error)
            is VoiceAgentCleanupResult.Failed -> {
                error.addVoiceAgentSuppressedDistinct(result.error)
                VoiceAgentStartOutcome.CancelledDirty(error, startupCleanup)
            }
        }
    }

    private suspend fun finishClaimedFailure(
        error: Throwable,
        claim: StartupLocalCleanupDecision.Execute,
    ): VoiceAgentStartOutcome {
        val result = withContext(NonCancellable) {
            try {
                claim.cleanup.run(VoiceAgentCleanupMode.Immediate)
            } catch (cleanupError: Throwable) {
                VoiceAgentCleanupResult.Failed(cleanupError)
            }
        }
        if (startupCleanup.completeLocalCleanup(claim, result)) {
            return VoiceAgentStartOutcome.Cancelled
        }
        return when (result) {
            VoiceAgentCleanupResult.Completed -> VoiceAgentStartOutcome.FailedClean(error)
            is VoiceAgentCleanupResult.Failed -> {
                error.addVoiceAgentSuppressedDistinct(result.error)
                VoiceAgentStartOutcome.FailedDirty(error, startupCleanup)
            }
        }
    }

    private fun updatePhase(value: VoiceAgentStartPhase) {
        synchronized(phaseLock) {
            currentPhase = value
        }
    }
}

private sealed interface StartupCleanupAttempt {
    data object Ready : StartupCleanupAttempt
    data class Local(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupAttempt
    data class Running(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupAttempt
    data object Completed : StartupCleanupAttempt
}

private sealed interface StartupCleanupDecision {
    data object Completed : StartupCleanupDecision
    data class Execute(
        val completion: CompletableDeferred<VoiceAgentCleanupResult>,
        val localCompletion: CompletableDeferred<VoiceAgentCleanupResult>?,
    ) : StartupCleanupDecision
    data class Join(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupDecision
}

private sealed interface StartupLocalCleanupDecision {
    data object Clean : StartupLocalCleanupDecision
    data object External : StartupLocalCleanupDecision

    data class Execute(
        val cleanup: VoiceAgentCleanupOperation,
        val completion: CompletableDeferred<VoiceAgentCleanupResult>,
    ) : StartupLocalCleanupDecision
}

private sealed interface StartupCleanupTarget {
    data object None : StartupCleanupTarget
    data class Owned(val cleanup: VoiceAgentCleanupOperation) : StartupCleanupTarget
}

private enum class StartupCallJobProgress {
    Pending,
    Completed,
}

private enum class StartupCancellationState {
    Running,
    CleanupRequested,
}

private data class StartupWorker(
    val job: Job,
    val callJob: CompletableJob,
)

private sealed interface StartupWorkerState {
    data object Admitted : StartupWorkerState

    data class Creating(
        val completion: CompletableDeferred<StartupWorker>,
    ) : StartupWorkerState

    data class Attached(
        val worker: StartupWorker,
    ) : StartupWorkerState

    data object CancelledBeforeStart : StartupWorkerState
}

private sealed interface StartupWorkerAccess {
    data object None : StartupWorkerAccess
    data class Ready(val worker: StartupWorker) : StartupWorkerAccess
    data class Await(val completion: CompletableDeferred<StartupWorker>) : StartupWorkerAccess
}

private class StartupCleanupOperation : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    private val lock = Any()
    private var workerState: StartupWorkerState = StartupWorkerState.Admitted
    private var target: StartupCleanupTarget = StartupCleanupTarget.None
    private var attempt: StartupCleanupAttempt = StartupCleanupAttempt.Ready
    private var callJobProgress = StartupCallJobProgress.Pending
    private var cancellationState = StartupCancellationState.Running

    fun reserveWorkerCreation(): CompletableDeferred<StartupWorker>? = synchronized(lock) {
        if (
            cancellationState == StartupCancellationState.CleanupRequested ||
            workerState != StartupWorkerState.Admitted
        ) {
            return@synchronized null
        }
        CompletableDeferred<StartupWorker>().also { completion ->
            workerState = StartupWorkerState.Creating(completion)
        }
    }

    fun attachWorker(
        reservation: CompletableDeferred<StartupWorker>,
        worker: StartupWorker,
    ): Boolean {
        val shouldStart = synchronized(lock) {
            val creating = workerState as? StartupWorkerState.Creating
            check(creating?.completion === reservation) { "Startup worker reservation is no longer current" }
            workerState = StartupWorkerState.Attached(worker)
            cancellationState == StartupCancellationState.Running
        }
        check(reservation.complete(worker)) { "Startup worker reservation was already completed" }
        return shouldStart
    }

    fun installDelegate(value: VoiceAgentCleanupOperation) {
        synchronized(lock) {
            target = StartupCleanupTarget.Owned(value)
        }
    }

    fun currentTarget(): StartupCleanupTarget = synchronized(lock) { target }

    fun cancelWorker() {
        val worker = synchronized(lock) {
            cancellationState = StartupCancellationState.CleanupRequested
            when (val current = workerState) {
                StartupWorkerState.Admitted -> {
                    workerState = StartupWorkerState.CancelledBeforeStart
                    null
                }
                is StartupWorkerState.Creating -> null
                is StartupWorkerState.Attached -> current.worker.job
                StartupWorkerState.CancelledBeforeStart -> null
            }
        }
        worker?.cancel()
    }

    fun clearDelegate(expected: VoiceAgentCleanupOperation? = null) {
        synchronized(lock) {
            val current = target as? StartupCleanupTarget.Owned
            if (expected == null || current?.cleanup === expected) target = StartupCleanupTarget.None
        }
    }

    fun claimLocalCleanup(): StartupLocalCleanupDecision = synchronized(lock) {
        if (
            cancellationState == StartupCancellationState.CleanupRequested ||
            attempt != StartupCleanupAttempt.Ready
        ) {
            return@synchronized StartupLocalCleanupDecision.External
        }
        val cleanup = when (val current = target) {
            StartupCleanupTarget.None -> return@synchronized StartupLocalCleanupDecision.Clean
            is StartupCleanupTarget.Owned -> current.cleanup
        }
        val completion = CompletableDeferred<VoiceAgentCleanupResult>()
        attempt = StartupCleanupAttempt.Local(completion)
        StartupLocalCleanupDecision.Execute(cleanup, completion)
    }

    fun completeLocalCleanup(
        claim: StartupLocalCleanupDecision.Execute,
        result: VoiceAgentCleanupResult,
    ): Boolean = synchronized(lock) {
        if (result == VoiceAgentCleanupResult.Completed) {
            val current = target as? StartupCleanupTarget.Owned
            if (current?.cleanup === claim.cleanup) target = StartupCleanupTarget.None
        }
        check(claim.completion.complete(result)) { "Local startup cleanup was already completed" }
        cancellationState == StartupCancellationState.CleanupRequested ||
            (attempt as? StartupCleanupAttempt.Local)?.completion !== claim.completion
    }

    fun releaseLocalCleanupAfterPublication() {
        synchronized(lock) {
            val local = attempt as? StartupCleanupAttempt.Local ?: return
            if (
                local.completion.isCompleted &&
                cancellationState == StartupCancellationState.Running
            ) {
                attempt = StartupCleanupAttempt.Ready
            }
        }
    }

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        cancelWorker()
        val decision = synchronized(lock) {
            when (val current = attempt) {
                StartupCleanupAttempt.Completed -> StartupCleanupDecision.Completed
                StartupCleanupAttempt.Ready -> CompletableDeferred<VoiceAgentCleanupResult>().also {
                    attempt = StartupCleanupAttempt.Running(it)
                }.let { StartupCleanupDecision.Execute(it, null) }
                is StartupCleanupAttempt.Local -> CompletableDeferred<VoiceAgentCleanupResult>().also {
                    attempt = StartupCleanupAttempt.Running(it)
                }.let { externalCompletion ->
                    val joinsLocalAttempt =
                        cancellationState == StartupCancellationState.CleanupRequested ||
                            !current.completion.isCompleted
                    StartupCleanupDecision.Execute(
                        completion = externalCompletion,
                        localCompletion = current.completion.takeIf { joinsLocalAttempt },
                    )
                }
                is StartupCleanupAttempt.Running -> StartupCleanupDecision.Join(current.completion)
            }
        }
        return when (decision) {
            StartupCleanupDecision.Completed -> VoiceAgentCleanupResult.Completed
            is StartupCleanupDecision.Join -> decision.completion.await()
            is StartupCleanupDecision.Execute -> executeAndPublish(
                mode = mode,
                completion = decision.completion,
                localCompletion = decision.localCompletion,
            )
        }
    }

    private suspend fun executeAndPublish(
        mode: VoiceAgentCleanupMode,
        completion: CompletableDeferred<VoiceAgentCleanupResult>,
        localCompletion: CompletableDeferred<VoiceAgentCleanupResult>?,
    ): VoiceAgentCleanupResult {
        val localResult = localCompletion?.await()
        val result = executeAttempt(mode, localResult)
        synchronized(lock) {
            if ((attempt as? StartupCleanupAttempt.Running)?.completion === completion) {
                attempt = if (
                    target == StartupCleanupTarget.None &&
                    callJobProgress == StartupCallJobProgress.Completed
                ) {
                    StartupCleanupAttempt.Completed
                } else {
                    StartupCleanupAttempt.Ready
                }
                completion.complete(result)
            }
        }
        return result
    }

    private suspend fun executeAttempt(
        mode: VoiceAgentCleanupMode,
        localResult: VoiceAgentCleanupResult?,
    ): VoiceAgentCleanupResult {
        var failure = (localResult as? VoiceAgentCleanupResult.Failed)?.error
        val worker = awaitWorker()
        if (worker != null) {
            worker.job.cancel()
            try {
                worker.job.join()
            } catch (error: Throwable) {
                failure = failure.appendStartupFailure(error)
            }
        }
        val current = currentTarget()
        if (localResult == null && current is StartupCleanupTarget.Owned) {
            try {
                when (val result = current.cleanup.run(mode)) {
                    VoiceAgentCleanupResult.Completed -> clearDelegate(current.cleanup)
                    is VoiceAgentCleanupResult.Failed -> failure = failure.appendStartupFailure(result.error)
                }
            } catch (error: Throwable) {
                failure = failure.appendStartupFailure(error)
            }
        }
        if (callJobProgress == StartupCallJobProgress.Pending) {
            if (worker == null) {
                callJobProgress = StartupCallJobProgress.Completed
            } else {
                try {
                    worker.callJob.cancel()
                    worker.callJob.join()
                    callJobProgress = StartupCallJobProgress.Completed
                } catch (error: Throwable) {
                    failure = failure.appendStartupFailure(error)
                }
            }
        }
        return when {
            failure == null -> VoiceAgentCleanupResult.Completed
            localResult is VoiceAgentCleanupResult.Failed && failure === localResult.error -> localResult
            else -> VoiceAgentCleanupResult.Failed(failure)
        }
    }

    private suspend fun awaitWorker(): StartupWorker? {
        val access = synchronized(lock) {
            when (val current = workerState) {
                StartupWorkerState.Admitted,
                StartupWorkerState.CancelledBeforeStart,
                -> StartupWorkerAccess.None
                is StartupWorkerState.Creating -> StartupWorkerAccess.Await(current.completion)
                is StartupWorkerState.Attached -> StartupWorkerAccess.Ready(current.worker)
            }
        }
        return when (access) {
            StartupWorkerAccess.None -> null
            is StartupWorkerAccess.Ready -> access.worker
            is StartupWorkerAccess.Await -> access.completion.await()
        }
    }
}

private fun Throwable?.appendStartupFailure(error: Throwable): Throwable = when {
    this == null -> error
    this !== error && error !in suppressed -> apply { addSuppressed(error) }
    else -> this
}

private fun CoroutineContext.withJob(job: Job): CoroutineContext = minusKey(Job) + job
