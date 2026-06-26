package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlin.random.Random

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
    private val nowMs: () -> Long = { System.currentTimeMillis() },
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
        nowMs = { System.currentTimeMillis() },
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
    private val currentSessionConnected = AtomicBoolean(false)
    private var reconnectAttemptInProgress = false

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

    private suspend fun runSession(currentSessionId: Long) {
        val sessionJob = currentCoroutineContext()[Job]
        startJob = sessionJob
        currentSessionConnected.set(false)
        var geminiStarted = false
        try {
            coordinator.updateSessionStatus(VoiceSessionStatus.PreparingContext)
            VoiceAgentLog.d(TAG, "preparing context sessionId=$currentSessionId")
            val voiceContext = contextProvider.build(conversation.value).withTurnsFoldedIntoSystemInstruction()
            VoiceAgentLog.d(
                TAG,
                "context prepared sessionId=$currentSessionId turns=${voiceContext.turns.size} " +
                    "systemInstructionChars=${voiceContext.systemInstruction.length}",
            )
            coordinator.recordDiagnostic(
                name = "voice_context_prepared",
                detail = "turns=${voiceContext.turns.size}, systemInstructionChars=${voiceContext.systemInstruction.length}",
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.RequestingToken)
            VoiceAgentLog.d(TAG, "requesting voice session sessionId=$currentSessionId modelId=$modelId")
            val session = sessionApi.createSession(modelId = modelId)
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
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.ConnectingGemini)
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
            ensureActiveSession(currentSessionId)
            if (coordinator.state.value.session is VoiceSessionStatus.Error) {
                VoiceAgentLog.w(TAG, "Gemini connect returned with error state sessionId=$currentSessionId")
                prepareFailedSession(
                    sessionId = currentSessionId,
                    reason = VoiceSessionStopReason.StartupFailure,
                    closeGemini = true,
                )
                return
            }
            currentSessionConnected.set(true)
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            ensureActiveSession(currentSessionId)
            if (reconnectAttemptInProgress) {
                reconnectState?.attempts?.let { attempt ->
                    coordinator.recordDiagnostic(
                        name = "session_reconnect_connected",
                        detail = "attempt=$attempt",
                    )
                }
                reconnectAttemptInProgress = false
                reconnectState = null
                reconnectJob = null
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
            gemini.activateOutboundSession(currentSessionId)
            val bridge = coordinator.createHermesSessionBridge(currentSessionId)
            hermesBridge = bridge
            coordinator.attachHermesBridge(bridge = bridge, sessionId = currentSessionId)
            coordinator.resumeHermesJobs()
            audio.activatePlaybackSession(currentSessionId)
            if (!muted) {
                startCapture(currentSessionId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (coordinator.isActiveSession(currentSessionId)) {
                VoiceAgentLog.w(
                    TAG,
                    "run session failed sessionId=$currentSessionId detail=${error.toVoiceAgentLogDetail()}",
                )
                val startupErrorMessage = error.message ?: error.javaClass.simpleName
                prepareFailedSession(
                    sessionId = currentSessionId,
                    reason = VoiceSessionStopReason.StartupFailure,
                    closeGemini = geminiStarted,
                )
                coordinator.updateSessionStatus(
                    VoiceSessionStatus.Error(startupErrorMessage)
                )
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

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        val stopReason = event.toSessionStopReason()
        if (stopReason != null) {
            VoiceAgentLog.w(TAG, "Gemini failure event sessionId=$sessionId event=${event::class.simpleName}")
        }
        if (stopReason != null && scheduleAutomaticReconnectIfEligible(sessionId, event, stopReason)) {
            return
        }
        coordinator.onGeminiEvent(sessionId, event)
        if (stopReason != null) {
            prepareFailedSession(sessionId = sessionId, reason = stopReason, closeGemini = true)
        }
    }

    private fun scheduleAutomaticReconnectIfEligible(
        failedSessionId: Long,
        event: GeminiLiveEvent,
        reason: VoiceSessionStopReason,
    ): Boolean {
        if (!reason.autoReconnectEligible || !currentSessionConnected.get() || ended) return false
        if (!coordinator.isActiveSession(failedSessionId)) return false

        val now = nowMs()
        val currentState = reconnectState ?: VoiceReconnectState(
            attempts = 0,
            firstFailureAtMs = now,
            latestReason = reason,
        )
        val attempt = currentState.attempts + 1
        val elapsedMs = now - currentState.firstFailureAtMs
        val delayMs = reconnectPolicy.delayMsForAttempt(attempt = attempt, elapsedMs = elapsedMs)
            ?: return false
        val nextState = currentState.copy(attempts = attempt, latestReason = reason)
        reconnectState = nextState
        currentSessionConnected.set(false)
        recordRetryableTransportDiagnostic(event)
        prepareAutomaticReconnect(failedSessionId)
        coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
        coordinator.recordDiagnostic(
            name = "session_reconnect_scheduled",
            detail = "reason=${reason.diagnosticReason}, attempt=$attempt, " +
                "maxAttempts=${reconnectPolicy.maxAttempts}, delayMs=$delayMs",
        )
        reconnectJob?.cancel()
        val job = scope.launch {
            delay(delayMs)
            if (ended) return@launch
            coordinator.recordDiagnostic(
                name = "session_reconnect_attempting",
                detail = "attempt=$attempt",
            )
            val currentSessionId = coordinator.nextSessionId()
            sessionId = currentSessionId
            reconnectAttemptInProgress = true
            runSession(currentSessionId)
        }
        reconnectJob = job
        startJob = job
        return true
    }

    private fun prepareAutomaticReconnect(sessionId: Long) {
        if (!coordinator.isActiveSession(sessionId)) return
        detachHermesBridge()
        coordinator.prepareForAutomaticReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
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

    private suspend fun ensureActiveSession(sessionId: Long) {
        currentCoroutineContext().ensureActive()
        check(coordinator.isActiveSession(sessionId)) { "Voice Agent session is stale" }
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
        val previousJob = prepareManualReconnect()
        startJob = startReconnectedSession(previousJob)
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
        if (ended) return@launch
        coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        runSession(currentSessionId)
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
        if (ended) return null
        ended = true
        val previousJob = startJob
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
        val shouldRecordEnd = !ended
        if (shouldRecordEnd) {
            ended = true
        }
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
        coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
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

internal data class VoiceReconnectPolicy(
    val maxAttempts: Int = 15,
    val maxElapsedMs: Long = 30L * 60L * 1000L,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 5L * 60L * 1000L,
    val jitterRatio: Double = 0.2,
    val jitterSource: () -> Double = { Random.nextDouble(from = -1.0, until = 1.0) },
) {
    fun delayMsForAttempt(attempt: Int, elapsedMs: Long): Long? {
        if (attempt > maxAttempts) return null
        val remainingMs = maxElapsedMs - elapsedMs
        if (remainingMs <= 0L) return null
        val exponentialDelay = exponentialDelayMs(attempt)
        val jitterMs = (exponentialDelay * jitterRatio * jitterSource()).roundToLong()
        return (exponentialDelay + jitterMs)
            .coerceAtLeast(0L)
            .coerceAtMost(remainingMs)
    }

    private fun exponentialDelayMs(attempt: Int): Long {
        var delayMs = baseDelayMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2L).coerceAtMost(maxDelayMs)
        }
        return delayMs.coerceAtMost(maxDelayMs)
    }
}

private data class VoiceReconnectState(
    val attempts: Int,
    val firstFailureAtMs: Long,
    val latestReason: VoiceSessionStopReason,
)

private fun GeminiLiveEvent.toSessionStopReason(): VoiceSessionStopReason? =
    when (this) {
        is GeminiLiveEvent.Error -> VoiceSessionStopReason.GeminiError(message)
        is GeminiLiveEvent.WebSocketClosed -> VoiceSessionStopReason.WebSocketClosed(code = code, reason = reason)
        is GeminiLiveEvent.WebSocketFailure -> VoiceSessionStopReason.WebSocketFailure(message)
        else -> null
    }

private sealed class VoiceSessionStopReason(
    val diagnosticReason: String,
    val autoReconnectEligible: Boolean = false,
) {
    data object StartupFailure : VoiceSessionStopReason("startup_failure")
    data object ManualReconnect : VoiceSessionStopReason("manual_reconnect")
    data class GeminiError(val message: String) : VoiceSessionStopReason("gemini_error")
    data class WebSocketClosed(val code: Int, val reason: String) :
        VoiceSessionStopReason("websocket_closed", autoReconnectEligible = true)
    data class WebSocketFailure(val message: String) :
        VoiceSessionStopReason("websocket_failure", autoReconnectEligible = true)
}
