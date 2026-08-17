package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface VoiceAgentTelecomOutcomeTimeout {
    suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome?
}

internal fun interface VoiceAgentRouteDeliveryProbe {
    fun onDeliveryArmed(job: Job)
}

internal data class VoiceAgentRouteExecutionDispatchers(
    val acquisition: CoroutineDispatcher,
    val cleanup: CoroutineDispatcher,
)

private object NoOpVoiceAgentRouteDeliveryProbe : VoiceAgentRouteDeliveryProbe {
    override fun onDeliveryArmed(job: Job) = Unit
}

private object DefaultVoiceAgentTelecomOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = withTimeoutOrNull(timeoutMs) { observe() }
}

class VoiceAgentAudioRouteResolver internal constructor(
    private val gateway: VoiceAgentTelecomGateway,
    private val registry: VoiceAgentTelecomCallRegistry,
    private val timeoutMs: Long,
    private val outcomeTimeout: VoiceAgentTelecomOutcomeTimeout,
    private val executionDispatchers: VoiceAgentRouteExecutionDispatchers,
    private val cleanupScope: CoroutineScope,
    private val deliveryProbe: VoiceAgentRouteDeliveryProbe,
) {
    internal constructor(
        gateway: VoiceAgentTelecomGateway,
        registry: VoiceAgentTelecomCallRegistry,
        timeoutMs: Long = 3_000L,
        outcomeTimeout: VoiceAgentTelecomOutcomeTimeout,
        executionDispatchers: VoiceAgentRouteExecutionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers,
    ) : this(
        gateway,
        registry,
        timeoutMs,
        outcomeTimeout,
        executionDispatchers,
        DefaultVoiceAgentRouteCleanupScope,
        NoOpVoiceAgentRouteDeliveryProbe,
    )

    constructor(
        gateway: VoiceAgentTelecomGateway,
        registry: VoiceAgentTelecomCallRegistry,
        timeoutMs: Long = 3_000L,
    ) : this(
        gateway,
        registry,
        timeoutMs,
        DefaultVoiceAgentTelecomOutcomeTimeout,
        DefaultVoiceAgentRouteExecutionDispatchers,
        DefaultVoiceAgentRouteCleanupScope,
        NoOpVoiceAgentRouteDeliveryProbe,
    )

    internal constructor(
        gateway: VoiceAgentTelecomGateway,
        registry: VoiceAgentTelecomCallRegistry,
        timeoutMs: Long = 3_000L,
        executionDispatchers: VoiceAgentRouteExecutionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers,
        cleanupScope: CoroutineScope = DefaultVoiceAgentRouteCleanupScope,
        deliveryProbe: VoiceAgentRouteDeliveryProbe = NoOpVoiceAgentRouteDeliveryProbe,
    ) : this(
        gateway,
        registry,
        timeoutMs,
        DefaultVoiceAgentTelecomOutcomeTimeout,
        executionDispatchers,
        cleanupScope,
        deliveryProbe,
    )

    suspend fun resolve(): VoiceAgentRouteResolution {
        val attempt = beginAttemptRespectingCancellation()
        val resolution = try {
            resolveAttempt(attempt)
        } catch (cancellation: CancellationException) {
            cleanupCancelledAttempt(attempt, cancellation)
            throw cancellation
        }
        val delivery = FinalResolutionDelivery(
            resolution = resolution,
            cleanupScope = cleanupScope,
            cleanupDispatcher = executionDispatchers.cleanup,
        )
        return try {
            deliverResolution(delivery)
        } catch (cancellation: CancellationException) {
            delivery.awaitCleanupAndAttachTo(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            delivery.requestCleanup(error)
            delivery.awaitCleanupAndAttachTo(error)
            throw error
        }
    }

    private suspend fun resolveAttempt(
        attempt: VoiceAgentTelecomAttemptId,
    ): VoiceAgentRouteResolution {
        gateway.register().exceptionOrNull()?.let { error ->
            return fallback(attempt, "telecom_register_failed", error)
        }
        gateway.startCall(attempt).exceptionOrNull()?.let { error ->
            return fallback(attempt, "telecom_start_failed", error)
        }
        return when (val outcome = outcomeTimeout.awaitOutcome(timeoutMs) { registry.observeOutcome(attempt) }) {
            VoiceAgentTelecomOutcome.Active -> registry.consumeActiveOutcome(attempt)
            is VoiceAgentTelecomOutcome.Failed -> {
                registry.acknowledgeOutcome(attempt)
                VoiceAgentRouteResolution.Resolved(
                    DirectFallbackVoiceAgentRouteLease(outcome.failure),
                )
            }
            is VoiceAgentTelecomOutcome.CleanupFailed -> {
                registry.acknowledgeOutcome(attempt)
                throw outcome.cleanupError
            }
            null -> fallback(
                attempt,
                "telecom_connection_timeout",
                IllegalStateException("Android Telecom did not become active within ${timeoutMs}ms"),
            )
        }
    }

    private suspend fun beginAttemptRespectingCancellation(): VoiceAgentTelecomAttemptId {
        val callerContext = currentCoroutineContext()
        val result = withContext(NonCancellable) {
            withContext(executionDispatchers.acquisition) {
                runCatching(registry::beginAttempt)
            }
        }
        val cancellation = runCatching {
            callerContext.ensureActive()
        }.exceptionOrNull() as? CancellationException
        if (cancellation != null) {
            val beginFailure = result.exceptionOrNull()
            beginFailure?.let { cancellation.addSuppressedDistinct(it) }
            when (val startResult = result.getOrNull()) {
                is VoiceAgentTelecomAttemptStartResult.Allocated -> {
                    cleanupCancelledAttempt(startResult.attemptId, cancellation)
                }
                is VoiceAgentTelecomAttemptStartResult.CleanupFailed -> {
                    cancellation.addSuppressedDistinct(startResult.error)
                }
                null -> Unit
            }
            throw cancellation
        }
        return when (val startResult = result.getOrThrow()) {
            is VoiceAgentTelecomAttemptStartResult.Allocated -> startResult.attemptId
            is VoiceAgentTelecomAttemptStartResult.CleanupFailed -> throw startResult.error
        }
    }

    private suspend fun cleanupCancelledAttempt(
        attempt: VoiceAgentTelecomAttemptId,
        cancellation: CancellationException,
    ) {
        val schedulingError = withContext(NonCancellable) {
            runCatching {
                withContext(executionDispatchers.cleanup) {
                    retireAndAcknowledgeCancelledAttempt(attempt, cancellation)
                }
            }.exceptionOrNull()?.exactSchedulingFailure()
        }
        if (schedulingError != null) {
            cancellation.addSuppressedDistinct(schedulingError)
            val fallbackSchedulingError = withContext(NonCancellable) {
                runCatching {
                    withContext(DefaultVoiceAgentRouteCleanupDispatcher) {
                        retireAndAcknowledgeCancelledAttempt(attempt, cancellation)
                    }
                }.exceptionOrNull()?.exactSchedulingFailure()
            }
            if (fallbackSchedulingError != null) {
                cancellation.addSuppressedDistinct(fallbackSchedulingError)
            }
        }
    }

    private suspend fun retireAndAcknowledgeCancelledAttempt(
        attempt: VoiceAgentTelecomAttemptId,
        cancellation: CancellationException,
    ) {
        val retirementError = runCatching {
            registry.retireAttempt(
                attempt,
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_resolution_cancelled",
                    detail = cancellation.message ?: cancellation.javaClass.simpleName,
                ),
            )
        }.exceptionOrNull()
        val acknowledgementError = runCatching {
            registry.awaitOutcome(attempt)
        }.exceptionOrNull()
        retirementError?.let { cancellation.addSuppressedDistinct(it) }
        acknowledgementError?.let { cancellation.addSuppressedDistinct(it) }
    }

    private suspend fun fallback(
        attempt: VoiceAgentTelecomAttemptId,
        name: String,
        error: Throwable,
    ): VoiceAgentRouteResolution {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        return when (retired) {
            VoiceAgentTelecomOutcome.Active -> registry.consumeActiveOutcome(attempt)
            is VoiceAgentTelecomOutcome.Failed -> VoiceAgentRouteResolution.Resolved(
                DirectFallbackVoiceAgentRouteLease(retired.failure),
            )
            is VoiceAgentTelecomOutcome.CleanupFailed -> throw retired.cleanupError
        }
    }

    private suspend fun deliverResolution(
        delivery: FinalResolutionDelivery,
    ): VoiceAgentRouteResolution = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation(delivery::requestCleanup)
        deliveryProbe.onDeliveryArmed(checkNotNull(continuation.context[Job]))
        try {
            continuation.resume(delivery.resolution) { cancellation, _, _ ->
                delivery.requestCleanup(cancellation)
            }
        } catch (error: Throwable) {
            delivery.requestCleanup(error)
            throw error
        }
    }
}

