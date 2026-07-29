package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal const val VOICE_AGENT_END_DRAIN_TIMEOUT_MS = 15_000L

internal interface VoiceAgentCallServiceLifecycleHost {
    fun cancelNotification()
    fun startForeground(
        conversationId: String,
        transport: VoiceAgentTransport,
        state: VoiceAgentUiState,
    )
    fun endCompleted(conversationId: Uuid?)
    fun stopForeground()
    fun stopSelf()
    fun reportFailure(error: Throwable)
    fun destroyBaseService()
}

internal class VoiceAgentCallServiceLifecycle(
    private val controller: VoiceAgentCallServiceController,
    private val serviceScope: CoroutineScope,
    private val host: VoiceAgentCallServiceLifecycleHost,
) {
    private val endJobTracker = VoiceAgentEndJobTracker()
    private var configurationJob: Job? = null
    private var configurationToken: Any? = null
    private var notificationJob: Job? = null
    private var closeControllerOnDestroy = true
    private var notificationTransport = VoiceAgentTransport.DirectGemini

    var currentGeneration: Long = 0L
        private set

    fun beginStart(
        conversationId: Uuid,
        transport: VoiceAgentTransport = VoiceAgentTransport.DirectGemini,
    ): Long {
        currentGeneration += 1
        notificationTransport = transport
        closeControllerOnDestroy = true
        configurationJob?.cancel()
        configurationJob = null
        configurationToken = null
        endJobTracker.clearTracking()
        notificationJob?.cancel()
        notificationJob = null
        host.cancelNotification()
        val currentState = controller.state.value
        val activeIdentity = controller.activeIdentity.value
        val foregroundState = if (
            activeIdentity?.conversationId == conversationId &&
            currentState.call is VoiceCallStatus.Degraded
        ) {
            currentState
        } else {
            currentState.copy(call = VoiceCallStatus.ForegroundStarting)
        }
        host.startForeground(
            conversationId.toString(),
            notificationTransport,
            foregroundState,
        )
        return currentGeneration
    }

    fun isCurrent(generation: Long): Boolean = generation == currentGeneration

    fun launchStartConfiguration(
        generation: Long,
        conversationId: Uuid,
        resolveRequest: suspend () -> VoiceAgentCallRequest,
    ) {
        configurationJob?.cancel()
        val token = Any()
        configurationToken = token
        configurationJob = serviceScope.launch {
            var submitted = false
            try {
                val request = resolveRequest()
                if (!isCurrent(generation)) return@launch
                clearConfigurationTracking(token)
                submitted = true
                val result = controller.start(request)
                if (!isCurrent(generation)) return@launch
                when (result) {
                    is VoiceAgentCallStartResult.Active -> {
                        observeActiveCall(
                            generation,
                            activeResultIdentity(request),
                        )
                    }
                    VoiceAgentCallStartResult.Superseded -> Unit
                    is VoiceAgentCallStartResult.Failed -> stopFailedStartIfCurrent(
                        generation = generation,
                        conversationId = conversationId,
                        error = result.error,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                stopFailedStartIfCurrent(
                    generation = generation,
                    conversationId = conversationId,
                    error = error,
                    skipControllerCloseOnDestroy = !submitted,
                )
            } finally {
                clearConfigurationTracking(token)
            }
        }
    }

    fun rejectInvalidStart(error: Throwable) {
        if (hasHostedCall()) {
            reportFailureSafely(error)
        } else {
            currentGeneration += 1
            configurationJob?.cancel()
            configurationJob = null
            configurationToken = null
            if (controller.lifecycle.value == VoiceAgentCallLifecycle.Idle) {
                closeControllerOnDestroy = false
            }
            runCatching {
                runVoiceAgentCleanupStages(
                    { host.reportFailure(error) },
                    host::stopSelf,
                )
            }.exceptionOrNull()?.let(::reportFailureSafely)
        }
    }

    fun endCall(): Boolean {
        if (endJobTracker.job?.isActive == true) return false

        configurationJob?.cancel()
        configurationJob = null
        configurationToken = null
        notificationJob?.cancel()
        notificationJob = null
        host.cancelNotification()
        val endingIdentity = controller.activeIdentity.value
        host.startForeground(
            endingIdentity?.conversationId?.toString() ?: FALLBACK_END_NOTIFICATION_CONVERSATION_ID,
            endingIdentity?.transport ?: notificationTransport,
            controller.state.value.copy(call = VoiceCallStatus.Ending),
        )
        currentGeneration += 1
        val endGeneration = currentGeneration
        endJobTracker.launch(serviceScope) {
            val result = controller.end()
            if (!isCurrent(endGeneration)) return@launch
            if (result is VoiceAgentCallEndResult.Failed) {
                reportFailureSafely(result.error)
            }
            val hostFailure = runCatching {
                runVoiceAgentCleanupStages(
                    { host.endCompleted(endingIdentity?.conversationId) },
                    host::stopForeground,
                    host::stopSelf,
                )
            }.exceptionOrNull()
            hostFailure?.let(::reportFailureSafely)
        }
        return true
    }

    fun destroy() {
        currentGeneration += 1
        runVoiceAgentCleanupStages(
            { if (closeControllerOnDestroy) controller.closeNow() },
            host::cancelNotification,
            { configurationJob?.cancel() },
            { notificationJob?.cancel() },
            endJobTracker::clearTracking,
            serviceScope::cancel,
            host::destroyBaseService,
        )
    }

    private fun stopFailedStartIfCurrent(
        generation: Long,
        conversationId: Uuid,
        error: Throwable,
        skipControllerCloseOnDestroy: Boolean = false,
    ) {
        if (!isCurrent(generation)) return
        val hostedIdentity = hostedIdentityOrNull(conversationId)
        if (hostedIdentity != null) {
            reportFailureSafely(error)
            observeActiveCall(
                generation = generation,
                identity = hostedIdentity,
            )
            return
        }
        if (
            skipControllerCloseOnDestroy &&
            controller.lifecycle.value == VoiceAgentCallLifecycle.Idle
        ) {
            closeControllerOnDestroy = false
        }
        val detail = error.toVoiceAgentLogDetail()
        val hostFailure = runCatching {
            runVoiceAgentCleanupStages(
                host::cancelNotification,
                {
                    host.startForeground(
                        conversationId.toString(),
                        notificationTransport,
                        controller.state.value.copy(
                            call = VoiceCallStatus.Degraded("Voice call startup failed: $detail"),
                        ),
                    )
                },
                { host.reportFailure(error) },
                host::stopForeground,
                host::stopSelf,
            )
        }.exceptionOrNull()
        if (hostFailure != null && hostFailure !== error) {
            reportFailureSafely(hostFailure)
        }
    }

    private fun observeActiveCall(
        generation: Long,
        identity: ActiveVoiceAgentIdentity,
    ) {
        if (!isCurrent(generation)) return
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            coroutineScope {
                launch {
                    controller.state.collect { state ->
                        if (isCurrent(generation)) {
                            host.startForeground(identity.conversationId.toString(), identity.transport, state)
                        }
                    }
                }
                launch {
                    controller.lifecycle.collect { lifecycle ->
                        when (lifecycle) {
                            VoiceAgentCallLifecycle.Idle -> stopAutonomousIfCurrent(generation)
                            is VoiceAgentCallLifecycle.Starting,
                            is VoiceAgentCallLifecycle.Active,
                            is VoiceAgentCallLifecycle.Stopping,
                            -> Unit
                            is VoiceAgentCallLifecycle.CleanupFailed -> {
                                reportFailureSafely(lifecycle.error)
                                stopAutonomousIfCurrent(generation)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopAutonomousIfCurrent(generation: Long) {
        if (!isCurrent(generation)) return
        runCatching {
            runVoiceAgentCleanupStages(
                host::cancelNotification,
                host::stopForeground,
                host::stopSelf,
            )
        }.exceptionOrNull()?.let(::reportFailureSafely)
    }

    private fun reportFailureSafely(error: Throwable) {
        runCatching { host.reportFailure(error) }
    }

    private fun clearConfigurationTracking(token: Any) {
        if (configurationToken === token) {
            configurationToken = null
            configurationJob = null
        }
    }

    private fun hasHostedCall(): Boolean =
        controller.activeIdentity.value != null || when (controller.lifecycle.value) {
            is VoiceAgentCallLifecycle.Starting,
            is VoiceAgentCallLifecycle.Active,
            is VoiceAgentCallLifecycle.Stopping,
            -> true
            VoiceAgentCallLifecycle.Idle,
            is VoiceAgentCallLifecycle.CleanupFailed,
            -> false
        }

    private fun activeResultIdentity(request: VoiceAgentCallRequest): ActiveVoiceAgentIdentity {
        controller.activeIdentity.value?.let { return it }
        check(controller.lifecycle.value !is VoiceAgentCallLifecycle.Active) {
            "Active Voice Agent lifecycle must expose its complete identity"
        }
        return ActiveVoiceAgentIdentity(request.conversationId, request.transport)
    }

    private fun hostedIdentityOrNull(fallbackConversationId: Uuid): ActiveVoiceAgentIdentity? {
        controller.activeIdentity.value?.let { return it }
        val conversationId = when (val lifecycle = controller.lifecycle.value) {
            is VoiceAgentCallLifecycle.Starting -> lifecycle.conversationId
            is VoiceAgentCallLifecycle.Active -> error("Active Voice Agent lifecycle must expose its complete identity")
            is VoiceAgentCallLifecycle.Stopping -> lifecycle.conversationId ?: fallbackConversationId
            VoiceAgentCallLifecycle.Idle,
            is VoiceAgentCallLifecycle.CleanupFailed,
            -> return null
        }
        return ActiveVoiceAgentIdentity(conversationId, notificationTransport)
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
            try {
                block()
            } finally {
                clearIfCurrent(token)
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
