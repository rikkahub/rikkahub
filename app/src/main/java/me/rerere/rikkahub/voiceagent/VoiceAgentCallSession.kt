package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.hermes.HermesSessionBridge
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import java.util.Base64

class VoiceAgentCallSession internal constructor(
    private val modelId: String,
    private val sessionApi: VoiceSessionApi,
    private val toolApi: VoiceToolApi,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    private val conversationStore: VoiceConversationStore,
    private val contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val observability: VoiceObservability = NoOpVoiceObservability,
    private val traceContext: VoiceTraceContext = newVoiceTraceContext(),
    private val voiceE2EArtifacts: VoiceE2EArtifactWriter = VoiceE2EArtifactWriter.disabled(),
    private var sessionMetadata: VoiceE2ESessionMetadata? = null,
    private val reconnectPolicy: VoiceReconnectPolicy = VoiceReconnectPolicy(),
    private val nowMs: () -> Long = ::defaultReconnectClockMs,
    private val metadataEpochNowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
) : ManagedVoiceCallSession {
    constructor(
        modelId: String,
        sessionApi: VoiceSessionApi,
        toolApi: VoiceToolApi,
        gemini: GeminiLiveVoiceClient,
        audio: VoiceAudioEngine,
        conversationStore: VoiceConversationStore,
        contextProvider: VoiceAgentContextProvider,
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
        voiceE2EArtifacts: VoiceE2EArtifactWriter = VoiceE2EArtifactWriter.disabled(),
        sessionMetadata: VoiceE2ESessionMetadata? = null,
        scope: CoroutineScope,
    ) : this(
        modelId = modelId,
        sessionApi = sessionApi,
        toolApi = toolApi,
        gemini = gemini,
        audio = audio,
        conversationStore = conversationStore,
        contextProvider = contextProvider,
        diagnostics = diagnostics,
        observability = NoOpVoiceObservability,
        traceContext = newVoiceTraceContext(),
        voiceE2EArtifacts = voiceE2EArtifacts,
        sessionMetadata = sessionMetadata,
        reconnectPolicy = VoiceReconnectPolicy(),
        nowMs = ::defaultReconnectClockMs,
        metadataEpochNowMs = System::currentTimeMillis,
        scope = scope,
    )

    private val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = audio,
        diagnostics = diagnostics,
        observability = observability,
        traceContext = traceContext,
        conversationStore = conversationStore,
        writeVoiceE2EArtifact = voiceE2EArtifacts::write,
        scope = scope,
    )
    private val resourceCleaner = VoiceSessionResourceCleaner(
        coordinator = coordinator,
        gemini = gemini,
        audio = audio,
        hermesBridgeProvider = { hermesBridge },
        clearHermesBridge = { hermesBridge = null },
    )
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false
    private var sessionEndedRecorded = false
    private var sessionFailedRecordedSessionId: Long? = null
    private var runtimeFailureTelemetrySessionId: Long? = null
    private var hermesBridge: HermesSessionBridge? = null
    private val sessionLock = Any()
    private val reconnectController = VoiceReconnectController(
        policy = reconnectPolicy,
        nowMs = nowMs,
    )

    override val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation

    override fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        VoiceAgentLog.d(TAG, "start sessionId=$currentSessionId modelId=$modelId")
        writeSessionMetadata(status = "started")
        recordEventSafely(
            name = "voicelab.mobile.session.started",
            attributes = mapOf(
                "sessionId" to currentSessionId,
                "modelId" to modelId,
            ),
        )
        val job = scope.launch {
            runSession(currentSessionId)
        }
        startJob = job
    }

    override fun interrupt() {
        if (!ended) {
            coordinator.suppressPlayback()
        }
    }

    override fun setMuted(value: Boolean) {
        if (ended || muted == value) return
        muted = value
        if (muted) {
            gemini.sendAudioStreamEnd(sessionId)
            audio.stopCapture()
            coordinator.updateAudioStatus(VoiceAudioStatus.Muted)
        } else if (state.value.session == VoiceSessionStatus.Connected) {
            startCapture(sessionId)
        }
        recordEventSafely(
            name = if (muted) {
                "voicelab.mobile.audio.capture_muted"
            } else {
                "voicelab.mobile.audio.capture_unmuted"
            },
            attributes = mapOf("sessionId" to sessionId),
        )
    }

    override fun reconnect() {
        if (ended) return
        val automaticReconnectJob = resetAutomaticReconnectForManualReconnect()
        val previousJob = prepareManualReconnect()
        startJob = startReconnectedSession(previousJob ?: automaticReconnectJob)
    }

    override fun end() {
        endWithVisibleReason(visibleReason = null)
    }

    override suspend fun endAndDrain() {
        val preparation = beginEnd() ?: return
        finishEnd(previousJob = preparation.previousJob, visibleReason = null)
    }

    override fun recordDiagnostic(name: String, detail: String) {
        coordinator.recordDiagnostic(name = name, detail = detail)
    }

    override fun closeNow() {
        markEnded()
        cancelAutomaticReconnect(reason = "close")
        startJob?.cancel()
        resourceCleaner.cleanupForEnd(closeGemini = false)
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close(waitForStartedSends = false)
        recordSessionEndedSafely(endReason = "close_now", writeSessionMetadataImmediately = true)
        coordinator.launchPersistenceDrain()
    }

    private suspend fun runSession(
        currentSessionId: Long,
        automaticReconnectJob: Job? = null,
    ) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val sessionJob = coroutineContext[Job]
        startJob = sessionJob
        clearReconnectEligibility()
        var closeGeminiOnStartupFailure = false
        try {
            val voiceContext = prepareVoiceContext(
                currentSessionId = currentSessionId,
                automaticReconnectJob = automaticReconnectJob,
            ) ?: return
            val session = createVoiceSession(
                currentSessionId = currentSessionId,
                automaticReconnectJob = automaticReconnectJob,
            ) ?: return
            if (!updateSessionStatusIfActive(
                    sessionId = currentSessionId,
                    status = VoiceSessionStatus.ConnectingGemini,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
            }
            closeGeminiOnStartupFailure = true
            if (!connectGeminiSession(
                    currentSessionId = currentSessionId,
                    automaticReconnectJob = automaticReconnectJob,
                    session = session,
                    voiceContext = voiceContext,
                )
            ) {
                return
            }
            if (!prepareConnectedResourceActivation(
                    currentSessionId = currentSessionId,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
            }
            if (!activateConnectedResources(
                    currentSessionId = currentSessionId,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
            }
            if (!finishConnectedSessionCommit(
                    currentSessionId = currentSessionId,
                    automaticReconnectJob = automaticReconnectJob,
                    providerModel = session.providerModel,
                )
            ) {
                return
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isSessionOpenAndActive(currentSessionId)) {
                VoiceAgentLog.w(
                    TAG,
                    "run session failed sessionId=$currentSessionId detail=${error.toVoiceAgentLogDetail()}",
                )
                val startupErrorMessage = error.message ?: error.javaClass.simpleName
                reconnectController.cancel()
                recordAndPrepareStartupFailure(
                    sessionId = currentSessionId,
                    message = startupErrorMessage,
                    closeGemini = closeGeminiOnStartupFailure,
                )
                clearReconnectEligibility()
                updateSessionStatusIfNotEnded(VoiceSessionStatus.Error(startupErrorMessage))
            }
        } finally {
            if (startJob === sessionJob) {
                startJob = null
            }
        }
    }

    private suspend fun prepareVoiceContext(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
    ): VoiceContext? {
        if (!updateSessionStatusIfActive(
                sessionId = currentSessionId,
                status = VoiceSessionStatus.PreparingContext,
                automaticReconnectJob = automaticReconnectJob,
            )
        ) {
            return null
        }
        VoiceAgentLog.d(TAG, "preparing context sessionId=$currentSessionId")
        val voiceContext = contextProvider.build(conversation.value).withTurnsFoldedIntoSystemInstruction()
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        VoiceAgentLog.d(
            TAG,
            "context prepared sessionId=$currentSessionId turns=${voiceContext.turns.size} " +
                "systemInstructionChars=${voiceContext.systemInstruction.length}",
        )
        coordinator.recordDiagnostic(
            name = "voice_context_prepared",
            detail = "turns=${voiceContext.turns.size}, systemInstructionChars=${voiceContext.systemInstruction.length}",
        )
        return voiceContext
    }

    private suspend fun createVoiceSession(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
    ): MobileVoiceSessionResponse? {
        if (!updateSessionStatusIfActive(
                sessionId = currentSessionId,
                status = VoiceSessionStatus.RequestingToken,
                automaticReconnectJob = automaticReconnectJob,
            )
        ) {
            return null
        }
        VoiceAgentLog.d(TAG, "requesting voice session sessionId=$currentSessionId modelId=$modelId")
        val session = sessionApi.createSession(modelId = modelId)
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        VoiceAgentLog.d(
            TAG,
            "voice session created sessionId=$currentSessionId modelId=${session.modelId} " +
                "providerModel=${session.providerModel} inputSampleRate=${session.inputSampleRate} " +
                "outputSampleRate=${session.outputSampleRate}",
        )
        coordinator.recordDiagnostic(
            name = "voice_session_created",
            detail = "modelId=${session.modelId}, providerModel=${session.providerModel}, " +
                "inputSampleRate=${session.inputSampleRate}, outputSampleRate=${session.outputSampleRate}",
        )
        return session
    }

    private suspend fun connectGeminiSession(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
        session: MobileVoiceSessionResponse,
        voiceContext: VoiceContext,
    ): Boolean {
        VoiceAgentLog.d(
            TAG,
            "connecting Gemini sessionId=$currentSessionId providerModel=${session.providerModel}",
        )
        gemini.connect(
            token = session.token,
            websocketUrl = session.websocketUrl,
            providerModel = session.providerModel,
            liveConnectConfig = session.liveConnectConfig,
            systemInstruction = voiceContext.systemInstruction,
            contextTurns = voiceContext.turns,
            onEvent = { event -> handleGeminiEvent(currentSessionId, event) },
        )
        VoiceAgentLog.d(TAG, "Gemini connect returned sessionId=$currentSessionId")
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        if (coordinator.state.value.session is VoiceSessionStatus.Error) {
            VoiceAgentLog.w(TAG, "Gemini connect returned with error state sessionId=$currentSessionId")
            val startupErrorMessage = (coordinator.state.value.session as? VoiceSessionStatus.Error)?.message
                ?: "Gemini connect failed"
            recordAndPrepareStartupFailure(
                sessionId = currentSessionId,
                message = startupErrorMessage,
                closeGemini = true,
            )
            return false
        }
        return true
    }

    private fun recordAndPrepareStartupFailure(
        sessionId: Long,
        message: String,
        closeGemini: Boolean,
    ) {
        recordSessionFailedSafely(
            sessionId = sessionId,
            endReason = VoiceSessionStopReason.StartupFailure.diagnosticReason,
            failureKind = "startup",
            failureSummary = message,
        )
        prepareFailedSession(
            sessionId = sessionId,
            reason = VoiceSessionStopReason.StartupFailure,
            closeGemini = closeGemini,
        )
    }

    private suspend fun prepareConnectedResourceActivation(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean {
        if (!markReconnectEligible(currentSessionId, automaticReconnectJob)) return false
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        if (!coordinator.isActiveSession(currentSessionId)) {
            restoreReconnectingStatusIfAutomaticReconnectPending()
            return false
        }
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        if (!reserveConnectedResourceActivation(currentSessionId)) {
            restoreReconnectingStatusIfAutomaticReconnectPending()
            return false
        }
        ensureActiveSession(currentSessionId, automaticReconnectJob)
        consumePendingActivationReconnect(currentSessionId)?.let { pending ->
            activatePendingReconnect(pending)
            return false
        }
        return true
    }

    private suspend fun activateConnectedResources(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean {
        try {
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            gemini.activateOutboundSession(currentSessionId)
            if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            val bridge = coordinator.createHermesSessionBridge(currentSessionId)
            hermesBridge = bridge
            if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            coordinator.attachHermesBridge(bridge = bridge, sessionId = currentSessionId)
            if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            coordinator.resumeHermesJobs()
            if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            audio.activatePlaybackSession(currentSessionId)
            if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            if (!muted) {
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                startCapture(currentSessionId)
                if (consumePendingActivationReconnectIfAny(currentSessionId)) return false
            }
        } catch (error: Throwable) {
            takePendingActivationReconnect(currentSessionId)?.let { pending ->
                activatePendingReconnect(pending)
                return false
            }
            throw error
        }
        takePendingActivationReconnect(currentSessionId)?.let { pending ->
            activatePendingReconnect(pending)
            return false
        }
        return true
    }

    private fun finishConnectedSessionCommit(
        currentSessionId: Long,
        automaticReconnectJob: Job?,
        providerModel: String,
    ): Boolean {
        var connectedSessionStale = false
        var restoreReconnectingAfterCommit = false
        val completedReconnectAttempt = synchronized(sessionLock) {
            if (!coordinator.isActiveSession(currentSessionId)) {
                connectedSessionStale = true
                restoreReconnectingAfterCommit = reconnectController.hasPendingReconnect()
                null
            } else {
                val reconnectJobIsCurrent =
                    automaticReconnectJob?.let(reconnectController::isCurrentJob) == true
                if (reconnectJobIsCurrent) {
                    runtimeFailureTelemetrySessionId = currentSessionId
                }
                automaticReconnectJob
                    ?.takeIf { reconnectJobIsCurrent }
                    ?.let(reconnectController::completeAttempt)
            }
        }
        if (connectedSessionStale) {
            if (restoreReconnectingAfterCommit && !ended) {
                updateSessionStatusIfNotEnded(VoiceSessionStatus.Reconnecting)
            }
            return false
        }
        if (!markConnectedIfActive(currentSessionId)) return false
        if (!coordinator.isActiveSession(currentSessionId)) {
            restoreReconnectingStatusIfAutomaticReconnectPending()
            return false
        }
        completedReconnectAttempt?.let { attempt ->
            coordinator.recordDiagnostic(
                name = "session_reconnect_connected",
                detail = "attempt=$attempt",
            )
        }
        VoiceAgentLog.d(TAG, "session connected sessionId=$currentSessionId")
        writeSessionMetadata(status = "connected", providerModel = providerModel)
        recordEventSafely(
            name = "voicelab.mobile.session.connected",
            attributes = mapOf(
                "sessionId" to currentSessionId,
                "modelId" to modelId,
                "providerModel" to providerModel,
            ),
        )
        return true
    }

    private fun reserveConnectedResourceActivation(sessionId: Long): Boolean = synchronized(sessionLock) {
        coordinator.isActiveSession(sessionId) && reconnectController.reserveActivation(sessionId)
    }

    private fun consumePendingActivationReconnect(sessionId: Long): AutomaticReconnectPlan? =
        reconnectController.consumePendingActivation(sessionId)

    private fun takePendingActivationReconnect(sessionId: Long): AutomaticReconnectPlan? =
        reconnectController.finishActivation(sessionId)

    private fun activatePendingReconnect(plan: AutomaticReconnectPlan) {
        scheduleAutomaticReconnect(
            plan = plan,
            cleanupResources = true,
        )
    }

    private fun consumePendingActivationReconnectIfAny(sessionId: Long): Boolean {
        val pending = consumePendingActivationReconnect(sessionId) ?: return false
        activatePendingReconnect(pending)
        return true
    }

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        val coordinatorEvent = event.withoutSetupCompleteForCoordinator() ?: return
        val stopReason = coordinatorEvent.toSessionStopReason()
        if (stopReason != null) {
            VoiceAgentLog.w(
                TAG,
                "Gemini failure event sessionId=$sessionId event=${coordinatorEvent::class.simpleName}",
            )
        }
        val automaticReconnectAttemptFailure =
            stopReason != null && reconnectController.isAutomaticReconnectSession(sessionId)
        if (stopReason != null && scheduleAutomaticReconnectIfEligible(sessionId, coordinatorEvent, stopReason)) {
            return
        }
        coordinator.onGeminiEvent(sessionId, coordinatorEvent)
        if (stopReason != null) {
            if (coordinator.isActiveSession(sessionId)) {
                val runtimeFailure = isRuntimeFailureTelemetryEligible(sessionId) || automaticReconnectAttemptFailure
                recordSessionFailedSafely(
                    sessionId = sessionId,
                    endReason = if (runtimeFailure) {
                        stopReason.diagnosticReason
                    } else {
                        VoiceSessionStopReason.StartupFailure.diagnosticReason
                    },
                    failureKind = if (runtimeFailure) stopReason.diagnosticReason else "startup",
                    failureSummary = coordinatorEvent.failureSummary(),
                )
            }
            prepareFailedSession(sessionId = sessionId, reason = stopReason, closeGemini = true)
        }
    }

    private fun GeminiLiveEvent.withoutSetupCompleteForCoordinator(): GeminiLiveEvent? =
        when (this) {
            GeminiLiveEvent.SetupComplete -> {
                coordinator.recordSetupComplete()
                null
            }
            is GeminiLiveEvent.Events -> {
                val filteredEvents = events.mapNotNull { it.withoutSetupCompleteForCoordinator() }
                if (filteredEvents.isEmpty()) {
                    null
                } else {
                    copy(events = filteredEvents)
                }
            }
            else -> this
        }

    private fun scheduleAutomaticReconnectIfEligible(
        failedSessionId: Long,
        event: GeminiLiveEvent,
        reason: VoiceSessionStopReason,
    ): Boolean {
        if (ended || !coordinator.isActiveSession(failedSessionId)) return false
        return when (
            val decision = reconnectController.planReconnect(
                failedSessionId = failedSessionId,
                event = event,
                reason = reason,
            )
        ) {
            VoiceReconnectDecision.Ignore -> false
            VoiceReconnectDecision.AlreadyPlanned -> true
            VoiceReconnectDecision.DeferredForActivation -> true
            is VoiceReconnectDecision.Exhausted -> {
                coordinator.recordDiagnostic(
                    name = "session_reconnect_exhausted",
                    detail = "reason=${decision.reason.diagnosticReason}, attempts=${decision.attempts}, " +
                        "elapsedMs=${decision.elapsedMs}",
                )
                false
            }
            is VoiceReconnectDecision.Schedule -> {
                scheduleAutomaticReconnect(
                    plan = decision.plan,
                    cleanupResources = true,
                )
                true
            }
        }
    }

    private fun scheduleAutomaticReconnect(
        plan: AutomaticReconnectPlan,
        cleanupResources: Boolean,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(plan.delayMs)
            val job = currentCoroutineContext()[Job] ?: return@launch
            val currentSessionId: Long
            synchronized(sessionLock) {
                if (ended) return@launch
                currentSessionId = reconnectController.beginAttempt(job = job) {
                    coordinator.nextSessionId().also { allocatedSessionId ->
                        sessionId = allocatedSessionId
                    }
                } ?: return@launch
            }
            currentCoroutineContext().ensureActive()
            synchronized(sessionLock) {
                if (ended) return@launch
            }
            coordinator.recordDiagnostic(
                name = "session_reconnect_attempting",
                detail = "attempt=${plan.attempt}",
            )
            currentCoroutineContext().ensureActive()
            runSession(currentSessionId, automaticReconnectJob = job)
        }
        if (!reconnectController.setScheduled(plan = plan, job = job)) {
            job.cancel()
            return
        }
        val scheduleStillCurrent = synchronized(sessionLock) {
            if (ended || !reconnectController.isCurrentJob(job)) {
                false
            } else {
                coordinator.prepareForAutomaticReconnect()
                startJob = job
                true
            }
        }
        if (!scheduleStillCurrent) {
            job.cancel()
            return
        }
        if (cleanupResources) {
            if (!cleanupAutomaticReconnectResources(job)) {
                return
            }
        } else if (!isAutomaticReconnectCurrent(job)) {
            return
        }
        if (!updateAutomaticReconnectStatusIfCurrent(job)) {
            job.cancel()
            if (reconnectController.isCurrentJob(job)) {
                reconnectController.cancel()
            }
            synchronized(sessionLock) {
                if (startJob === job) {
                    startJob = null
                }
            }
            return
        }
        if (!isAutomaticReconnectCurrent(job)) return
        recordRetryableTransportDiagnostic(plan.event)
        coordinator.recordDiagnostic(
            name = "session_reconnect_scheduled",
            detail = "reason=${plan.reason.diagnosticReason}, attempt=${plan.attempt}, " +
                "maxAttempts=${reconnectPolicy.maxAttempts}, delayMs=${plan.delayMs}",
        )
        recordEventSafely(
            name = "voicelab.mobile.session.reconnect_scheduled",
            attributes = mapOf(
                "sessionId" to sessionId,
                "session.reconnect.reason" to plan.reason.diagnosticReason,
                "session.reconnect.attempt" to plan.attempt,
                "session.reconnect.delay_ms" to plan.delayMs,
            ),
        )
        if (!isAutomaticReconnectCurrent(job)) return
        job.start()
    }

    private fun resetAutomaticReconnectForManualReconnect(): Job? {
        val cancellation = clearAutomaticReconnectState()
        if (cancellation.hadAutomaticReconnect) {
            coordinator.recordDiagnostic(
                name = "session_reconnect_reset",
                detail = "reason=manual_reconnect",
            )
        }
        return cancellation.job
    }

    private fun cancelAutomaticReconnect(reason: String): Job? {
        val cancellation = clearAutomaticReconnectState()
        if (cancellation.hadAutomaticReconnect) {
            coordinator.recordDiagnostic(
                name = "session_reconnect_cancelled",
                detail = "reason=$reason",
            )
        }
        return cancellation.job
    }

    private fun clearAutomaticReconnectState(): VoiceReconnectCancellation =
        synchronized(sessionLock) {
            reconnectController.cancel().also { cancellation ->
                if (cancellation.job != null && startJob === cancellation.job) {
                    startJob = null
                }
            }
        }

    private fun restoreReconnectingStatusIfAutomaticReconnectPending() {
        if (reconnectController.hasPendingReconnect() && !ended) {
            updateSessionStatusIfNotEnded(VoiceSessionStatus.Reconnecting)
        }
    }

    private fun updateSessionStatusIfActive(
        sessionId: Long,
        status: VoiceSessionStatus,
        automaticReconnectJob: Job? = null,
    ): Boolean = synchronized(sessionLock) {
        if (!isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)) {
            false
        } else {
            coordinator.updateSessionStatus(status)
            true
        }
    }

    private fun updateSessionStatusIfNotEnded(status: VoiceSessionStatus): Boolean =
        synchronized(sessionLock) {
            if (ended) {
                false
            } else {
                coordinator.updateSessionStatus(status)
                true
            }
        }

    private fun markConnectedIfActive(sessionId: Long): Boolean = synchronized(sessionLock) {
        if (!isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob = null)) {
            false
        } else {
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            runtimeFailureTelemetrySessionId = sessionId
            true
        }
    }

    private fun updateAutomaticReconnectStatusIfCurrent(job: Job): Boolean =
        synchronized(sessionLock) {
            if (ended || !reconnectController.isCurrentJob(job)) {
                false
            } else {
                coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
                true
            }
        }

    private fun isSessionOpenAndActive(
        sessionId: Long,
        automaticReconnectJob: Job? = null,
    ): Boolean =
        synchronized(sessionLock) {
            isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)
        }

    private fun isSessionOpenAndActiveLocked(
        sessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean {
        return !ended &&
            coordinator.isActiveSession(sessionId) &&
            (automaticReconnectJob == null || reconnectController.isCurrentJob(automaticReconnectJob))
    }

    private fun isAutomaticReconnectCurrent(job: Job): Boolean =
        !ended && reconnectController.isCurrentJob(job)

    private fun clearReconnectEligibility() {
        reconnectController.clearEligibility()
    }

    private fun markReconnectEligible(
        sessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean = synchronized(sessionLock) {
        if (!isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)) {
            false
        } else {
            reconnectController.markEligible(sessionId)
        }
    }

    private fun isRuntimeFailureTelemetryEligible(sessionId: Long): Boolean = synchronized(sessionLock) {
        runtimeFailureTelemetrySessionId == sessionId
    }

    private fun markEnded(): Boolean =
        synchronized(sessionLock) {
            if (ended) {
                false
            } else {
                ended = true
                true
            }
        }

    private fun cleanupAutomaticReconnectResources(job: Job): Boolean {
        return resourceCleaner.cleanupForAutomaticReconnect(
            closeGemini = true,
            isAutomaticReconnectCurrentUnderCleanupLock = { isAutomaticReconnectCurrent(job) },
        )
    }

    private fun prepareFailedSession(sessionId: Long, reason: VoiceSessionStopReason, closeGemini: Boolean) {
        if (!coordinator.isActiveSession(sessionId)) return
        VoiceAgentLog.d(
            TAG,
            "prepare failed session sessionId=$sessionId reason=${reason.diagnosticReason} closeGemini=$closeGemini",
        )
        coordinator.recordDiagnostic(
            name = "session_transition_failed",
            detail = "reason=${reason.diagnosticReason}, closeGemini=$closeGemini",
        )
        resourceCleaner.cleanupForFailure(closeGemini = closeGemini)
    }

    private fun prepareManualReconnect(): Job? {
        val previousJob = startJob
        coordinator.recordDiagnostic(
            name = "session_transition_manual_reconnect",
            detail = "reason=${VoiceSessionStopReason.ManualReconnect.diagnosticReason}",
        )
        resourceCleaner.cleanupForReconnect(closeGemini = true)
        return previousJob
    }

    private fun startReconnectedSession(previousJob: Job?): Job = scope.launch {
        previousJob?.cancelAndJoin()
        val currentSessionId = synchronized(sessionLock) {
            if (!ended) {
                coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
                coordinator.nextSessionId().also { sessionId = it }
            } else {
                null
            }
        } ?: return@launch
        writeSessionMetadata(status = "started")
        runSession(currentSessionId)
    }

    private fun endWithVisibleReason(visibleReason: String?) {
        val preparation = beginEnd() ?: return
        scope.launch {
            finishEnd(previousJob = preparation.previousJob, visibleReason = visibleReason)
        }
    }

    private fun beginEnd(): EndPreparation? {
        if (!markEnded()) return null
        val automaticReconnectJob = cancelAutomaticReconnect(reason = "end")
        val previousJob = startJob ?: automaticReconnectJob
        resourceCleaner.cleanupForEnd(closeGemini = true)
        return EndPreparation(previousJob = previousJob)
    }

    private suspend fun finishEnd(previousJob: Job?, visibleReason: String?) {
        var terminalSessionMetadataWrite = completedSessionMetadataWrite()
        try {
            previousJob?.cancelAndJoin()
            coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
            coordinator.close()
        } finally {
            terminalSessionMetadataWrite = recordSessionEndedSafely(endReason = visibleReason ?: "user_end")
        }
        visibleReason?.let(coordinator::setVisibleError)
        coordinator.awaitPersistenceJobs()
        voiceE2EArtifacts.drain()
        voiceE2EArtifacts.drainTerminalWrites()
        terminalSessionMetadataWrite.await()
        coordinator.stopPersistenceScope()
    }

    private suspend fun ensureActiveSession(
        sessionId: Long,
        automaticReconnectJob: Job? = null,
    ) {
        currentCoroutineContext().ensureActive()
        check(isSessionOpenAndActive(sessionId, automaticReconnectJob)) { "Voice Agent session is stale" }
    }

    private fun recordRetryableTransportDiagnostic(event: GeminiLiveEvent) {
        when (event) {
            is GeminiLiveEvent.WebSocketClosed -> coordinator.recordDiagnostic(
                name = "gemini_ws_closed",
                detail = "code=${event.code}, reason=${event.reason}",
            )
            is GeminiLiveEvent.WebSocketFailure -> coordinator.recordDiagnostic(
                name = "gemini_ws_failure",
                detail = event.message,
            )
            else -> Unit
        }
    }

    private fun startCapture(currentSessionId: Long) {
        VoiceAgentLog.d(TAG, "starting audio capture sessionId=$currentSessionId muted=$muted")
        audio.startCapture(
            onPcm16 = { pcm16 ->
                if (!coordinator.isActiveSession(currentSessionId)) {
                    return@startCapture
                }
                val sent = gemini.sendAudio(
                    base64Pcm16 = Base64.getEncoder().encodeToString(pcm16),
                    sessionId = currentSessionId,
                )
                if (sent && coordinator.isActiveSession(currentSessionId)) {
                    coordinator.updateAudioStatus(VoiceAudioStatus.UserSpeaking)
                }
            },
            onDebugInjectionComplete = {
                if (coordinator.isActiveSession(currentSessionId)) {
                    VoiceAgentLog.d(
                        TAG,
                        "debug injection complete; stopping capture and sending audio stream end " +
                            "sessionId=$currentSessionId",
                    )
                    audio.stopCapture()
                    gemini.sendAudioStreamEnd(currentSessionId)
                    coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
                }
            },
        )
        recordEventSafely(
            name = "voicelab.mobile.audio.capture_started",
            attributes = mapOf(
                "sessionId" to currentSessionId,
                "audio.muted" to muted,
            ),
        )
        if (isSessionOpenAndActive(currentSessionId)) {
            coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
        }
    }

    private data class EndPreparation(
        val previousJob: Job?,
    )

    private fun recordEventSafely(name: String, attributes: Map<String, Any?> = emptyMap()) {
        runCatching {
            observability.recordEvent(name = name, trace = traceContext, attributes = attributes)
        }
    }

    private fun recordSessionEndedSafely(
        endReason: String,
        failureKind: String = "none",
        failureSummary: String? = null,
        writeSessionMetadataImmediately: Boolean = false,
    ): Deferred<Unit> {
        val shouldRecord = synchronized(sessionLock) {
            if (sessionEndedRecorded) {
                false
            } else {
                sessionEndedRecorded = true
                true
            }
        }
        if (!shouldRecord) return completedSessionMetadataWrite()
        val metadataWrite = writeSessionMetadata(
            status = "ended",
            closeStatus = endReason,
            endedAtEpochMs = metadataEpochNowMs(),
            immediately = writeSessionMetadataImmediately,
        )
        recordEventSafely(
            name = "voicelab.mobile.session.ended",
            attributes = mapOf(
                "sessionId" to sessionId,
                "modelId" to modelId,
                "session.end_reason" to endReason,
                "session.failure.kind" to failureKind,
                "session.failure.summary" to failureSummary?.let(::sanitizeVoiceFailureSummary),
            ),
        )
        return metadataWrite
    }

    private fun recordSessionFailedSafely(
        sessionId: Long,
        endReason: String,
        failureKind: String,
        failureSummary: String,
    ): Deferred<Unit> {
        val shouldRecord = synchronized(sessionLock) {
            if (sessionFailedRecordedSessionId == sessionId) {
                false
            } else {
                sessionFailedRecordedSessionId = sessionId
                true
            }
        }
        if (!shouldRecord) return completedSessionMetadataWrite()
        val metadataWrite = writeSessionMetadata(
            status = "failed",
            closeStatus = endReason,
            endedAtEpochMs = metadataEpochNowMs(),
        )
        recordEventSafely(
            name = "voicelab.mobile.session.failed",
            attributes = mapOf(
                "sessionId" to sessionId,
                "modelId" to modelId,
                "session.end_reason" to endReason,
                "session.failure.kind" to failureKind,
                "session.failure.summary" to sanitizeVoiceFailureSummary(failureSummary),
            ),
        )
        return metadataWrite
    }

    private fun writeSessionMetadata(
        status: String,
        providerModel: String? = null,
        closeStatus: String? = null,
        endedAtEpochMs: Long? = null,
        immediately: Boolean = false,
    ): Deferred<Unit> {
        val content = synchronized(sessionLock) {
            val metadata = sessionMetadata ?: return completedSessionMetadataWrite()
            val updated = metadata.withLifecycleUpdate(
                status = status,
                providerModel = providerModel,
                closeStatus = closeStatus,
                endedAtEpochMs = endedAtEpochMs,
            )
            if (updated == metadata) return completedSessionMetadataWrite()
            sessionMetadata = updated
            updated.toJson()
        }
        return if (immediately || status.isTerminalSessionMetadataStatus()) {
            voiceE2EArtifacts.writeTerminalSessionJson(content)
        } else {
            voiceE2EArtifacts.write(VoiceE2EArtifact.SessionJson, content)
            completedSessionMetadataWrite()
        }
    }

    private companion object {
        const val TAG = "VoiceAgentCallSession"
    }
}

