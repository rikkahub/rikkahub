package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal const val VOICE_AGENT_END_DRAIN_TIMEOUT_MS = 15_000L

internal interface VoiceAgentCallServiceLifecycleHost {
    fun cancelNotification()
    fun startForeground(conversationId: String, state: VoiceAgentUiState)
    fun endCompleted(conversationId: Uuid?)
    fun stopForeground()
    fun stopSelf()
    fun reportCleanupFailure(error: Throwable)
    fun destroyBaseService()
}

internal class VoiceAgentCallServiceLifecycle(
    private val manager: VoiceAgentCallManager,
    private val serviceScope: CoroutineScope,
    private val host: VoiceAgentCallServiceLifecycleHost,
    private val endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
) {
    private val endJobTracker = VoiceAgentEndJobTracker()
    var currentGeneration: Long = 0L
        private set

    init {
        require(endDrainTimeoutMillis > 0) { "endDrainTimeoutMillis must be positive" }
    }

    fun beginStart(): Long {
        currentGeneration += 1
        endJobTracker.clearTracking()
        host.cancelNotification()
        return currentGeneration
    }

    fun isCurrent(generation: Long): Boolean = generation == currentGeneration

    fun isStartupTerminal(startGeneration: Long, session: VoiceSessionStatus): Boolean =
        isVoiceAgentStartupTerminal(startGeneration, currentGeneration, session)

    fun handleStartupTerminal(conversationId: Uuid, session: VoiceSessionStatus): Boolean = when (session) {
        VoiceSessionStatus.Connected -> false
        is VoiceSessionStatus.Error -> {
            tearDownFailedStart(
                conversationId = conversationId,
                error = IllegalStateException(session.message),
                preserveSessionRequested = true,
            )
            true
        }
        VoiceSessionStatus.Ended -> {
            tearDownFailedStart(
                conversationId = conversationId,
                error = IllegalStateException("Voice call ended before startup completed"),
            )
            true
        }
        else -> false
    }

    fun tearDownFailedStart(
        conversationId: Uuid,
        error: Throwable,
        preserveSessionRequested: Boolean = false,
    ) {
        val detail = error.message ?: error.javaClass.simpleName
        val preserveSession = preserveSessionRequested && manager.canPreserveActiveSession(conversationId)
        val cleanupFailure = runCatching {
            runVoiceAgentCleanupStages(
                host::cancelNotification,
                { manager.recordDiagnostic("voice_call_start_failed", detail) },
                { if (!preserveSession) manager.closeNow() },
                { manager.updateCallStatus(VoiceCallStatus.Degraded("Voice call startup failed: $detail")) },
                { host.startForeground(conversationId.toString(), manager.state.value) },
                { if (!preserveSession) host.stopForeground() },
                { if (!preserveSession) host.stopSelf() },
            )
        }.exceptionOrNull()
        cleanupFailure?.let(::reportCleanupFailureSafely)
    }

    fun endCall(): Boolean {
        if (endJobTracker.job?.isActive == true) return false

        host.cancelNotification()
        val endingConversationId = manager.activeConversationId.value
        if (shouldStartForegroundForVoiceAgentEnd(endingConversationId)) {
            host.startForeground(
                endingConversationId?.toString() ?: FALLBACK_END_NOTIFICATION_CONVERSATION_ID,
                manager.state.value.copy(call = VoiceCallStatus.Ending),
            )
        }
        currentGeneration += 1
        val endGeneration = currentGeneration
        val session = manager.detachForEndAndDrain()
        endJobTracker.launch(serviceScope) {
            val cleanupFailure = runCatching {
                runVoiceAgentSuspendCleanupStages(
                    { session?.let { drainOwnedSession(it) } },
                    { if (isCurrent(endGeneration)) host.endCompleted(endingConversationId) },
                    { if (isCurrent(endGeneration)) host.stopForeground() },
                    { if (isCurrent(endGeneration)) host.stopSelf() },
                )
            }.exceptionOrNull()
            cleanupFailure?.let(::reportCleanupFailureSafely)
        }
        return true
    }

    fun destroy() {
        currentGeneration += 1
        runVoiceAgentCleanupStages(
            host::cancelNotification,
            endJobTracker::clearTracking,
            manager::closeNow,
            serviceScope::cancel,
            host::destroyBaseService,
        )
    }

    private suspend fun drainOwnedSession(session: RouteOwnedManagedVoiceCallSession) {
        val failure = when (val outcome = session.endAndDrainWithin(endDrainTimeoutMillis)) {
            is VoiceAgentEndDrainOutcome.Completed -> outcome.failure
            is VoiceAgentEndDrainOutcome.Failed -> outcome.failure
            is VoiceAgentEndDrainOutcome.TimedOut -> outcome.failure
        }
        failure?.let { throw it }
    }

    private fun reportCleanupFailureSafely(error: Throwable) {
        runCatching { host.reportCleanupFailure(error) }
    }

    private companion object {
        const val FALLBACK_END_NOTIFICATION_CONVERSATION_ID = "voice-agent"
    }
}

internal class VoiceAgentEndJobTracker {
    var job: Job? = null
        private set

    private var operationToken: Any? = null

    fun clearTracking() {
        operationToken = null
        job = null
    }

    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        val token = Any()
        operationToken = token
        job = null
        val launchedJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    block()
                } finally {
                    clearIfCurrent(token)
                }
            }
        }
        if (operationToken === token) {
            job = launchedJob
        }
    }

    private fun clearIfCurrent(token: Any) {
        if (operationToken === token) {
            operationToken = null
            job = null
        }
    }
}

internal fun isVoiceAgentStartupTerminal(
    startGeneration: Long,
    currentGeneration: Long,
    session: VoiceSessionStatus,
): Boolean = startGeneration != currentGeneration ||
    session == VoiceSessionStatus.Connected ||
    session is VoiceSessionStatus.Error ||
    session == VoiceSessionStatus.Ended
