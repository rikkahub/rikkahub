package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
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
    private val reconnectPolicy: VoiceReconnectPolicy = VoiceReconnectPolicy(),
    private val nowMs: () -> Long = ::defaultReconnectClockMs,
    private val afterConnectedResourceGuardForTest: () -> Unit = {},
    private val beforeConnectedResourceActivationForTest: () -> Unit = {},
    private val afterReconnectCompletionGuardForTest: () -> Unit = {},
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
        reconnectPolicy = VoiceReconnectPolicy(),
        nowMs = ::defaultReconnectClockMs,
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
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false
    private var sessionEndedRecorded = false
    private var hermesBridge: HermesSessionBridge? = null
    private var reconnectJob: Job? = null
    private var reconnectState: VoiceReconnectState? = null
    private val reconnectLock = Any()
    private var reconnectEligibleSessionId: Long? = null
    private var reconnectAttemptInProgress = false
    private var activatingConnectedSessionId: Long? = null
    private var pendingActivationReconnect: PendingActivationReconnect? = null

    override val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation

    override fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        VoiceAgentLog.d(TAG, "start sessionId=$currentSessionId modelId=$modelId")
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

    private suspend fun runSession(
        currentSessionId: Long,
        automaticReconnectJob: Job? = null,
    ) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val sessionJob = coroutineContext[Job]
        startJob = sessionJob
        clearReconnectEligibility()
        var geminiStarted = false
        try {
            if (!updateSessionStatusIfActive(
                    sessionId = currentSessionId,
                    status = VoiceSessionStatus.PreparingContext,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
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
            if (!updateSessionStatusIfActive(
                    sessionId = currentSessionId,
                    status = VoiceSessionStatus.RequestingToken,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
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
            if (!updateSessionStatusIfActive(
                    sessionId = currentSessionId,
                    status = VoiceSessionStatus.ConnectingGemini,
                    automaticReconnectJob = automaticReconnectJob,
                )
            ) {
                return
            }
            geminiStarted = true
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
                prepareFailedSession(
                    sessionId = currentSessionId,
                    reason = VoiceSessionStopReason.StartupFailure,
                    closeGemini = true,
                )
                return
            }
            if (!markReconnectEligible(currentSessionId, automaticReconnectJob)) return
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            afterConnectedResourceGuardForTest()
            if (!coordinator.isActiveSession(currentSessionId)) {
                restoreReconnectingStatusIfAutomaticReconnectPending()
                return
            }
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            if (!reserveConnectedResourceActivation(currentSessionId)) {
                restoreReconnectingStatusIfAutomaticReconnectPending()
                return
            }
            beforeConnectedResourceActivationForTest()
            ensureActiveSession(currentSessionId, automaticReconnectJob)
            consumePendingActivationReconnect(currentSessionId)?.let { pending ->
                activatePendingReconnect(pending)
                return
            }
            try {
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                gemini.activateOutboundSession(currentSessionId)
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                val bridge = coordinator.createHermesSessionBridge(currentSessionId)
                hermesBridge = bridge
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                coordinator.attachHermesBridge(bridge = bridge, sessionId = currentSessionId)
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                coordinator.resumeHermesJobs()
                ensureActiveSession(currentSessionId, automaticReconnectJob)
                audio.activatePlaybackSession(currentSessionId)
                if (!muted) {
                    ensureActiveSession(currentSessionId, automaticReconnectJob)
                    startCapture(currentSessionId)
                }
            } catch (error: Throwable) {
                takePendingActivationReconnect(currentSessionId)?.let { pending ->
                    activatePendingReconnect(pending)
                    return
                }
                throw error
            }
            takePendingActivationReconnect(currentSessionId)?.let { pending ->
                activatePendingReconnect(pending)
                return
            }
            afterReconnectCompletionGuardForTest()
            var connectedSessionStale = false
            var restoreReconnectingAfterCommit = false
            val completedReconnectAttempt = synchronized(reconnectLock) {
                if (!coordinator.isActiveSession(currentSessionId)) {
                    connectedSessionStale = true
                    restoreReconnectingAfterCommit = reconnectState != null || reconnectJob != null
                    null
                } else {
                    if (reconnectAttemptInProgress) {
                        reconnectAttemptInProgress = false
                        val attempt = reconnectState?.attempts
                        reconnectState = null
                        reconnectJob = null
                        attempt
                    } else {
                        null
                    }
                }
            }
            if (connectedSessionStale) {
                if (restoreReconnectingAfterCommit && !ended) {
                    updateSessionStatusIfNotEnded(VoiceSessionStatus.Reconnecting)
                }
                return
            }
            if (!updateSessionStatusIfActive(currentSessionId, VoiceSessionStatus.Connected)) return
            if (!coordinator.isActiveSession(currentSessionId)) {
                restoreReconnectingStatusIfAutomaticReconnectPending()
                return
            }
            completedReconnectAttempt?.let { attempt ->
                coordinator.recordDiagnostic(
                    name = "session_reconnect_connected",
                    detail = "attempt=$attempt",
                )
            }
            VoiceAgentLog.d(TAG, "session connected sessionId=$currentSessionId")
            recordEventSafely(
                name = "voicelab.mobile.session.connected",
                attributes = mapOf(
                    "sessionId" to currentSessionId,
                    "modelId" to modelId,
                    "providerModel" to session.providerModel,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isSessionOpenAndActive(currentSessionId)) {
                VoiceAgentLog.w(
                    TAG,
                    "run session failed sessionId=$currentSessionId detail=${error.toVoiceAgentLogDetail()}",
                )
                val startupErrorMessage = error.message ?: error.javaClass.simpleName
                synchronized(reconnectLock) {
                    reconnectAttemptInProgress = false
                    reconnectState = null
                    reconnectJob = null
                    pendingActivationReconnect = null
                }
                prepareFailedSession(
                    sessionId = currentSessionId,
                    reason = VoiceSessionStopReason.StartupFailure,
                    closeGemini = geminiStarted,
                )
                clearReconnectEligibility()
                updateSessionStatusIfNotEnded(VoiceSessionStatus.Error(startupErrorMessage))
            }
        } finally {
            if (startJob === sessionJob) {
                startJob = null
            }
            if (reconnectJob === sessionJob) {
                reconnectJob = null
            }
        }
    }

    private fun reserveConnectedResourceActivation(sessionId: Long): Boolean = synchronized(reconnectLock) {
        if (!coordinator.isActiveSession(sessionId)) {
            false
        } else {
            activatingConnectedSessionId = sessionId
            true
        }
    }

    private fun consumePendingActivationReconnect(sessionId: Long): PendingActivationReconnect? =
        synchronized(reconnectLock) {
            if (activatingConnectedSessionId != sessionId) return@synchronized null
            val pending = pendingActivationReconnect?.takeIf { it.sessionId == sessionId }
            if (pending != null) {
                pendingActivationReconnect = null
                activatingConnectedSessionId = null
            }
            pending
        }

    private fun takePendingActivationReconnect(sessionId: Long): PendingActivationReconnect? =
        synchronized(reconnectLock) {
            if (activatingConnectedSessionId != sessionId) return@synchronized null
            activatingConnectedSessionId = null
            pendingActivationReconnect?.takeIf { it.sessionId == sessionId }?.also {
                pendingActivationReconnect = null
            }
        }

    private fun activatePendingReconnect(pending: PendingActivationReconnect) {
        coordinator.prepareForAutomaticReconnect()
        scheduleAutomaticReconnect(
            plan = pending.plan,
            cleanupResources = true,
        )
    }

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        val coordinatorEvent = event.withoutSetupCompleteForCoordinator() ?: return
        val stopReason = coordinatorEvent.toSessionStopReason()
        if (stopReason != null) {
            VoiceAgentLog.w(TAG, "Gemini failure event sessionId=$sessionId event=${coordinatorEvent::class.simpleName}")
        }
        if (stopReason != null && scheduleAutomaticReconnectIfEligible(sessionId, coordinatorEvent, stopReason)) {
            return
        }
        coordinator.onGeminiEvent(sessionId, coordinatorEvent)
        if (stopReason != null) {
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
        var reconnectPlan: AutomaticReconnectPlan? = null
        var deferredForActivation = false
        var exhaustedReason: VoiceSessionStopReason? = null
        var exhaustedAttempts = 0
        var exhaustedElapsedMs = 0L
        synchronized(reconnectLock) {
            if (!reason.autoReconnectEligible || ended) return false
            if (pendingActivationReconnect?.sessionId == failedSessionId) return true
            if (!coordinator.isActiveSession(failedSessionId)) return false
            val failureBelongsToReconnectAttempt = reconnectAttemptInProgress && sessionId == failedSessionId
            if (reconnectEligibleSessionId != failedSessionId && !failureBelongsToReconnectAttempt) return false

            val now = nowMs()
            val currentState = reconnectState ?: VoiceReconnectState(
                attempts = 0,
                firstFailureAtMs = now,
            )
            val attempt = currentState.attempts + 1
            val elapsedMs = now - currentState.firstFailureAtMs
            val delayMs = reconnectPolicy.delayMsForAttempt(attempt = attempt, elapsedMs = elapsedMs)
            if (delayMs == null) {
                exhaustedReason = reason
                exhaustedAttempts = currentState.attempts
                exhaustedElapsedMs = elapsedMs
                reconnectState = null
                reconnectJob = null
                reconnectAttemptInProgress = false
                pendingActivationReconnect = null
                reconnectEligibleSessionId = null
                return@synchronized
            }
            val nextState = currentState.copy(attempts = attempt)
            reconnectState = nextState
            reconnectEligibleSessionId = null
            reconnectJob?.cancel()
            val plan = AutomaticReconnectPlan(
                event = event,
                reason = reason,
                attempt = attempt,
                delayMs = delayMs,
            )
            if (activatingConnectedSessionId == failedSessionId) {
                pendingActivationReconnect = PendingActivationReconnect(
                    sessionId = failedSessionId,
                    plan = plan,
                )
                deferredForActivation = true
            } else {
                coordinator.prepareForAutomaticReconnect()
                reconnectPlan = plan
            }
        }
        exhaustedReason?.let { exhausted ->
            coordinator.recordDiagnostic(
                name = "session_reconnect_exhausted",
                detail = "reason=${exhausted.diagnosticReason}, attempts=$exhaustedAttempts, " +
                    "elapsedMs=$exhaustedElapsedMs",
            )
            return false
        }
        if (deferredForActivation) return true
        scheduleAutomaticReconnect(
            plan = reconnectPlan ?: return false,
            cleanupResources = true,
        )
        return true
    }

    private fun scheduleAutomaticReconnect(
        plan: AutomaticReconnectPlan,
        cleanupResources: Boolean,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(plan.delayMs)
            val job = currentCoroutineContext()[Job]
            val currentSessionId: Long
            synchronized(reconnectLock) {
                if (reconnectJob !== job) return@launch
                if (ended) return@launch
                reconnectAttemptInProgress = true
                currentSessionId = coordinator.nextSessionId()
                sessionId = currentSessionId
            }
            currentCoroutineContext().ensureActive()
            synchronized(reconnectLock) {
                if (reconnectJob !== job) return@launch
                if (ended) return@launch
            }
            coordinator.recordDiagnostic(
                name = "session_reconnect_attempting",
                detail = "attempt=${plan.attempt}",
            )
            currentCoroutineContext().ensureActive()
            runSession(currentSessionId, automaticReconnectJob = job)
        }
        synchronized(reconnectLock) {
            reconnectJob = job
            startJob = job
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
            synchronized(reconnectLock) {
                if (reconnectJob === job) {
                    reconnectJob = null
                }
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

    private fun clearAutomaticReconnectState(): AutomaticReconnectCancellation =
        synchronized(reconnectLock) {
            val job = reconnectJob
            val hadAutomaticReconnect = reconnectJob != null ||
                reconnectState != null ||
                reconnectAttemptInProgress ||
                pendingActivationReconnect != null
            job?.cancel()
            reconnectJob = null
            reconnectState = null
            reconnectAttemptInProgress = false
            pendingActivationReconnect = null
            reconnectEligibleSessionId = null
            if (job != null && startJob === job) {
                startJob = null
            }
            AutomaticReconnectCancellation(
                job = job,
                hadAutomaticReconnect = hadAutomaticReconnect,
            )
        }

    private fun restoreReconnectingStatusIfAutomaticReconnectPending() {
        val reconnectPending = synchronized(reconnectLock) {
            reconnectJob != null || reconnectAttemptInProgress || pendingActivationReconnect != null
        }
        if (reconnectPending && !ended) {
            updateSessionStatusIfNotEnded(VoiceSessionStatus.Reconnecting)
        }
    }

    private fun updateSessionStatusIfActive(
        sessionId: Long,
        status: VoiceSessionStatus,
        automaticReconnectJob: Job? = null,
    ): Boolean = synchronized(reconnectLock) {
        if (!isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)) {
            false
        } else {
            coordinator.updateSessionStatus(status)
            true
        }
    }

    private fun updateSessionStatusIfNotEnded(status: VoiceSessionStatus): Boolean =
        synchronized(reconnectLock) {
            if (ended) {
                false
            } else {
                coordinator.updateSessionStatus(status)
                true
            }
        }

    private fun updateAutomaticReconnectStatusIfCurrent(job: Job): Boolean =
        synchronized(reconnectLock) {
            if (ended || reconnectJob !== job) {
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
        synchronized(reconnectLock) {
            isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)
        }

    private fun isSessionOpenAndActiveLocked(
        sessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean {
        return !ended &&
            coordinator.isActiveSession(sessionId) &&
            (automaticReconnectJob == null || reconnectJob === automaticReconnectJob)
    }

    private fun isAutomaticReconnectCurrent(job: Job): Boolean =
        synchronized(reconnectLock) {
            !ended && reconnectJob === job
        }

    private fun clearReconnectEligibility() {
        synchronized(reconnectLock) {
            reconnectEligibleSessionId = null
        }
    }

    private fun markReconnectEligible(
        sessionId: Long,
        automaticReconnectJob: Job?,
    ): Boolean = synchronized(reconnectLock) {
        if (!isSessionOpenAndActiveLocked(sessionId, automaticReconnectJob)) {
            false
        } else {
            reconnectEligibleSessionId = sessionId
            true
        }
    }

    private fun markEnded(): Boolean =
        synchronized(reconnectLock) {
            if (ended) {
                false
            } else {
                ended = true
                true
            }
        }

    private fun cleanupAutomaticReconnectResources(job: Job): Boolean {
        if (!isAutomaticReconnectCurrent(job)) return false
        detachHermesBridge()
        if (!isAutomaticReconnectCurrent(job)) return false
        invalidateAudioSessions()
        if (!isAutomaticReconnectCurrent(job)) return false
        audio.stopCapture()
        if (!isAutomaticReconnectCurrent(job)) return false
        audio.suppressPlayback()
        if (!isAutomaticReconnectCurrent(job)) return false
        gemini.close()
        return isAutomaticReconnectCurrent(job)
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
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    private suspend fun ensureActiveSession(
        sessionId: Long,
        automaticReconnectJob: Job? = null,
    ) {
        currentCoroutineContext().ensureActive()
        check(isSessionOpenAndActive(sessionId, automaticReconnectJob)) { "Voice Agent session is stale" }
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
    }

    override fun reconnect() {
        if (ended) return
        val automaticReconnectJob = resetAutomaticReconnectForManualReconnect()
        val previousJob = prepareManualReconnect()
        startJob = startReconnectedSession(previousJob ?: automaticReconnectJob)
    }

    private fun prepareManualReconnect(): Job? {
        val previousJob = startJob
        coordinator.recordDiagnostic(
            name = "session_transition_manual_reconnect",
            detail = "reason=${VoiceSessionStopReason.ManualReconnect.diagnosticReason}",
        )
        detachHermesBridge()
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        return previousJob
    }

    private fun startReconnectedSession(previousJob: Job?): Job = scope.launch {
        previousJob?.cancelAndJoin()
        var currentSessionId: Long? = null
        synchronized(reconnectLock) {
            if (!ended) {
                coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
                currentSessionId = coordinator.nextSessionId().also { sessionId = it }
            }
        }
        runSession(currentSessionId ?: return@launch)
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
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        return EndPreparation(previousJob = previousJob)
    }

    private suspend fun finishEnd(previousJob: Job?, visibleReason: String?) {
        try {
            previousJob?.cancelAndJoin()
            coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
            coordinator.close()
        } finally {
            recordSessionEndedSafely()
        }
        visibleReason?.let(coordinator::setVisibleError)
        coordinator.awaitPersistenceJobs()
        voiceE2EArtifacts.drain()
        coordinator.stopPersistenceScope()
    }

    override fun closeNow() {
        markEnded()
        cancelAutomaticReconnect(reason = "close")
        startJob?.cancel()
        detachHermesBridge()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close(waitForStartedSends = false)
        recordSessionEndedSafely()
        coordinator.launchPersistenceDrain()
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
        if (isSessionOpenAndActive(currentSessionId)) {
            coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
        }
    }

    private fun invalidateAudioSessions() {
        gemini.invalidateOutboundSession()
        audio.invalidatePlaybackSession()
    }

    private fun detachHermesBridge() {
        hermesBridge?.let(coordinator::detachHermesBridge)
        hermesBridge = null
    }

    private data class EndPreparation(
        val previousJob: Job?,
    )

    private data class AutomaticReconnectCancellation(
        val job: Job?,
        val hadAutomaticReconnect: Boolean,
    )

    private fun recordEventSafely(name: String, attributes: Map<String, Any?> = emptyMap()) {
        runCatching {
            observability.recordEvent(name = name, trace = traceContext, attributes = attributes)
        }
    }

    private fun recordSessionEndedSafely() {
        if (sessionEndedRecorded) return
        sessionEndedRecorded = true
        recordEventSafely(
            name = "voicelab.mobile.session.ended",
            attributes = mapOf(
                "sessionId" to sessionId,
                "modelId" to modelId,
            ),
        )
    }

    private companion object {
        const val TAG = "VoiceAgentCallSession"
    }
}

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

private fun GeminiContentTurn.voiceContextLabel(): String =
    when (role) {
        "model" -> "Assistant"
        else -> "User"
    }

private data class VoiceReconnectState(
    val attempts: Int,
    val firstFailureAtMs: Long,
)

private data class PendingActivationReconnect(
    val sessionId: Long,
    val plan: AutomaticReconnectPlan,
)

private data class AutomaticReconnectPlan(
    val event: GeminiLiveEvent,
    val reason: VoiceSessionStopReason,
    val attempt: Int,
    val delayMs: Long,
)

private fun GeminiLiveEvent.toSessionStopReason(): VoiceSessionStopReason? =
    when (this) {
        is GeminiLiveEvent.Error -> VoiceSessionStopReason.GeminiError
        is GeminiLiveEvent.WebSocketClosed -> VoiceSessionStopReason.WebSocketClosed
        is GeminiLiveEvent.WebSocketFailure -> VoiceSessionStopReason.WebSocketFailure
        else -> null
    }

private sealed class VoiceSessionStopReason(
    val diagnosticReason: String,
    val autoReconnectEligible: Boolean = false,
) {
    data object StartupFailure : VoiceSessionStopReason("startup_failure")
    data object ManualReconnect : VoiceSessionStopReason("manual_reconnect")
    data object GeminiError : VoiceSessionStopReason("gemini_error")
    data object WebSocketClosed : VoiceSessionStopReason("websocket_closed", autoReconnectEligible = true)
    data object WebSocketFailure : VoiceSessionStopReason("websocket_failure", autoReconnectEligible = true)
}