private fun completedSessionMetadataWrite(): Deferred<Unit> =
    CompletableDeferred(Unit)

private fun VoiceContext.withTurnsFoldedIntoSystemInstruction(): VoiceContext {
    if (turns.isEmpty()) return this

    val previousContext = turns.joinToString(separator = "\n\n") { turn ->
        "${turn.voiceContextLabel()}: ${turn.text}"
    }
    return copy(
        systemInstruction = listOf(
            systemInstruction,
            "Previous RikkaHub conversation context:\n$previousContext",
        )
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n"),
        turns = emptyList(),
    )
}

private fun String.isTerminalSessionMetadataStatus(): Boolean = this == "ended" || this == "failed"

private fun GeminiContentTurn.voiceContextLabel(): String =
    when (role) {
        "model" -> "Assistant"
        else -> "User"
    }

private fun GeminiLiveEvent.toSessionStopReason(): VoiceSessionStopReason? =
    when (this) {
        is GeminiLiveEvent.Error -> VoiceSessionStopReason.GeminiError
        is GeminiLiveEvent.WebSocketClosed -> VoiceSessionStopReason.WebSocketClosed
        is GeminiLiveEvent.WebSocketFailure -> VoiceSessionStopReason.WebSocketFailure
        else -> null
    }

private fun GeminiLiveEvent.failureSummary(): String =
    when (this) {
        is GeminiLiveEvent.Error -> message
        is GeminiLiveEvent.WebSocketClosed -> "code=$code, reason=$reason"
        is GeminiLiveEvent.WebSocketFailure -> message
        else -> this::class.simpleName ?: "Gemini terminal event"
    }