private class FinalResolutionDelivery(
    val resolution: VoiceAgentRouteResolution,
    private val cleanupScope: CoroutineScope,
    private val cleanupDispatcher: CoroutineDispatcher,
) {
    private var cleanup: FinalDeliveryCleanup = FinalDeliveryCleanup.Pending

    @Synchronized
    fun requestCleanup(cancellation: Throwable?) {
        if (cleanup !is FinalDeliveryCleanup.Pending) return
        val work = when (val undelivered = resolution) {
            is VoiceAgentRouteResolution.Resolved -> when (val lease = undelivered.lease) {
                is TelecomVoiceAgentRouteLease -> TelecomFinalDeliveryCleanup(
                    lease = lease,
                    acquisition = lease.claimUndeliveredCleanup(),
                )
                is DirectFallbackVoiceAgentRouteLease -> DirectFinalDeliveryCleanup(lease)
                else -> DirectFinalDeliveryCleanup(lease)
            }
            is VoiceAgentRouteResolution.CleanupFailed -> CompletedFinalDeliveryCleanup(
                Result.failure(undelivered.error),
            )
            is VoiceAgentRouteResolution.Superseded -> CompletedFinalDeliveryCleanup(Result.success(Unit))
        }
        cleanup = FinalDeliveryCleanup.Claimed(work)
        if (work !is CompletedFinalDeliveryCleanup) schedule(work, cancellation)
    }

    private fun schedule(
        work: FinalDeliveryCleanupWork,
        cancellation: Throwable?,
    ) {
        val entered = AtomicBoolean()
        val task = try {
            cleanupScope.async(cleanupDispatcher) {
                entered.set(true)
                work.execute()
            }
        } catch (error: Throwable) {
            val schedulingError = error.exactSchedulingFailure()
            work.rejectScheduling(schedulingError)
            cancellation?.addSuppressedDistinct(schedulingError)
            return
        }
        task.invokeOnCompletion { completionError ->
            if (entered.compareAndSet(false, true)) {
                val schedulingError = completionError?.exactSchedulingFailure()
                    ?: CancellationException("Voice Agent route cleanup did not start")
                work.rejectScheduling(schedulingError)
                cancellation?.addSuppressedDistinct(schedulingError)
            }
        }
    }

    suspend fun awaitCleanupAndAttachTo(primary: Throwable) {
        val work = synchronized(this) {
            (cleanup as? FinalDeliveryCleanup.Claimed)?.work
        } ?: return
        val cleanupError = withContext(NonCancellable) {
            work.awaitResult().exceptionOrNull()
        }
        cleanupError?.let(primary::addSuppressedDistinct)
    }
}

