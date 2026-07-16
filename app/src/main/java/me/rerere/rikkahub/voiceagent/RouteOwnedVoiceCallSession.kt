package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed interface VoiceAgentEndDrainOutcome {
    class Completed internal constructor(val failure: Throwable?) : VoiceAgentEndDrainOutcome
    class Failed internal constructor(val failure: Throwable) : VoiceAgentEndDrainOutcome
    class TimedOut internal constructor(val failure: Throwable) : VoiceAgentEndDrainOutcome
}

interface RouteOwnedManagedVoiceCallSession : ManagedVoiceCallSession {
    val routeMetadata: VoiceAgentRouteMetadata
    val isRouteUsable: Boolean
    suspend fun endAndDrainWithin(timeoutMillis: Long): VoiceAgentEndDrainOutcome
}

class RouteOwnedVoiceCallSession(
    private val delegate: ManagedVoiceCallSession,
    private val routeLease: VoiceAgentRouteLease,
) : RouteOwnedManagedVoiceCallSession {
    override val state = delegate.state
    override val routeMetadata = routeLease.metadata
    override val isRouteUsable: Boolean
        get() = routeLease.isUsable

    override fun start() = delegate.start()

    override fun interrupt() = delegate.interrupt()

    override fun setMuted(value: Boolean) = delegate.setMuted(value)

    override fun reconnect() = delegate.reconnect()

    override fun recordDiagnostic(name: String, detail: String) = delegate.recordDiagnostic(name, detail)

    override fun end() = runVoiceAgentCleanupStages(routeLease::retire, delegate::end)

    override suspend fun endAndDrain() = runVoiceAgentSuspendCleanupStages(
        { routeLease.retire() },
        delegate::endAndDrain,
    )

    override suspend fun endAndDrainWithin(timeoutMillis: Long): VoiceAgentEndDrainOutcome {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        var failure = runCatching(routeLease::retire).exceptionOrNull()
        var drainFailure: Throwable? = null
        val completedNormally = try {
            withTimeoutOrNull(timeoutMillis) {
                delegate.endAndDrain()
                true
            } ?: false
        } catch (cancellation: CancellationException) {
            failure = closeDelegateNow(failure)
            failure?.takeIf { it !== cancellation }?.let(cancellation::addSuppressed)
            throw cancellation
        } catch (error: Throwable) {
            drainFailure = error
            false
        }
        if (completedNormally) return VoiceAgentEndDrainOutcome.Completed(failure)

        drainFailure?.let { failure = failure.withEndDrainFailure(it) }
        if (drainFailure != null) {
            failure = closeDelegateNow(failure)
            return VoiceAgentEndDrainOutcome.Failed(checkNotNull(failure))
        }

        failure = failure.withEndDrainFailure(VoiceAgentEndDrainTimeoutException(timeoutMillis))
        failure = closeDelegateNow(failure)
        return VoiceAgentEndDrainOutcome.TimedOut(checkNotNull(failure))
    }

    private fun closeDelegateNow(failure: Throwable?): Throwable? =
        runCatching(delegate::closeNow)
            .exceptionOrNull()
            ?.let { closeFailure -> failure.withEndDrainFailure(closeFailure) }
            ?: failure

    override fun closeNow() = runVoiceAgentCleanupStages(routeLease::retire, delegate::closeNow)
}

internal class VoiceAgentEndDrainTimeoutException(
    timeoutMillis: Long,
) : RuntimeException("Voice Agent end drain timed out after ${timeoutMillis}ms")

private fun Throwable?.withEndDrainFailure(error: Throwable): Throwable = when {
    this == null -> error
    this !== error -> apply { addSuppressed(error) }
    else -> this
}