private fun sanitizeVoiceFailureSummary(value: String): String =
    value
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<redacted-url>")
        .replace(
            Regex(
                """(?i)(["']?\bAuthorization\b["']?\s*:\s*["']?(?:Bearer|Basic)\s+)[^"'\s,;}\]]+""",
            ),
            "\$1<redacted>",
        )
        .replace(
            Regex(
                """(?i)(["']?\b(?:(?:access|refresh|id)_?token|token|api[_-]?key|apiKey|password|secretToken|secret|client[_-]?secret|clientSecret)\b["']?)(\s*[:=]\s*["']?)([^"'\s,;&}\]]+)""",
            ),
            "\$1\$2<redacted>",
        )
        .replace(
            Regex(
                "\\b(?:github_pat_[A-Za-z0-9_]+(?:_[A-Za-z0-9_]+)*|glpat-[A-Za-z0-9_-]{10,}|(?:ghp|gho|ghu|ghs|ghr|sk|ya29)[A-Za-z0-9._-]{10,})\\b",
                RegexOption.IGNORE_CASE,
            ),
            "<redacted-token>",
        )
        .replace(Regex("[ \\t\\r\\n]+"), " ")
        .trim()
        .take(512)

internal sealed class VoiceSessionStopReason(
    val diagnosticReason: String,
    val autoReconnectEligible: Boolean = false,
) {
    data object StartupFailure : VoiceSessionStopReason("startup_failure")
    data object ManualReconnect : VoiceSessionStopReason("manual_reconnect")
    data object GeminiError : VoiceSessionStopReason("gemini_error")
    data object WebSocketClosed : VoiceSessionStopReason("websocket_closed", autoReconnectEligible = true)
    data object WebSocketFailure : VoiceSessionStopReason("websocket_failure", autoReconnectEligible = true)
}
