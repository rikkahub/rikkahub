package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames

sealed interface GeminiLiveDebugEvent {
    data object Open : GeminiLiveDebugEvent
    data class Setup(
        val hasAskHermesTool: Boolean,
        val toolConfigMode: String?,
        val allowedFunctionNames: List<String>,
        val responseModalities: List<String>,
        val systemInstructionChars: Int,
        val realtimeInputConfig: String?,
    ) : GeminiLiveDebugEvent

    data class Send(
        val kind: String,
        val sent: Boolean,
        val dataBytes: Int? = null,
    ) : GeminiLiveDebugEvent

    data class Receive(
        val kind: String,
    ) : GeminiLiveDebugEvent

    data class Event(
        val kind: String,
    ) : GeminiLiveDebugEvent
}

interface GeminiLiveVoiceClient {
    suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    )

    /**
     * When [sessionId] is null the send is not gated by the active outbound-session check.
     */
    fun sendAudio(base64Pcm16: String, sessionId: Long? = null): Boolean
    fun sendAudioStreamEnd(sessionId: Long? = null): Boolean
    fun activateOutboundSession(sessionId: Long)
    fun invalidateOutboundSession()

    fun sendToolResponse(
        callId: String,
        answer: String,
        sessionId: Long? = null,
        name: String = VoiceAgentToolNames.ASK_HERMES,
    ): Boolean
    fun sendTextTurn(text: String, sessionId: Long? = null): Boolean

    fun close()
}

interface GeminiSocket {
    fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun send(text: String): Boolean

    fun close()
}

