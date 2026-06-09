package me.rerere.rikkahub.voiceagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptStatus
import me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnosticEvent
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

class VoiceAgentCoordinator(
    private val gemini: GeminiLiveVoiceClient,
    private val toolApi: VoiceToolApi,
    private val audio: VoiceAudioEngine,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
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
    private val conversationStore: VoiceConversationStore? = null,
    private val persister: VoiceConversationPersister = VoiceConversationPersister(),
    scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val ownsScope = scope == null
    private val coordinatorScope = scope ?: CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
    private val toolLaunchContext = dispatcher ?: EmptyCoroutineContext
    private val closeLock = Any()
    private val eventLock = Any()
    private val playbackSuppressionLock = Any()
    private val toolJobsLock = Any()
    private val persistenceJobsLock = Any()
    private val persistenceLock = Mutex()
    private val persistenceScope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.IO))
    private val persistenceJobs = mutableSetOf<Job>()
    private var lastPersistenceJob: Job? = null
    private var activeSessionId = 0L
    private var acceptsUnscopedGeminiEvents = true
    private val staleToolSendFailureMessages = mutableMapOf<Long, String>()
    private val toolJobs = mutableMapOf<String, ToolJobHandle>()
    private val toolCallLocks = mutableMapOf<String, Any>()
    private val cancelledToolCallIds = mutableSetOf<ToolCallKey>()
    private var closing = false
    private var closed = false
    private var outputAudioSuppressed = false
    private var activeTranscriptSpeaker: TranscriptSpeaker? = null
    private var inputTurnTranscript = ""
    private var outputTurnTranscript = ""
    private var outputTurnStatus = VoiceTranscriptStatus.Partial
    private var transcriptTurnSequence = 0L
    private var inputTurnId = ""
    private var outputTurnId = ""
    private val voiceArtifactSessionId = Uuid.random().toString()
    private val removeDiagnosticsListener: () -> Unit

    private val _state = MutableStateFlow(VoiceAgentUiState())
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

    fun invalidateActiveSession(staleToolSendFailureMessage: String? = null) {
        synchronized(toolJobsLock) {
            val staleSessionId = activeSessionId
            activeSessionId += 1
            acceptsUnscopedGeminiEvents = false
            if (staleToolSendFailureMessage != null) {
                staleToolSendFailureMessages[staleSessionId] = staleToolSendFailureMessage
            }
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

    fun suppressPlayback() {
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = true
            if (outputTurnTranscript.isNotBlank()) {
                persistAssistantTranscript()
            }
            _state.update { it.reduce(VoiceAgentEvent.UserInterrupted) }
        }
        coordinatorScope.launch(toolLaunchContext) {
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
                diagnostics.record("gemini_setup_complete")
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

    suspend fun awaitToolJobs() {
        while (true) {
            val jobs = synchronized(toolJobsLock) {
                if (toolJobs.isEmpty()) {
                    return
                }
                toolJobs.values.map { it.job }
            }
            jobs.joinAll()
        }
    }

    suspend fun awaitPersistenceJobs() {
        while (true) {
            val jobs = synchronized(persistenceJobsLock) {
                if (persistenceJobs.isEmpty()) {
                    return
                }
                persistenceJobs.toList()
            }
            jobs.joinAll()
        }
    }

    suspend fun closeAndDrain() {
        close()
        awaitPersistenceJobs()
        persistenceScope.cancel()
    }

    fun stopPersistenceScope() {
        conversationStore?.close()
        persistenceScope.cancel()
    }

    fun launchPersistenceDrain() {
        persistenceScope.launch {
            awaitPersistenceJobs()
            stopPersistenceScope()
        }
    }

    fun close(waitForStartedSends: Boolean = true) {
        synchronized(closeLock) {
            val cleanup = synchronized(toolJobsLock) {
                if (closed || closing) return
                closing = true
                diagnostics.record("coordinator_closing")
                removeAllToolHandlesForCleanup(
                    staleSendingFailureMessage = if (waitForStartedSends) null else TOOL_CALL_CANCELED_BY_SESSION_END,
                )
            }
            val handlesToPersistCanceled = if (waitForStartedSends) {
                cleanup.cancelableHandles
            } else {
                cleanup.cancelableHandles + cleanup.sendingHandles
            }
            if (waitForStartedSends) {
                cleanup.sendingHandles.forEach { it.allowInactiveSendCompletion = true }
            }
            handlesToPersistCanceled.forEach {
                persistToolCanceled(it, TOOL_CALL_CANCELED_BY_SESSION_END)
            }
            val jobs = cleanup.cancelableHandles.map { it.job }
            jobs.forEach { it.cancel() }
            synchronized(eventLock) {
                // Wait for any in-flight non-tool event to finish before resources are released.
            }
            if (waitForStartedSends) {
                cleanup.sendingHandles.forEach { handle ->
                    synchronized(handle.sendLock) {
                        // Wait for any already-started Gemini tool response write to return.
                    }
                }
            }
            persistAssistantTranscriptForSessionClose()
            persistUserTranscript(status = VoiceTranscriptStatus.SessionClosedBeforeFinal)
            synchronized(toolJobsLock) {
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
            gemini.close()
            audio.release()
            audio.setErrorHandler(null)
            if (ownsScope) {
                coordinatorScope.cancel()
            }
            removeDiagnosticsListener()
        }
    }

    fun prepareForReconnect() {
        diagnostics.record("prepare_for_reconnect")
        invalidateActiveSession(staleToolSendFailureMessage = TOOL_CALL_CANCELED_BY_RECONNECT)
        val handles = synchronized(toolJobsLock) {
            removeAllToolHandlesForCleanup(staleSendingFailureMessage = TOOL_CALL_CANCELED_BY_RECONNECT)
        }
        (handles.cancelableHandles + handles.sendingHandles).forEach {
            persistToolCanceled(it, TOOL_CALL_CANCELED_BY_RECONNECT)
        }
        val jobs = handles.cancelableHandles.map { it.job }
        jobs.forEach { it.cancel() }
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

    fun prepareForSessionEnd() {
        diagnostics.record("prepare_for_session_end")
        invalidateActiveSession(staleToolSendFailureMessage = TOOL_CALL_CANCELED_BY_SESSION_END)
        val handles = synchronized(toolJobsLock) {
            removeAllToolHandlesForCleanup(staleSendingFailureMessage = TOOL_CALL_CANCELED_BY_SESSION_END)
        }
        (handles.cancelableHandles + handles.sendingHandles).forEach {
            persistToolCanceled(it, TOOL_CALL_CANCELED_BY_SESSION_END)
        }
        val jobs = handles.cancelableHandles.map { it.job }
        jobs.forEach { it.cancel() }
        _state.update {
            it.copy(
                tool = VoiceToolStatus.Idle,
                toolCalls = emptyMap(),
            )
        }
    }

    private fun appendInputTranscript(text: String, sessionId: Long?) {
        val artifactSnapshot = synchronized(toolJobsLock) {
            if (shouldIgnoreStaleSession(sessionId, GeminiLiveEvent.InputTranscript(text))) return
            if (activeTranscriptSpeaker != TranscriptSpeaker.User) {
                inputTurnTranscript = ""
                inputTurnId = nextTranscriptTurnId(TranscriptSpeaker.User)
            }
            activeTranscriptSpeaker = TranscriptSpeaker.User
            inputTurnTranscript += text
            diagnostics.record("input_transcript_delta", "turnId=$inputTurnId, text=$text")
            synchronized(playbackSuppressionLock) {
                outputAudioSuppressed = false
            }
            _state.update { it.copy(inputTranscript = it.inputTranscript + text) }
            val transcript = inputTurnTranscript
            val turnId = inputTurnId
            persistConversation { conversation ->
                persister.upsertUserTranscriptTurn(
                    conversation = conversation,
                    text = transcript,
                    turnId = turnId,
                    sessionId = voiceArtifactSessionId,
                    status = VoiceTranscriptStatus.Partial,
                )
            }
            transcript
        }
        writeArtifactSafely(artifact = VoiceE2EArtifact.InputTranscript, content = artifactSnapshot)
    }

    private fun appendOutputTranscript(text: String, sessionId: Long?) {
        val artifactSnapshot = synchronized(toolJobsLock) {
            if (shouldIgnoreStaleSession(sessionId, GeminiLiveEvent.OutputTranscript(text))) return
            if (activeTranscriptSpeaker != TranscriptSpeaker.Assistant) {
                outputTurnTranscript = ""
                outputTurnId = nextTranscriptTurnId(TranscriptSpeaker.Assistant)
                outputTurnStatus = VoiceTranscriptStatus.Partial
            }
            activeTranscriptSpeaker = TranscriptSpeaker.Assistant
            outputTurnTranscript += text
            diagnostics.record("output_transcript_delta", "turnId=$outputTurnId, text=$text")
            _state.update { it.copy(outputTranscript = it.outputTranscript + text) }
            persistAssistantTranscript()
            outputTurnTranscript
        }
        writeArtifactSafely(artifact = VoiceE2EArtifact.OutputTranscript, content = artifactSnapshot)
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
        audio.playPcm16(base64Pcm16, sessionId = sessionId)
        if (sessionId != null && !isActiveSession(sessionId)) {
            diagnostics.record("stale_output_audio_state_suppressed")
            return
        }
        synchronized(playbackSuppressionLock) {
            if (outputAudioSuppressed) {
                diagnostics.record("output_audio_state_suppressed_after_interruption")
                return
            }
            _state.update { it.copy(audio = VoiceAudioStatus.AssistantSpeaking) }
        }
    }

    private fun handleInterrupted(event: GeminiLiveEvent.Interrupted) {
        diagnostics.record("gemini_interrupted", event.reason)
        suppressPlayback()
    }

    private fun handleGenerationComplete() {
        diagnostics.record("gemini_generation_complete")
        synchronized(playbackSuppressionLock) {
            if (outputTurnTranscript.isBlank() || outputAudioSuppressed) return
            outputTurnStatus = VoiceTranscriptStatus.Complete
            persistAssistantTranscript()
        }
    }

    private fun handleIgnored(event: GeminiLiveEvent.Ignored) {
        diagnostics.record("gemini_event_ignored", event.raw)
    }

    private fun handleToolCall(call: GeminiLiveEvent.ToolCall, sessionId: Long?) {
        if (sessionId != null && !isActiveSession(sessionId)) {
            diagnostics.record("stale_gemini_event", call.javaClass.simpleName)
            return
        }
        diagnostics.record(
            "tool_call_received",
            "callId=${call.callId}, name=${call.name}, promptChars=${call.prompt.length}",
        )
        if (call.name != VoiceAgentToolNames.ASK_HERMES) {
            recordUnsupportedToolCall(
                GeminiLiveEvent.UnsupportedToolCall(
                    callId = call.callId,
                    name = call.name,
                )
            )
            return
        }
        val handle = ToolJobHandle(callId = call.callId, prompt = call.prompt, sessionId = sessionId)
        val job = coordinatorScope.launch(toolLaunchContext, start = CoroutineStart.LAZY) {
            runHermesToolCall(callId = call.callId, prompt = call.prompt, handle = handle)
        }
        handle.job = job
        val shouldStart = registerToolHandle(callId = call.callId, handle = handle)
        if (!shouldStart) {
            job.cancel()
            return
        }
        writeArtifactSafely(artifact = VoiceE2EArtifact.HermesCall, content = call.prompt)
        recordHermesToolRequestHash(callId = call.callId, prompt = call.prompt)
        diagnostics.record("hermes_tool_started", "callId=${call.callId}")
        handle.elapsedJob = coordinatorScope.launch(toolLaunchContext) {
            refreshPendingToolElapsed(callId = call.callId, handle = handle)
        }
        job.invokeOnCompletion {
            handle.elapsedJob?.cancel()
            synchronized(toolJobsLock) {
                if (toolJobs[call.callId] === handle && !handle.superseded) {
                    toolJobs -= call.callId
                }
            }
        }
        job.start()
    }

    private suspend fun refreshPendingToolElapsed(callId: String, handle: ToolJobHandle) {
        while (true) {
            delay(PENDING_TOOL_ELAPSED_REFRESH_MS)
            if (!isToolHandleActive(callId, handle)) return
            updatePendingToolElapsed(callId = callId, elapsedMs = handle.elapsedMs())
        }
    }

    private suspend fun runHermesToolCall(callId: String, prompt: String, handle: ToolJobHandle) {
        try {
            val response = toolApi.askHermes(callId = callId, prompt = prompt)
            val coroutineContext = currentCoroutineContext()
            synchronized(handle.sendLock) {
                if (!isToolHandleActive(callId, handle)) return
                coroutineContext.ensureActive()
                synchronized(toolJobsLock) {
                    if (!isToolHandleActive(callId, handle)) return
                    handle.sendStarted = true
                }
                recordHermesToolResponseHash(
                    callId = callId,
                    answer = response.answer,
                    expectedHash = hermesResponseExpectedHash,
                    elapsedMs = handle.elapsedMs(),
                    serverElapsedMs = response.elapsedMs,
                )
                val sent = gemini.sendToolResponse(
                    callId = callId,
                    answer = response.answer,
                    sessionId = handle.sessionId,
                )
                val activeAfterSend = isToolHandleActive(callId, handle)
                val staleSendingFailureMessage = if (activeAfterSend) null else staleSendingFailureMessage(handle)
                val canPersistInactiveSend = activeAfterSend || handle.allowInactiveSendCompletion
                val elapsedMs = handle.elapsedMs()
                if (sent) {
                    if (canPersistInactiveSend) {
                        diagnostics.record(
                            "hermes_tool_succeeded",
                            "callId=$callId, elapsedMs=$elapsedMs${response.serverElapsedDiagnostic()}, " +
                                "answerChars=${response.answer.length}",
                        )
                        persistToolStatus(
                            callId = callId,
                            prompt = prompt,
                            status = VoiceToolRecordStatus.Complete(response.answer),
                        )
                    } else if (staleSendingFailureMessage != null) {
                        diagnostics.record(
                            "stale_hermes_tool_send_completed",
                            "callId=$callId, message=$staleSendingFailureMessage",
                        )
                    } else {
                        diagnostics.record("inactive_hermes_tool_send_completed", "callId=$callId")
                    }
                    if (activeAfterSend) {
                        updateToolStatus(callId, VoiceToolStatus.HermesAnswered(callId = callId, elapsedMs = elapsedMs))
                    }
                } else {
                    val message = "Failed to send Gemini tool response message"
                    if (canPersistInactiveSend) {
                        diagnostics.record("hermes_tool_failed", "callId=$callId, elapsedMs=$elapsedMs, message=$message")
                        persistToolStatus(
                            callId = callId,
                            prompt = prompt,
                            status = VoiceToolRecordStatus.Failed(message),
                        )
                    } else if (staleSendingFailureMessage != null) {
                        diagnostics.record(
                            "stale_hermes_tool_send_failed",
                            "callId=$callId, message=$staleSendingFailureMessage",
                        )
                    } else {
                        diagnostics.record("inactive_hermes_tool_send_failed", "callId=$callId, message=$message")
                    }
                    if (activeAfterSend) {
                        updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            synchronized(handle.sendLock) {
                val active = isToolHandleActive(callId, handle)
                if (!active && !hasToolResponseSendStarted(handle)) return
                val elapsedMs = handle.elapsedMs()
                val detail = "callId=$callId, elapsedMs=$elapsedMs, message=$message"
                diagnostics.record("hermes_tool_failed", detail)
                val e2eDetail = "callId=$callId, elapsedMs=$elapsedMs, " +
                    "message=${message.e2eSafeHermesFailureMessage()}"
                runCatching {
                    logHermesToolFailure(e2eDetail)
                }
                persistToolStatus(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Failed(message),
                )
                if (active) {
                    updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
                }
            }
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

    private fun isToolHandleActive(callId: String, handle: ToolJobHandle): Boolean = synchronized(toolJobsLock) {
        !closed &&
            !closing &&
            isHandleSessionActive(handle) &&
            !handle.superseded &&
            handle.key !in cancelledToolCallIds &&
            toolJobs[callId] === handle
    }

    private fun registerToolHandle(callId: String, handle: ToolJobHandle): Boolean {
        synchronized(toolCallLock(callId)) {
            val currentHandle = synchronized(toolJobsLock) {
                if (!canAcceptToolHandle(callId, handle)) return false
                toolJobs[callId]
            }
            if (currentHandle != null) {
                synchronized(toolJobsLock) {
                    if (!canAcceptToolHandle(callId, handle)) return false
                    if (toolJobs[callId] !== currentHandle) return false
                    if (currentHandle.sendStarted) {
                        diagnostics.record("duplicate_tool_call_after_send_started", "callId=$callId")
                        return false
                    }
                }
                synchronized(currentHandle.sendLock) {
                    synchronized(toolJobsLock) {
                        if (!canAcceptToolHandle(callId, handle)) return false
                        if (toolJobs[callId] !== currentHandle) return false
                        if (currentHandle.sendStarted) {
                            diagnostics.record("duplicate_tool_call_after_send_started", "callId=$callId")
                            return false
                        }
                        currentHandle.superseded = true
                        persistToolStatus(
                            callId = handle.callId,
                            prompt = handle.prompt,
                            status = VoiceToolRecordStatus.Pending,
                        )
                        toolJobs[callId] = handle
                        updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId))
                        currentHandle.job.cancel()
                        return true
                    }
                }
            }
            synchronized(toolJobsLock) {
                if (!canAcceptToolHandle(callId, handle)) return false
                toolJobs[callId]
                    ?.let { return false }
                persistToolStatus(
                    callId = handle.callId,
                    prompt = handle.prompt,
                    status = VoiceToolRecordStatus.Pending,
                )
                toolJobs[callId] = handle
                updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId))
                return true
            }
        }
    }

    private fun canAcceptToolHandle(callId: String, handle: ToolJobHandle): Boolean {
        return !closed && !closing && isHandleSessionActive(handle) && handle.key !in cancelledToolCallIds
    }

    private fun isHandleSessionActive(handle: ToolJobHandle): Boolean {
        val sessionId = handle.sessionId ?: return true
        return activeSessionId == sessionId
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
        val handlesToCancel = event.callIds.mapNotNull { cancelToolCall(callId = it, sessionId = sessionId) }
        handlesToCancel.flatMap { it.cancelableHandles }.forEach {
            persistToolCanceled(it, TOOL_CALL_CANCELED_BY_GEMINI)
        }
        removeToolStatuses(event.callIds)
        handlesToCancel.flatMap { it.cancelableHandles }.forEach { it.job.cancel() }
    }

    private fun cancelToolCall(callId: String, sessionId: Long?): ToolCleanup? {
        synchronized(toolCallLock(callId)) {
            return synchronized(toolJobsLock) {
                if (sessionId != null && activeSessionId != sessionId) {
                    diagnostics.record("stale_gemini_event", GeminiLiveEvent.ToolCallCancellation::class.java.simpleName)
                    return null
                }
                val cancellationKey = ToolCallKey(sessionId = sessionId, callId = callId)
                cancelledToolCallIds += cancellationKey
                val activeHandle = toolJobs[callId] ?: return null
                if (activeHandle.sessionId != sessionId) return null
                val handle = toolJobs.remove(callId)
                if (handle != null) {
                    handle.superseded = true
                }
                handle?.toCleanup(allowInactiveSendCompletion = true)
            }
        }
    }

    private fun removeAllToolHandlesForCleanup(staleSendingFailureMessage: String? = null): ToolCleanup {
        val cleanup = toolJobs.values.toList().toCleanup()
        if (staleSendingFailureMessage != null) {
            cleanup.sendingHandles.forEach {
                it.staleSendingFailureMessage = staleSendingFailureMessage
            }
        }
        toolJobs.clear()
        toolCallLocks.clear()
        cancelledToolCallIds.clear()
        return cleanup
    }

    private fun List<ToolJobHandle>.toCleanup(): ToolCleanup {
        forEach { it.superseded = true }
        val (sendingHandles, cancelableHandles) = partition { it.sendStarted }
        return ToolCleanup(
            cancelableHandles = cancelableHandles,
            sendingHandles = sendingHandles,
        )
    }

    private fun ToolJobHandle.toCleanup(allowInactiveSendCompletion: Boolean = false): ToolCleanup {
        superseded = true
        if (allowInactiveSendCompletion) {
            this.allowInactiveSendCompletion = true
        }
        return if (sendStarted) {
            ToolCleanup(cancelableHandles = emptyList(), sendingHandles = listOf(this))
        } else {
            ToolCleanup(cancelableHandles = listOf(this), sendingHandles = emptyList())
        }
    }

    private fun hasToolResponseSendStarted(handle: ToolJobHandle): Boolean = synchronized(toolJobsLock) {
        handle.sendStarted
    }

    private fun staleSendingFailureMessage(handle: ToolJobHandle): String? = synchronized(toolJobsLock) {
        handle.staleSendingFailureMessage ?: handle.sessionId?.let(staleToolSendFailureMessages::get)
    }

    private fun toolCallLock(callId: String): Any = synchronized(toolJobsLock) {
        toolCallLocks.getOrPut(callId) { Any() }
    }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        _state.update { current ->
            val toolCalls = current.toolCalls + (callId to status)
            current.copy(
                tool = summarizeToolStatus(toolCalls, status),
                toolCalls = toolCalls,
            )
        }
    }

    private fun updatePendingToolElapsed(callId: String, elapsedMs: Long) {
        _state.update { current ->
            val currentStatus = current.toolCalls[callId] as? VoiceToolStatus.CallingHermes
                ?: return@update current
            val updatedStatus = currentStatus.copy(elapsedMs = elapsedMs)
            val toolCalls = current.toolCalls + (callId to updatedStatus)
            current.copy(
                tool = summarizeToolStatus(toolCalls, updatedStatus),
                toolCalls = toolCalls,
            )
        }
    }

    private fun removeToolStatuses(callIds: Collection<String>) {
        _state.update { current ->
            val toolCalls = current.toolCalls - callIds.toSet()
            current.copy(
                tool = summarizeToolStatus(
                    toolCalls = toolCalls,
                    fallback = toolCalls.values.lastOrNull() ?: VoiceToolStatus.Idle,
                ),
                toolCalls = toolCalls,
            )
        }
    }

    private fun summarizeToolStatus(
        toolCalls: Map<String, VoiceToolStatus>,
        fallback: VoiceToolStatus,
    ): VoiceToolStatus {
        return when (fallback) {
            is VoiceToolStatus.CallingHermes -> fallback
            else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
                ?: fallback
        }
    }

    private fun recordUnsupportedToolCall(call: GeminiLiveEvent.UnsupportedToolCall) {
        diagnostics.record("unsupported_tool_call", "callId=${call.callId}, name=${call.name}")
    }

    private fun recordHermesToolRequestHash(callId: String, prompt: String) {
        val detail = HermesToolResponseHash.requestDiagnosticDetail(callId = callId, prompt = prompt)
        diagnostics.record("hermes_tool_request_hash", detail)
        runCatching {
            logHermesRequestHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_request_hash_log_failed", "callId=$callId, message=$message")
        }
    }

    private fun recordHermesToolResponseHash(
        callId: String,
        answer: String,
        expectedHash: String?,
        elapsedMs: Long,
        serverElapsedMs: Long?,
    ) {
        val detail = HermesToolResponseHash.diagnosticDetail(
            callId = callId,
            answer = answer,
            expectedSha256 = expectedHash?.takeIf { it.isNotBlank() },
            elapsedMs = elapsedMs,
            serverElapsedMs = serverElapsedMs,
        )
        diagnostics.record("hermes_tool_response_hash", detail)
        runCatching {
            logHermesResponseHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_response_hash_log_failed", "callId=$callId, message=$message")
        }
        writeArtifactSafely(artifact = VoiceE2EArtifact.HermesAnswer, content = answer, callId = callId)
    }

    private fun writeArtifactSafely(artifact: VoiceE2EArtifact, content: String, callId: String? = null) {
        runCatching {
            writeVoiceE2EArtifact(artifact, content)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            val callDetail = callId?.let { ", callId=$it" } ?: ""
            diagnostics.record(
                "voice_e2e_artifact_write_failed",
                "name=${artifact.fileName}$callDetail, message=$message",
            )
        }
    }

    private fun persistToolStatus(callId: String, prompt: String, status: VoiceToolRecordStatus) {
        persistConversation { conversation ->
            persister.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = status,
                sessionId = voiceArtifactSessionId,
            )
        }
    }

    private fun persistToolCanceled(handle: ToolJobHandle, message: String) {
        persistToolStatus(
            callId = handle.callId,
            prompt = handle.prompt,
            status = VoiceToolRecordStatus.Failed(message),
        )
    }

    private fun persistAssistantTranscript(statusOverride: VoiceTranscriptStatus? = null) {
        val transcript = outputTurnTranscript
        val turnId = outputTurnId
        val status = synchronized(playbackSuppressionLock) {
            statusOverride ?: when {
                outputAudioSuppressed -> VoiceTranscriptStatus.Interrupted
                else -> outputTurnStatus
            }
        }
        persistConversation { conversation ->
            persister.upsertAssistantTranscriptTurn(
                conversation = conversation,
                text = transcript,
                interrupted = status == VoiceTranscriptStatus.Interrupted,
                turnId = turnId,
                sessionId = voiceArtifactSessionId,
                status = status,
            )
        }
    }

    private fun persistAssistantTranscriptForSessionClose() {
        val status = synchronized(playbackSuppressionLock) {
            when {
                outputTurnTranscript.isBlank() || outputTurnId.isBlank() -> return
                outputAudioSuppressed -> VoiceTranscriptStatus.Interrupted
                outputTurnStatus == VoiceTranscriptStatus.Complete -> VoiceTranscriptStatus.Complete
                else -> VoiceTranscriptStatus.SessionClosedBeforeFinal
            }
        }
        persistAssistantTranscript(statusOverride = status)
    }

    private fun persistUserTranscript(status: VoiceTranscriptStatus) {
        val transcript = inputTurnTranscript
        val turnId = inputTurnId
        if (transcript.isBlank() || turnId.isBlank()) return
        persistConversation { conversation ->
            persister.upsertUserTranscriptTurn(
                conversation = conversation,
                text = transcript,
                turnId = turnId,
                sessionId = voiceArtifactSessionId,
                status = status,
            )
        }
    }

    private fun nextTranscriptTurnId(speaker: TranscriptSpeaker): String {
        transcriptTurnSequence += 1
        return "${speaker.name.lowercase()}-$transcriptTurnSequence"
    }

    private fun persistConversation(transform: (Conversation) -> Conversation) {
        val store = conversationStore ?: return
        lateinit var job: Job
        synchronized(persistenceJobsLock) {
            val previousJob = lastPersistenceJob
            job = persistenceScope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                persistConversationNow(store = store, transform = transform)
            }
            persistenceJobs += job
            lastPersistenceJob = job
        }
        job.invokeOnCompletion {
            synchronized(persistenceJobsLock) {
                persistenceJobs -= job
                if (lastPersistenceJob === job) {
                    lastPersistenceJob = null
                }
            }
        }
        job.start()
    }

    private suspend fun persistConversationNow(
        store: VoiceConversationStore,
        transform: (Conversation) -> Conversation,
    ) {
        runCatching {
            diagnostics.record("conversation_persist_saving")
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saving) }
            persistenceLock.withLock {
                store.update(transform)
            }
        }.onSuccess {
            diagnostics.record("conversation_persist_saved")
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saved) }
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("conversation_persist_failed", message)
            _state.update { it.copy(persistence = VoicePersistenceStatus.SaveFailed(message)) }
        }
    }

    private companion object {
        const val E2E_TAG = "VoiceAgentE2E"
        const val TOOL_CALL_CANCELED_BY_GEMINI = "Tool call canceled by Gemini"
        const val TOOL_CALL_CANCELED_BY_RECONNECT = "Tool call canceled by reconnect"
        const val TOOL_CALL_CANCELED_BY_SESSION_END = "Tool call canceled by session end"
        const val MAX_UI_DIAGNOSTICS = 30
        const val PENDING_TOOL_ELAPSED_REFRESH_MS = 250L
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

    private enum class TranscriptSpeaker {
        User,
        Assistant,
    }

    private fun MobileHermesResponse.serverElapsedDiagnostic(): String =
        elapsedMs?.let { ", serverElapsedMs=$it" }.orEmpty()

    private fun String.e2eSafeHermesFailureMessage(): String {
        Regex("Voice Lab request failed \\d+").find(this)?.let { return it.value }
        return substringBefore(':').take(120).ifBlank { "Hermes tool failed" }
    }

    private class ToolJobHandle(
        val callId: String,
        val prompt: String,
        val sessionId: Long?,
        val sendLock: Any = Any(),
    ) {
        private val startedAt = Clock.System.now()
        lateinit var job: Job
        var elapsedJob: Job? = null
        var superseded: Boolean = false
        var sendStarted: Boolean = false
        var staleSendingFailureMessage: String? = null
        var allowInactiveSendCompletion: Boolean = false
        val key: ToolCallKey get() = ToolCallKey(sessionId = sessionId, callId = callId)

        fun elapsedMs(): Long = (Clock.System.now() - startedAt).inWholeMilliseconds.coerceAtLeast(0L)
    }

    private data class ToolCleanup(
        val cancelableHandles: List<ToolJobHandle>,
        val sendingHandles: List<ToolJobHandle>,
    )

    private data class ToolCallKey(
        val sessionId: Long?,
        val callId: String,
    )
}
