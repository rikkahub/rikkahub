package me.rerere.rikkahub.voiceagent

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncer
import me.rerere.rikkahub.voiceagent.hermes.HermesJobManager
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.HermesSessionBridge
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnosticEvent
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

const val HERMES_JOB_POLL_INTERVAL_MS = 10_000L
private const val HERMES_JOB_MAX_ELAPSED_MS = 24L * 60 * 60 * 1000L
private const val HERMES_JOB_POLL_RETRY_DELAY_MS = 2_000L
private const val UNBOUND_HERMES_BRIDGE_SESSION_ID = HermesJobManager.UNBOUND_BRIDGE_SESSION_ID

class VoiceAgentCoordinator(
    private val gemini: GeminiLiveVoiceClient,
    private val toolApi: VoiceToolApi,
    private val audio: VoiceAudioEngine,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val observability: VoiceObservability = NoOpVoiceObservability,
    private val traceContext: VoiceTraceContext = newVoiceTraceContext(),
    private val hermesResponseExpectedHash: String? = BuildConfig.VOICE_AGENT_HERMES_E2E_EXPECTED_HASH,
    private val logHermesRequestHash: (String) -> Unit = { detail ->
        Log.i(E2E_TAG, "hermes_tool_request_hash $detail")
    },
    private val logHermesResponseHash: (String) -> Unit = { detail ->
        Log.i(E2E_TAG, "hermes_tool_response_hash $detail")
    },
    private val writeVoiceE2EArtifact: (VoiceE2EArtifact, String) -> Unit = { _, _ -> },
    private val logHermesToolFailure: (String) -> Unit = { detail ->
        Log.w(E2E_TAG, "hermes_tool_failed $detail")
    },
    private val logHermesQueueEvent: (String) -> Unit = { detail ->
        Log.d(E2E_TAG, "hermes_queue_event $detail")
    },
    private val conversationStore: VoiceConversationStore? = null,
    private val transcriptPersister: VoiceTranscriptPersister = VoiceTranscriptPersister(),
    private val hermesJobPollIntervalMs: Long = HERMES_JOB_POLL_INTERVAL_MS,
    private val hermesJobMaxElapsedMs: Long = HERMES_JOB_MAX_ELAPSED_MS,
    private val hermesJobPollRetryDelayMs: Long = HERMES_JOB_POLL_RETRY_DELAY_MS,
    private val hermesStillWorkingThresholdMs: Long = HermesJobManager.DEFAULT_STILL_WORKING_THRESHOLD_MS,
    private val hermesAnnouncementQuietWindowMs: Long = HermesAnnouncer.DEFAULT_QUIET_WINDOW_MS,
    private val hermesAnnouncementMaxHoldMs: Long = HermesAnnouncer.DEFAULT_MAX_HOLD_MS,
    private val hermesAnnouncementNowMs: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val ownsSessionScope = scope == null
    private val sessionScope = scope ?: CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
    private val hermesScope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
    private val toolLaunchContext = dispatcher ?: EmptyCoroutineContext
    private val closeLock = Any()
    private val eventLock = Any()
    private val playbackSuppressionLock = Any()
    private val toolJobsLock = Any()
    private val persistenceScope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.IO))
    private val persistenceQueue = VoicePersistenceQueue(persistenceScope)
    private val sharedConversationStore = SynchronizedVoiceConversationStore(
        conversationStore ?: InMemoryVoiceConversationStore()
    )
    private val voiceE2EArtifactSink = VoiceE2EArtifactSink(
        diagnostics = diagnostics,
        writeVoiceE2EArtifact = writeVoiceE2EArtifact,
    )
    private val hermesTelemetrySink = HermesTelemetrySink(
        diagnostics = diagnostics,
        hermesResponseExpectedHash = hermesResponseExpectedHash,
        logHermesRequestHash = logHermesRequestHash,
        logHermesResponseHash = logHermesResponseHash,
        logHermesToolFailure = logHermesToolFailure,
        logHermesQueueEvent = logHermesQueueEvent,
        artifactSink = voiceE2EArtifactSink,
    )
    private val hermesJobManager = HermesJobManager(
        toolApi = toolApi,
        conversationStore = sharedConversationStore,
        transcriptPersister = transcriptPersister,
        scope = hermesScope,
        dispatcher = dispatcher ?: Dispatchers.Default,
        pollIntervalMs = hermesJobPollIntervalMs,
        pollRetryDelayMs = hermesJobPollRetryDelayMs,
        maxElapsedMs = hermesJobMaxElapsedMs,
        stillWorkingThresholdMs = hermesStillWorkingThresholdMs,
        defaultBridge = { defaultHermesBridge },
        announcementQuietWindowMs = hermesAnnouncementQuietWindowMs,
        announcementMaxHoldMs = hermesAnnouncementMaxHoldMs,
        announcementNowMs = hermesAnnouncementNowMs,
        updateToolStatus = ::updateHermesToolStatusFromManager,
        recordDiagnostic = diagnostics::record,
        writeQueueEvent = hermesTelemetrySink::writeQueueEvent,
        writeHermesAnswer = { answer -> voiceE2EArtifactSink.writeArtifactSafely(VoiceE2EArtifact.HermesAnswer, answer) },
        persistenceSessionId = { voiceArtifactSessionId },
        onJobCompleted = hermesTelemetrySink::recordJobCompletion,
        onJobFailed = hermesTelemetrySink::recordJobFailure,
        onPollFailed = hermesTelemetrySink::recordPollFailure,
        observability = observability,
        traceContext = traceContext,
    )
    private val hermesBridgeFactory = VoiceHermesSessionBridgeFactory(
        gemini = gemini,
        diagnostics = diagnostics,
        unboundSessionId = UNBOUND_HERMES_BRIDGE_SESSION_ID,
        writeQueueEvent = { event -> hermesTelemetrySink.writeQueueEvent(event) },
        clearOutputAudioSuppressionForNewTurn = ::clearOutputAudioSuppressionForNewTurn,
    )
    // lazy: evaluated on first default attach, after construction completes
    private val defaultHermesBridge = createHermesSessionBridge(UNBOUND_HERMES_BRIDGE_SESSION_ID)
    private var hermesQueueStatusProjectionJob: Job? = null
    private var activeSessionId = 0L
    private var acceptsUnscopedGeminiEvents = true
    private val cancelledToolCallIds = mutableSetOf<ToolCallKey>()
    private var closing = false
    private var closed = false
    private var outputAudioSuppressed = false
    private var outputAudioSuppressedByGeminiInterruption = false
    private val voiceArtifactSessionId = Uuid.random().toString()
    private val turnTracker = VoiceTranscriptTurnTracker(
        transcriptPersister = transcriptPersister,
        sessionId = voiceArtifactSessionId,
        persist = ::persistConversation,
        recordEvent = ::recordEventSafely,
        recordDiagnostic = diagnostics::record,
    )
    private val removeDiagnosticsListener: () -> Unit
    private val assistantOutputAudioActive = AtomicBoolean(false)

    private val _state = MutableStateFlow(VoiceAgentUiState(traceId = traceContext.traceId))
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    init {
        audio.setErrorHandler(::handleAudioEngineError)
        _state.update { it.copy(diagnostics = diagnostics.events.value.toUiDiagnostics()) }
        removeDiagnosticsListener = diagnostics.addListener { event ->
            _state.update { current ->
                current.copy(
                    diagnostics = (current.diagnostics + event.toUiDiagnosticLine()).takeLast(MAX_UI_DIAGNOSTICS)
                )
            }
        }
        startHermesQueueStatusProjection()
    }

    private fun startHermesQueueStatusProjection() {
        hermesQueueStatusProjectionJob = hermesScope.launch {
            sharedConversationStore.conversation.collect { conversation ->
                val queueStatus = VoiceHermesQueueUiStatus.fromSnapshot(HermesQueueSnapshot.from(conversation))
                _state.update { current ->
                    current.copy(hermesQueue = queueStatus)
                }
            }
        }
    }

    private fun handleAudioEngineError(message: String) {
        diagnostics.record("audio_error", message)
        _state.update { it.reduce(VoiceAgentEvent.SessionError(message)) }
    }

    fun updateSessionStatus(status: VoiceSessionStatus) {
        diagnostics.record("session_status", status.diagnosticDetail())
        _state.update { it.copy(session = status, error = (status as? VoiceSessionStatus.Error)?.message) }
    }

    fun updateAudioStatus(status: VoiceAudioStatus) {
        diagnostics.record("audio_status", status.diagnosticDetail())
        _state.update { it.copy(audio = status) }
    }

    fun recordDiagnostic(name: String, detail: String = "") {
        diagnostics.record(name = name, detail = detail)
    }

    fun setVisibleError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun nextSessionId(): Long = synchronized(toolJobsLock) {
        activeSessionId += 1
        acceptsUnscopedGeminiEvents = true
        activeSessionId
    }

    fun invalidateActiveSession() {
        synchronized(toolJobsLock) {
            activeSessionId += 1
            acceptsUnscopedGeminiEvents = false
        }
    }

    fun onGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        when (event) {
            is GeminiLiveEvent.Events -> event.events.forEach { onGeminiEvent(sessionId = sessionId, event = it) }
            is GeminiLiveEvent.ToolCall -> handleToolCall(call = event, sessionId = sessionId)
            is GeminiLiveEvent.ToolCalls -> {
                if (!isActiveSession(sessionId)) {
                    diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
                    return
                }
                event.unsupportedCalls.forEach(::recordUnsupportedToolCall)
                event.calls.forEach { handleToolCall(call = it, sessionId = sessionId) }
            }
            is GeminiLiveEvent.ToolCallCancellation -> handleToolCallCancellation(event = event, sessionId = sessionId)
            else -> synchronized(eventLock) {
                if (!isActiveSession(sessionId)) {
                    diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
                    return
                }
                onNonToolGeminiEvent(event = event, sessionId = sessionId)
            }
        }
    }

    private fun setAssistantOutputAudioActive(active: Boolean) {
        assistantOutputAudioActive.set(active)
        hermesJobManager.announcer.onAssistantAudioActive(active)
    }

    fun suppressPlayback() {
        setAssistantOutputAudioActive(false)
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = true
        }
        turnTracker.interruptAssistantTurn(suppressed = true)
        _state.update { it.reduce(VoiceAgentEvent.UserInterrupted) }
        sessionScope.launch(toolLaunchContext) {
            audio.suppressPlayback()
        }
    }

    fun onGeminiEvent(event: GeminiLiveEvent) {
        if (shouldIgnoreEventAfterClose(event) || shouldIgnoreUnscopedEventAfterInvalidation(event)) return
        when (event) {
            is GeminiLiveEvent.Events -> event.events.forEach(::onGeminiEvent)
            is GeminiLiveEvent.ToolCall -> {
                if (shouldIgnoreEventAfterClose(event)) return
                handleToolCall(event, sessionId = null)
            }
            is GeminiLiveEvent.ToolCalls -> {
                if (shouldIgnoreEventAfterClose(event)) return
                event.unsupportedCalls.forEach(::recordUnsupportedToolCall)
                event.calls.forEach { handleToolCall(call = it, sessionId = null) }
            }
            is GeminiLiveEvent.ToolCallCancellation -> {
                if (shouldIgnoreEventAfterClose(event)) return
                handleToolCallCancellation(event = event, sessionId = null)
            }
            else -> synchronized(eventLock) {
                onNonToolGeminiEvent(event = event, sessionId = null)
            }
        }
    }

    private fun onNonToolGeminiEvent(event: GeminiLiveEvent, sessionId: Long?) {
        if (shouldIgnoreEventAfterClose(event)) return
        when (event) {
            GeminiLiveEvent.SetupComplete -> {
                recordSetupComplete()
                updateSessionStatus(VoiceSessionStatus.Connected)
            }
            is GeminiLiveEvent.InputTranscript -> {
                appendInputTranscript(event.text, sessionId = sessionId)
            }
            is GeminiLiveEvent.OutputTranscript -> {
                appendOutputTranscript(event.text, sessionId = sessionId)
            }
            GeminiLiveEvent.GenerationComplete -> handleGenerationComplete()
            GeminiLiveEvent.TurnComplete -> diagnostics.record("gemini_turn_complete")
            is GeminiLiveEvent.OutputAudio -> playOutputAudio(event.base64Pcm16, sessionId = sessionId)
            is GeminiLiveEvent.Interrupted -> handleInterrupted(event)
            is GeminiLiveEvent.SessionResumptionUpdate -> diagnostics.record(
                name = "session_resumption_update",
                detail = "resumable=${event.resumable}, newHandle=${event.newHandle.orEmpty()}",
            )
            is GeminiLiveEvent.Error -> _state.update { it.reduce(VoiceAgentEvent.SessionError(event.message)) }
            is GeminiLiveEvent.WebSocketClosed -> {
                val message = "Gemini WebSocket closed: ${event.code} ${event.reason}"
                diagnostics.record("gemini_ws_closed", "code=${event.code}, reason=${event.reason}")
                _state.update { it.reduce(VoiceAgentEvent.SessionError(message)) }
            }
            is GeminiLiveEvent.WebSocketFailure -> {
                diagnostics.record("gemini_ws_failure", event.message)
                _state.update { it.reduce(VoiceAgentEvent.SessionError("Gemini WebSocket failed: ${event.message}")) }
            }
            is GeminiLiveEvent.Ignored -> handleIgnored(event)
            is GeminiLiveEvent.ToolCall,
            is GeminiLiveEvent.ToolCalls,
            is GeminiLiveEvent.ToolCallCancellation,
            is GeminiLiveEvent.Events,
                -> Unit
        }
    }

    fun recordSetupComplete() {
        diagnostics.record("gemini_setup_complete")
    }

    fun removeLegacyVoiceSessionStartedNotes() {
        persistConversation(
            transform = transcriptPersister::removeLegacyVoiceSessionStartedNotes,
        )
    }

    suspend fun awaitToolJobs() {
        hermesJobManager.awaitJobs()
    }

    suspend fun awaitPersistenceJobs() {
        persistenceQueue.await()
    }

    suspend fun closeAndDrain() {
        close()
        awaitPersistenceJobs()
        stopPersistenceScope()
    }

    fun stopPersistenceScope() {
        persistenceScope.cancel()
        hermesScope.launch {
            hermesJobManager.awaitJobs()
            // Drain barrier — a within-process teardown ordering guarantee only: every
            // announcement enqueued before close is processed (sent or fallback-to-text) before
            // the store closes and this scope is cancelled, so the racing cancel can't drop a tail
            // completion/terminal that teardown itself produced. It is NOT a crash-durability
            // guarantee: process death mid-drain is recovered next session by resumeActiveJobs()
            // + announcer replay-on-attach, because the terminal record is persisted before the
            // announce is ever enqueued.
            hermesJobManager.announcer.awaitClosed()
            if (conversationStore != null) {
                sharedConversationStore.close()
            }
            hermesScope.cancel()
        }
    }

    fun launchPersistenceDrain() {
        persistenceScope.launch {
            awaitPersistenceJobs()
            stopPersistenceScope()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun close(waitForStartedSends: Boolean = true) {
        synchronized(closeLock) {
            synchronized(toolJobsLock) {
                if (closed || closing) return
                closing = true
                diagnostics.record("coordinator_closing")
            }
            synchronized(eventLock) {
                // Wait for any in-flight non-tool event to finish before resources are released.
            }
            turnTracker.persistAssistantForSessionClose(
                suppressed = synchronized(playbackSuppressionLock) { outputAudioSuppressed },
            )
            turnTracker.persistUserForSessionClose()
            synchronized(toolJobsLock) {
                cancelledToolCallIds.clear()
                closed = true
                closing = false
            }
            _state.update { current ->
                current.copy(
                    session = VoiceSessionStatus.Ended,
                    tool = VoiceToolStatus.Idle,
                    toolCalls = emptyMap(),
                )
            }
            setAssistantOutputAudioActive(false)
            hermesQueueStatusProjectionJob?.cancel()
            hermesQueueStatusProjectionJob = null
            hermesJobManager.announcer.close()
            gemini.close()
            audio.release()
            audio.setErrorHandler(null)
            if (ownsSessionScope) {
                sessionScope.cancel()
            }
            removeDiagnosticsListener()
        }
    }

    fun prepareForReconnect() {
        setAssistantOutputAudioActive(false)
        diagnostics.record("prepare_for_reconnect")
        invalidateActiveSession()
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = false
        }
        _state.update {
            it.copy(
                tool = VoiceToolStatus.Idle,
                toolCalls = emptyMap(),
            )
        }
    }

    fun prepareForAutomaticReconnect() {
        setAssistantOutputAudioActive(false)
        diagnostics.record("prepare_for_automatic_reconnect")
        invalidateActiveSession()
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = false
        }
    }

    fun prepareForSessionEnd() {
        setAssistantOutputAudioActive(false)
        diagnostics.record("prepare_for_session_end")
        invalidateActiveSession()
        _state.update {
            it.copy(
                tool = VoiceToolStatus.Idle,
                toolCalls = emptyMap(),
            )
        }
    }

    fun createHermesSessionBridge(sessionId: Long): HermesSessionBridge =
        hermesBridgeFactory.create(sessionId = sessionId)

    fun attachHermesBridge(bridge: HermesSessionBridge, sessionId: Long) {
        hermesJobManager.announcer.attachScoped(bridge = bridge, sessionId = sessionId)
    }

    fun detachHermesBridge(bridge: HermesSessionBridge) {
        hermesJobManager.announcer.detachScoped(bridge)
    }

    fun resumeHermesJobs() {
        hermesJobManager.resumeActiveJobs()
    }

    private fun appendInputTranscript(text: String, sessionId: Long?) {
        if (shouldIgnoreStaleSession(sessionId, GeminiLiveEvent.InputTranscript(text))) return
        hermesJobManager.announcer.onInputTranscriptDelta()
        clearOutputAudioSuppressionForNewTurn()
        _state.update { it.copy(inputTranscript = it.inputTranscript + text) }
        val snapshot = turnTracker.appendUserDelta(text)
        voiceE2EArtifactSink.writeArtifactSafely(artifact = VoiceE2EArtifact.InputTranscript, content = snapshot)
    }

    private fun appendOutputTranscript(text: String, sessionId: Long?) {
        if (shouldIgnoreStaleSession(sessionId, GeminiLiveEvent.OutputTranscript(text))) return
        clearOutputAudioSuppressionForAssistantTurnAfterInterruption()
        _state.update { it.copy(outputTranscript = it.outputTranscript + text) }
        val snapshot = turnTracker.appendAssistantDelta(
            text = text,
            suppressed = synchronized(playbackSuppressionLock) { outputAudioSuppressed },
        )
        voiceE2EArtifactSink.writeArtifactSafely(artifact = VoiceE2EArtifact.OutputTranscript, content = snapshot)
    }

    private fun playOutputAudio(base64Pcm16: String, sessionId: Long?) {
        if (synchronized(playbackSuppressionLock) { outputAudioSuppressed }) {
            diagnostics.record("output_audio_suppressed_after_interruption")
            return
        }
        if (shouldIgnoreStaleSession(sessionId, GeminiLiveEvent.OutputAudio(base64Pcm16))) return
        diagnostics.record(
            name = "output_audio_chunk",
            detail = "sessionId=${sessionId ?: "none"}, base64Chars=${base64Pcm16.length}",
        )
        val accepted = audio.playPcm16(base64Pcm16, sessionId = sessionId)
        if (!accepted) {
            diagnostics.record(
                name = "output_audio_chunk_rejected",
                detail = "sessionId=${sessionId ?: "none"}, base64Chars=${base64Pcm16.length}",
            )
            return
        }
        if (sessionId != null && !isActiveSession(sessionId)) {
            diagnostics.record("stale_output_audio_state_suppressed")
            return
        }
        if (synchronized(playbackSuppressionLock) { outputAudioSuppressed }) {
            diagnostics.record("output_audio_accepted_suppressed_after_interruption")
            return
        }
        var skippedQueuedAudioDiagnostic: String? = null
        val queued = synchronized(playbackSuppressionLock) {
            if (outputAudioSuppressed) {
                skippedQueuedAudioDiagnostic = "output_audio_state_suppressed_after_interruption"
                false
            } else if (sessionId != null && !isActiveSession(sessionId)) {
                skippedQueuedAudioDiagnostic = "stale_output_audio_state_suppressed"
                false
            } else {
                setAssistantOutputAudioActive(true)
                _state.update { it.copy(audio = VoiceAudioStatus.AssistantSpeaking) }
                true
            }
        }
        if (!queued) {
            skippedQueuedAudioDiagnostic?.let(diagnostics::record)
        } else {
            recordEventSafely(
                name = "voicelab.mobile.audio.playback_queued",
                attributes = mapOf(
                    "sessionId" to sessionId,
                    "audio.output.base64_chars" to base64Pcm16.length,
                ),
            )
        }
    }

    private fun handleInterrupted(event: GeminiLiveEvent.Interrupted) {
        diagnostics.record("gemini_interrupted", event.reason)
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressedByGeminiInterruption = true
        }
        suppressPlayback()
    }

    private fun clearOutputAudioSuppressionForNewTurn() {
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = false
            outputAudioSuppressedByGeminiInterruption = false
        }
        setAssistantOutputAudioActive(false)
    }

    private fun clearOutputAudioSuppressionForAssistantTurnAfterInterruption() {
        val cleared = synchronized(playbackSuppressionLock) {
            if (outputAudioSuppressed && outputAudioSuppressedByGeminiInterruption) {
                outputAudioSuppressed = false
                outputAudioSuppressedByGeminiInterruption = false
                true
            } else {
                outputAudioSuppressedByGeminiInterruption = false
                false
            }
        }
        if (cleared) {
            diagnostics.record("output_audio_suppression_cleared_after_interruption")
        }
    }

    private fun handleGenerationComplete() {
        diagnostics.record("gemini_generation_complete")
        hermesJobManager.announcer.onGenerationComplete()
        turnTracker.completeAssistantTurn(
            suppressed = synchronized(playbackSuppressionLock) { outputAudioSuppressed },
        )
    }

    private fun handleIgnored(event: GeminiLiveEvent.Ignored) {
        diagnostics.record("gemini_event_ignored", event.raw)
    }

    private fun handleToolCall(call: GeminiLiveEvent.ToolCall, sessionId: Long?) {
        if (sessionId != null && !isActiveSession(sessionId)) {
            diagnostics.record("stale_gemini_event", call.javaClass.simpleName)
            return
        }
        if (ToolCallKey(sessionId = sessionId, callId = call.callId) in synchronized(toolJobsLock) { cancelledToolCallIds }) {
            diagnostics.record("tool_call_ignored_after_cancellation", "callId=${call.callId}")
            return
        }
        diagnostics.record(
            "tool_call_received",
            "callId=${call.callId}, name=${call.name}, promptChars=${call.argLength()}",
        )
        when (call) {
            is GeminiLiveEvent.CancelHermesCall -> {
                if (sessionId == null) {
                    hermesJobManager.announcer.attachDefaultIfNeeded()
                }
                hermesJobManager.handleCancelHermesCall(
                    callId = call.callId,
                    question = call.question,
                    sessionId = sessionId ?: UNBOUND_HERMES_BRIDGE_SESSION_ID,
                )
            }
            is GeminiLiveEvent.AskHermesCall -> handleAskHermesToolCall(call = call, sessionId = sessionId)
        }
    }

    private fun GeminiLiveEvent.ToolCall.argLength(): Int = when (this) {
        is GeminiLiveEvent.AskHermesCall -> prompt.length
        is GeminiLiveEvent.CancelHermesCall -> question.length
    }

    private fun handleAskHermesToolCall(call: GeminiLiveEvent.AskHermesCall, sessionId: Long?) {
        if (sessionId == null) {
            hermesJobManager.announcer.attachDefaultIfNeeded()
        }
        runCatching {
            Log.d(E2E_TAG, "hermes_tool_call_received callId=${call.callId} promptChars=${call.prompt.length}")
        }
        val activeKey = ToolCallKey(sessionId = sessionId, callId = call.callId).toString()
        if (hermesJobManager.submit(callId = call.callId, prompt = call.prompt, activeKey = activeKey)) {
            voiceE2EArtifactSink.writeArtifactSafely(artifact = VoiceE2EArtifact.HermesCall, content = call.prompt)
            hermesTelemetrySink.recordRequestHash(callId = call.callId, prompt = call.prompt)
            diagnostics.record("hermes_tool_started", "callId=${call.callId}")
        } else {
            diagnostics.record("duplicate_tool_call_active", "callId=${call.callId}")
        }
    }

    private fun isClosed(): Boolean = synchronized(toolJobsLock) { closed || closing }

    fun isActiveSession(sessionId: Long): Boolean = synchronized(toolJobsLock) {
        !closed && !closing && activeSessionId == sessionId
    }

    private fun shouldIgnoreEventAfterClose(event: GeminiLiveEvent): Boolean {
        if (!isClosed()) return false
        diagnostics.record("gemini_event_after_close", event.javaClass.simpleName)
        return true
    }

    private fun shouldIgnoreUnscopedEventAfterInvalidation(event: GeminiLiveEvent): Boolean {
        val ignore = synchronized(toolJobsLock) { !acceptsUnscopedGeminiEvents && !closed && !closing }
        if (!ignore) return false
        diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
        return true
    }

    private fun shouldIgnoreStaleSession(sessionId: Long?, event: GeminiLiveEvent): Boolean {
        if (sessionId == null || isActiveSession(sessionId)) return false
        diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
        return true
    }

    private fun handleToolCallCancellation(event: GeminiLiveEvent.ToolCallCancellation, sessionId: Long?) {
        if (sessionId != null && !isActiveSession(sessionId)) {
            diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
            return
        }
        diagnostics.record(
            name = "tool_call_cancellation",
            detail = event.callIds.joinToString(","),
        )
        synchronized(toolJobsLock) {
            event.callIds.forEach { callId ->
                cancelledToolCallIds += ToolCallKey(sessionId = sessionId, callId = callId)
            }
        }
        event.callIds.forEach { callId ->
            hermesJobManager.cancel(
                callId = callId,
                activeKey = ToolCallKey(sessionId = sessionId, callId = callId).toString(),
            )
        }
        removeToolStatuses(event.callIds)
    }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        _state.update { current ->
            val toolCalls = current.toolCalls + (callId to status)
            current.copy(
                tool = summarizeVoiceToolStatus(toolCalls, status),
                toolCalls = toolCalls,
            )
        }
    }

    private fun updateHermesToolStatusFromManager(status: VoiceToolStatus) {
        when (status) {
            VoiceToolStatus.Idle -> {
                _state.update { current ->
                    current.copy(
                        tool = summarizeVoiceToolStatus(
                            toolCalls = current.toolCalls,
                            fallback = VoiceToolStatus.Idle,
                        )
                    )
                }
            }
            is VoiceToolStatus.CallingHermes -> updateToolStatus(callId = status.callId, status = status)
            is VoiceToolStatus.QueuedHermes -> updateToolStatus(callId = status.callId, status = status)
            is VoiceToolStatus.HermesAnswered -> updateToolStatus(callId = status.callId, status = status)
            is VoiceToolStatus.HermesFailed -> {
                val canceled = synchronized(toolJobsLock) {
                    cancelledToolCallIds.any { it.callId == status.callId }
                }
                if (!canceled) {
                    updateToolStatus(callId = status.callId, status = status)
                }
            }
        }
    }

    private fun removeToolStatuses(callIds: Collection<String>) {
        _state.update { current ->
            val toolCalls = current.toolCalls - callIds.toSet()
            current.copy(
                tool = summarizeVoiceToolStatus(
                    toolCalls = toolCalls,
                    fallback = toolCalls.values.lastOrNull() ?: VoiceToolStatus.Idle,
                ),
                toolCalls = toolCalls,
            )
        }
    }

    private fun recordUnsupportedToolCall(call: GeminiLiveEvent.UnsupportedToolCall) {
        diagnostics.record("unsupported_tool_call", "callId=${call.callId}, name=${call.name}")
    }

    private fun recordEventSafely(name: String, attributes: Map<String, Any?> = emptyMap()) {
        runCatching {
            observability.recordEvent(name = name, trace = traceContext, attributes = attributes)
        }
    }

    private fun persistConversation(
        transform: (Conversation) -> Conversation,
        onPersisted: () -> Unit = {},
    ) {
        if (conversationStore == null) return
        val store = sharedConversationStore
        persistenceQueue.enqueue {
            persistConversationNow(store = store, transform = transform, onPersisted = onPersisted)
        }
    }

    private suspend fun persistConversationNow(
        store: VoiceConversationStore,
        transform: (Conversation) -> Conversation,
        onPersisted: () -> Unit,
    ) {
        runCatching {
            diagnostics.record("conversation_persist_saving")
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saving) }
            store.update(transform)
        }.onSuccess {
            diagnostics.record("conversation_persist_saved")
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saved) }
            onPersisted()
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("conversation_persist_failed", message)
            _state.update { it.copy(persistence = VoicePersistenceStatus.SaveFailed(message)) }
        }
    }

    private companion object {
        const val E2E_TAG = "VoiceAgentE2E"
        const val MAX_UI_DIAGNOSTICS = 30
    }

    private fun List<VoiceDiagnosticEvent>.toUiDiagnostics(): List<VoiceDiagnosticLine> {
        return takeLast(MAX_UI_DIAGNOSTICS).map { it.toUiDiagnosticLine() }
    }

    private fun VoiceDiagnosticEvent.toUiDiagnosticLine(): VoiceDiagnosticLine {
        return VoiceDiagnosticLine(
            name = name,
            detail = detail,
            at = at.toString(),
        )
    }

    private fun VoiceSessionStatus.diagnosticDetail(): String = when (this) {
        VoiceSessionStatus.Idle -> "idle"
        VoiceSessionStatus.PreparingContext -> "preparing_context"
        VoiceSessionStatus.RequestingToken -> "requesting_token"
        VoiceSessionStatus.ConnectingGemini -> "connecting_gemini"
        VoiceSessionStatus.Connected -> "connected"
        VoiceSessionStatus.Reconnecting -> "reconnecting"
        VoiceSessionStatus.Ending -> "ending"
        VoiceSessionStatus.Ended -> "ended"
        is VoiceSessionStatus.Error -> "error=$message"
    }

    private fun VoiceAudioStatus.diagnosticDetail(): String = when (this) {
        VoiceAudioStatus.Listening -> "listening"
        VoiceAudioStatus.UserSpeaking -> "user_speaking"
        VoiceAudioStatus.AssistantSpeaking -> "assistant_speaking"
        VoiceAudioStatus.Muted -> "muted"
        VoiceAudioStatus.PlaybackSuppressed -> "playback_suppressed"
    }

    private data class ToolCallKey(
        val sessionId: Long?,
        val callId: String,
    )
}