class TestableGeminiLiveVoiceClient(
    private val socket: GeminiSocket,
    private val codec: GeminiLiveCodec = GeminiLiveCodec(),
    private val debugObserver: (GeminiLiveDebugEvent) -> Unit = {},
) : GeminiLiveVoiceClient {
    private val lock = Any()
    private val outboundSendLock = Any()
    private val lifecycleLock = Any()
    private var nextGeneration = 0L
    private var sessionState: SessionState? = null
    private var outboundSessionId: Long? = null

    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        val setupMessage = codec.setupMessage(
            providerModel = providerModel,
            liveConnectConfig = liveConnectConfig,
            systemInstruction = systemInstruction,
            hasInitialContext = contextTurns.isNotEmpty(),
        )
        debugObserver(setupMessage.debug)
        val pendingContext = contextTurns
            .takeIf { it.isNotEmpty() }
            ?.let {
                PendingMessage(
                    message = codec.clientContentMessage(it),
                    errorMessage = "Failed to send Gemini context message",
                )
            }
        val setupError = synchronized(lifecycleLock) {
            val generation = synchronized(lock) {
                sessionState?.closed = true
                val newGeneration = nextGeneration + 1
                nextGeneration = newGeneration
                sessionState = SessionState(
                    generation = newGeneration,
                    onEvent = onEvent,
                    setupComplete = false,
                    flushingSetupComplete = false,
                    pendingContext = pendingContext,
                    pendingOutboundMessages = mutableListOf(),
                    closed = false,
                )
                newGeneration
            }
            socket.open(
                url = websocketUrl,
                token = token,
                onMessage = { message ->
                    handleMessage(generation = generation, message = message)
                },
                onClosed = { code, reason ->
                    terminateCurrentSessionWithError(
                        generation = generation,
                        GeminiLiveEvent.WebSocketClosed(
                            code = code,
                            reason = reason,
                        ),
                    )
                },
                onFailure = { error ->
                    terminateCurrentSessionWithError(
                        generation = generation,
                        GeminiLiveEvent.WebSocketFailure(
                            message = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                },
            )
            debugObserver(GeminiLiveDebugEvent.Open)
            when (val result = sendOrGetPendingError(
                generation = generation,
                message = setupMessage.message,
                errorMessage = "Failed to send Gemini setup message",
            )) {
                is SendResult.Failed -> result.error
                SendResult.Sent,
                SendResult.Stale,
                    -> null
            }
        }
        setupError?.emitIfCurrent()
    }

    override fun sendAudio(base64Pcm16: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            synchronized(lock) {
                if (sessionId != null && outboundSessionId != sessionId) {
                    return false
                }
            }
            return sendPostSetupMessage(
                message = codec.realtimeAudioMessage(base64Pcm16),
                errorMessage = "Failed to send Gemini audio message",
                queueBeforeSetup = true,
                requiredOutboundSessionId = sessionId,
            )
        }
    }

    override fun sendAudioStreamEnd(sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            synchronized(lock) {
                if (sessionId != null && outboundSessionId != sessionId) {
                    return false
                }
            }
            return sendPostSetupMessage(
                message = codec.realtimeAudioStreamEndMessage(),
                errorMessage = "Failed to send Gemini audio stream end message",
                queueBeforeSetup = true,
                requiredOutboundSessionId = sessionId,
            )
        }
    }

    override fun activateOutboundSession(sessionId: Long) {
        synchronized(lock) {
            outboundSessionId = sessionId
        }
    }

    override fun invalidateOutboundSession() {
        synchronized(outboundSendLock) {
            synchronized(lock) {
                outboundSessionId = null
            }
        }
    }

    override fun sendToolResponse(
        callId: String,
        answer: String,
        sessionId: Long?,
        name: String,
    ): Boolean {
        synchronized(outboundSendLock) {
            return sendPostSetupMessage(
                message = codec.toolResponseMessage(callId = callId, answer = answer, name = name),
                errorMessage = "Failed to send Gemini tool response message",
                queueBeforeSetup = false,
                requiredOutboundSessionId = sessionId,
            )
        }
    }

    override fun sendTextTurn(text: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            return sendPostSetupMessage(
                message = codec.clientContentMessage(listOf(GeminiContentTurn(role = "user", text = text))),
                errorMessage = "Failed to send Gemini text turn message",
                queueBeforeSetup = false,
                requiredOutboundSessionId = sessionId,
            )
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            synchronized(lock) {
                sessionState?.let { state ->
                    state.closed = true
                    state.pendingContext = null
                    state.pendingOutboundMessages.clear()
                }
                sessionState = null
                outboundSessionId = null
            }
            socket.close()
        }
    }

    private fun handleMessage(generation: Long, message: String) {
        debugObserver(GeminiLiveDebugEvent.Receive(kind = message.geminiDebugReceiveKind()))
        codec.parseServerMessage(message).flatten().forEach { event ->
            debugObserver(GeminiLiveDebugEvent.Event(kind = event.geminiDebugEventKind()))
            if (event == GeminiLiveEvent.SetupComplete) {
                handleSetupComplete(generation = generation, event = event)
                return@forEach
            }

            val onEvent = synchronized(lock) {
                sessionState
                    ?.takeIf { it.generation == generation && !it.closed }
                    ?.onEvent
            }
            onEvent?.invoke(event)
        }
    }

    private fun handleSetupComplete(generation: Long, event: GeminiLiveEvent) {
        val batch: List<PendingMessage>
        val onEvent: (GeminiLiveEvent) -> Unit
        synchronized(lock) {
            val state = sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return
            state.setupComplete = true
            state.flushingSetupComplete = true
            batch = buildList {
                state.pendingContext?.let(::add)
                addAll(state.pendingOutboundMessages)
            }
            state.pendingContext = null
            state.pendingOutboundMessages.clear()
            onEvent = state.onEvent
        }

        onEvent(event)
        flushSetupCompleteMessages(generation = generation, messages = batch)
    }

    private fun flushSetupCompleteMessages(generation: Long, messages: List<PendingMessage>) {
        var batch = messages
        while (true) {
            batch.forEach { message ->
                sendOrEmitError(
                    generation = generation,
                    message = message.message,
                    errorMessage = message.errorMessage,
                )
            }
            batch = synchronized(lock) {
                val state = sessionState
                    ?.takeIf { it.generation == generation && !it.closed }
                    ?: return
                if (state.pendingOutboundMessages.isEmpty()) {
                    state.flushingSetupComplete = false
                    return
                }
                state.pendingOutboundMessages.toList().also {
                    state.pendingOutboundMessages.clear()
                }
            }
        }
    }

    private fun sendPostSetupMessage(
        message: EncodedMessage,
        errorMessage: String,
        queueBeforeSetup: Boolean,
        requiredOutboundSessionId: Long? = null,
    ): Boolean {
        val generation = synchronized(lock) {
            if (requiredOutboundSessionId != null && outboundSessionId != requiredOutboundSessionId) {
                return false
            }
            val state = sessionState?.takeUnless { it.closed } ?: return false
            if (!state.setupComplete || state.flushingSetupComplete) {
                if (!queueBeforeSetup) {
                    return false
                }
                state.pendingOutboundMessages += PendingMessage(
                    message = message,
                    errorMessage = errorMessage,
                )
                return true
            }
            state.generation
        }
        return sendOrEmitError(
            generation = generation,
            message = message,
            errorMessage = errorMessage,
        )
    }

    private fun sendOrEmitError(
        generation: Long,
        message: EncodedMessage,
        errorMessage: String,
    ): Boolean {
        return when (val result = sendOrGetPendingError(
            generation = generation,
            message = message,
            errorMessage = errorMessage,
        )) {
            is SendResult.Failed -> {
                result.error.emitIfCurrent()
                false
            }
            SendResult.Sent -> true
            SendResult.Stale -> false
        }
    }

    private fun sendOrGetPendingError(
        generation: Long,
        message: EncodedMessage,
        errorMessage: String,
    ): SendResult {
        synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return SendResult.Stale
        }
        val sent = socket.send(message.text)
        debugObserver(
            GeminiLiveDebugEvent.Send(
                kind = message.kind,
                sent = sent,
                dataBytes = message.audioDataBytes,
            )
        )
        if (sent) {
            return SendResult.Sent
        }
        val stillCurrent = synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed } != null
        }
        return if (stillCurrent) {
            SendResult.Failed(PendingError(generation = generation, message = errorMessage))
        } else {
            SendResult.Stale
        }
    }

    private fun emitIfCurrent(generation: Long, event: GeminiLiveEvent) {
        val onEvent = synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?.onEvent
        }
        onEvent?.invoke(event)
    }

    private fun terminateCurrentSessionWithError(generation: Long, event: GeminiLiveEvent) {
        val onEvent = synchronized(lock) {
            val state = sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return
            state.closed = true
            state.pendingContext = null
            state.pendingOutboundMessages.clear()
            sessionState = null
            state.onEvent
        }
        onEvent(event)
    }

    private data class SessionState(
        val generation: Long,
        val onEvent: (GeminiLiveEvent) -> Unit,
        var setupComplete: Boolean,
        var flushingSetupComplete: Boolean,
        var pendingContext: PendingMessage?,
        val pendingOutboundMessages: MutableList<PendingMessage>,
        var closed: Boolean,
    )

    private data class PendingMessage(
        val message: EncodedMessage,
        val errorMessage: String,
    )

    private data class PendingError(
        val generation: Long,
        val message: String,
    )

    private sealed interface SendResult {
        data object Sent : SendResult
        data object Stale : SendResult
        data class Failed(val error: PendingError) : SendResult
    }

    private fun PendingError.emitIfCurrent() {
        emitIfCurrent(
            generation = generation,
            GeminiLiveEvent.Error(
                message = message,
                raw = "",
            ),
        )
    }

    private fun GeminiLiveEvent.flatten(): List<GeminiLiveEvent> = when (this) {
        is GeminiLiveEvent.Events -> events.flatMap { it.flatten() }
        else -> listOf(this)
    }

}

