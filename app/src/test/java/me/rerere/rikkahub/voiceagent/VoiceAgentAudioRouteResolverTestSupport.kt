package me.rerere.rikkahub.voiceagent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class FakeTelecomGateway(
    private val registerResult: Result<Unit> = Result.success(Unit),
    private val startResult: Result<Unit> = Result.success(Unit),
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit = {},
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        return registerResult
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
        onStart(attemptId)
        return startResult
    }
}

internal class ResolverFakeCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
    }
}

internal class ThrowingResolverCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        error("framework retirement failed")
    }
}

internal class CallbackFaithfulResolverCall(
    private val registry: VoiceAgentTelecomCallRegistry,
    private val cleanupFailure: AtomicReference<Throwable?>,
    private val onCleanup: (Int) -> Unit = {},
) : VoiceAgentTelecomCall {
    val disconnectCalls = AtomicInteger()

    override fun disconnectFromApp() {
        val call = disconnectCalls.incrementAndGet()
        registry.retiring(this)
        val cleanupResult = runCatching {
            onCleanup(call)
            cleanupFailure.get()?.let { throw it }
            Unit
        }
        registry.retired(this, cleanupResult)
        cleanupResult.getOrThrow()
    }
}

internal class RecordingTelecomGateway(
    private val events: MutableList<String>,
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit,
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        events += "register"
        return Result.success(Unit)
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
        events += "start"
        onStart(attemptId)
        return Result.success(Unit)
    }
}

internal class BoundaryOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    val observationStarted = CompletableDeferred<Unit>()
    val observedOutcome = CompletableDeferred<VoiceAgentTelecomOutcome>()
    val returnTimeout = CompletableDeferred<Unit>()

    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? {
        observationStarted.complete(Unit)
        observedOutcome.complete(observe())
        returnTimeout.await()
        return null
    }
}

internal class ActiveOutcomeReturnGate : VoiceAgentTelecomOutcomeTimeout {
    val observedOutcome = CompletableDeferred<VoiceAgentTelecomOutcome>()
    val returnOutcome = CompletableDeferred<Unit>()

    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome {
        val outcome = observe()
        observedOutcome.complete(outcome)
        returnOutcome.await()
        return outcome
    }
}

internal object ImmediateOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = null
}

internal class CanonicalCancellationException(
    @Suppress("unused") private val identityMarker: Any,
) : CancellationException("cancel matching waiter")

internal class NonCopyableCleanupException(
    @Suppress("unused") private val identityMarker: Any,
    message: String,
) : IllegalStateException(message)
