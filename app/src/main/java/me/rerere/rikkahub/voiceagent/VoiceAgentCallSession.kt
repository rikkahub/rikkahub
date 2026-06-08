package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import java.util.Base64

class VoiceAgentCallSession(
    private val modelId: String,
    private val sessionApi: VoiceSessionApi,
    private val toolApi: VoiceToolApi,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    private val conversationStore: VoiceConversationStore,
    private val contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val voiceE2EArtifacts: VoiceE2EArtifactWriter = VoiceE2EArtifactWriter.disabled(),
    private val scope: CoroutineScope,
) : ManagedVoiceCallSession {
    private val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = audio,
        diagnostics = diagnostics,
        conversationStore = conversationStore,
        writeVoiceE2EArtifact = voiceE2EArtifacts::write,
        scope = scope,
    )
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false

    override val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation

    override fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        VoiceAgentLog.d(TAG, "start sessionId=$currentSessionId modelId=$modelId")
        val job = scope.launch {
            runSession(currentSessionId)
        }
        startJob = job
    }

    private suspend fun runSession(currentSessionId: Long) {
        val sessionJob = currentCoroutineContext()[Job]
        startJob = sessionJob
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
                cleanupFailedStartup(currentSessionId, closeGemini = true)
                return
            }
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            VoiceAgentLog.d(TAG, "session connected sessionId=$currentSessionId")
            gemini.activateOutboundSession(currentSessionId)
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
                cleanupFailedStartup(currentSessionId, closeGemini = geminiStarted)
                coordinator.updateSessionStatus(
                    VoiceSessionStatus.Error(error.message ?: error.javaClass.simpleName)
                )
            }
        } finally {
            if (startJob === sessionJob) {
                startJob = null
            }
        }
    }

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        if (event is GeminiLiveEvent.Error ||
            event is GeminiLiveEvent.WebSocketClosed ||
            event is GeminiLiveEvent.WebSocketFailure
        ) {
            VoiceAgentLog.w(TAG, "Gemini failure event sessionId=$sessionId event=${event::class.simpleName}")
        }
        coordinator.onGeminiEvent(sessionId, event)
        when (event) {
            is GeminiLiveEvent.Error,
            is GeminiLiveEvent.WebSocketClosed,
            is GeminiLiveEvent.WebSocketFailure,
                -> cleanupFailedStartup(sessionId, closeGemini = true)
            else -> Unit
        }
    }

    private fun cleanupFailedStartup(sessionId: Long, closeGemini: Boolean) {
        if (!coordinator.isActiveSession(sessionId)) return
        VoiceAgentLog.d(TAG, "cleanup failed startup sessionId=$sessionId closeGemini=$closeGemini")
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
        val previousJob = startJob
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        val reconnectJob = scope.launch {
            previousJob?.cancelAndJoin()
            if (ended) return@launch
            coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
            val currentSessionId = coordinator.nextSessionId()
            sessionId = currentSessionId
            runSession(currentSessionId)
        }
        startJob = reconnectJob
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
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        return EndPreparation(previousJob = previousJob)
    }

    private suspend fun finishEnd(previousJob: Job?, visibleReason: String?) {
        previousJob?.cancelAndJoin()
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close()
        visibleReason?.let(coordinator::setVisibleError)
        coordinator.awaitPersistenceJobs()
        voiceE2EArtifacts.drain()
        coordinator.stopPersistenceScope()
    }

    override fun closeNow() {
        if (!ended) {
            ended = true
        }
        startJob?.cancel()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close(waitForStartedSends = false)
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

    private data class EndPreparation(
        val previousJob: Job?,
    )

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