private fun String.geminiDebugReceiveKind(): String = runCatching {
    val root = JsonInstant.parseToJsonElement(this).jsonObject
    when {
        "setupComplete" in root -> "setupComplete"
        "serverContent" in root -> "serverContent"
        "toolCall" in root -> "toolCall"
        "toolCallCancellation" in root -> "toolCallCancellation"
        "sessionResumptionUpdate" in root -> "sessionResumptionUpdate"
        else -> "unknown"
    }
}.getOrDefault("invalid-json")

private fun GeminiLiveEvent.geminiDebugEventKind(): String = when (this) {
    GeminiLiveEvent.GenerationComplete -> "GenerationComplete"
    GeminiLiveEvent.SetupComplete -> "SetupComplete"
    GeminiLiveEvent.TurnComplete -> "TurnComplete"
    is GeminiLiveEvent.Error -> "Error"
    is GeminiLiveEvent.Events -> "Events"
    is GeminiLiveEvent.Ignored -> "Ignored"
    is GeminiLiveEvent.InputTranscript -> "InputTranscript"
    is GeminiLiveEvent.Interrupted -> "Interrupted"
    is GeminiLiveEvent.OutputAudio -> "OutputAudio"
    is GeminiLiveEvent.OutputTranscript -> "OutputTranscript"
    is GeminiLiveEvent.SessionResumptionUpdate -> "SessionResumptionUpdate"
    is GeminiLiveEvent.ToolCall -> "ToolCall"
    is GeminiLiveEvent.ToolCallCancellation -> "ToolCallCancellation"
    is GeminiLiveEvent.ToolCalls -> "ToolCalls"
    is GeminiLiveEvent.WebSocketClosed -> "WebSocketClosed"
    is GeminiLiveEvent.WebSocketFailure -> "WebSocketFailure"
}