private sealed interface FinalDeliveryCleanup {
    data object Pending : FinalDeliveryCleanup

    data class Claimed(
        val work: FinalDeliveryCleanupWork,
    ) : FinalDeliveryCleanup
}

private sealed interface FinalDeliveryCleanupWork {
    fun execute()
    fun rejectScheduling(error: Throwable)
    suspend fun awaitResult(): Result<Unit>
}

private class TelecomFinalDeliveryCleanup(
    private val lease: TelecomVoiceAgentRouteLease,
    private val acquisition: UndeliveredRouteCleanupAcquisition,
) : FinalDeliveryCleanupWork {
    override fun execute() = lease.executeUndeliveredCleanup(acquisition)

    override fun rejectScheduling(error: Throwable) {
        lease.rejectUndeliveredCleanupScheduling(acquisition.claim, error)
    }

    override suspend fun awaitResult(): Result<Unit> = acquisition.claim.awaitResult()
}

private class DirectFinalDeliveryCleanup(
    private val lease: VoiceAgentRouteLease,
) : FinalDeliveryCleanupWork {
    private val completion = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()

    override fun execute() {
        completion.complete(runCatching(lease::retire))
    }

    override fun rejectScheduling(error: Throwable) {
        completion.complete(Result.failure(error))
    }

    override suspend fun awaitResult(): Result<Unit> = completion.await()
}

private class CompletedFinalDeliveryCleanup(
    private val result: Result<Unit>,
) : FinalDeliveryCleanupWork {
    override fun execute() = Unit

    override fun rejectScheduling(error: Throwable) = Unit

    override suspend fun awaitResult(): Result<Unit> = result
}

private object DefaultVoiceAgentRouteCleanupScope : CoroutineScope by CoroutineScope(
    SupervisorJob() + Dispatchers.IO,
)

internal val DefaultVoiceAgentRouteExecutionDispatchers = VoiceAgentRouteExecutionDispatchers(
    acquisition = Dispatchers.IO,
    cleanup = Dispatchers.IO.limitedParallelism(1),
)

private val DefaultVoiceAgentRouteCleanupDispatcher = DefaultVoiceAgentRouteExecutionDispatchers.cleanup

private fun Throwable.addSuppressedDistinct(error: Throwable) {
    if (error !== this && suppressed.none { it === error }) addSuppressed(error)
}

private fun Throwable.exactSchedulingFailure(): Throwable {
    var exact = this
    while (
        exact.cause != null &&
        (exact is CancellationException || exact.javaClass.simpleName == "DispatchException")
    ) {
        exact = checkNotNull(exact.cause)
    }
    return exact
}
