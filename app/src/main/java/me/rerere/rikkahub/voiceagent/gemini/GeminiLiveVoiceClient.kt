package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    fun sendAudio(base64Pcm16: String)
    fun sendAudio(base64Pcm16: String, sessionId: Long?): Boolean {
        sendAudio(base64Pcm16)
        return true
    }
    fun sendAudioStreamEnd(sessionId: Long?): Boolean = true
    fun activateOutboundSession(sessionId: Long) = Unit
    fun invalidateOutboundSession() = Unit

    fun sendToolResponse(callId: String, answer: String, name: String = VoiceAgentToolNames.ASK_HERMES): Boolean
    fun sendToolResponse(
        callId: String,
        answer: String,
        sessionId: Long?,
        name: String = VoiceAgentToolNames.ASK_HERMES,
    ): Boolean {
        return sendToolResponse(callId = callId, answer = answer, name = name)
    }
    fun sendTextTurn(text: String): Boolean
    fun sendTextTurn(text: String, sessionId: Long?): Boolean {
        return sendTextTurn(text = text)
    }

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
            liveConnectConfig = liveConnectConfig.withInitialHistoryConfigIfNeeded(contextTurns),
            systemInstruction = systemInstruction,
        )
        setupMessage.geminiDebugSetupEvent()?.let(debugObserver)
        val pendingContext = contextTurns
            .takeIf { it.isNotEmpty() }
            ?.let {
                PendingMessage(
                    text = codec.clientContentMessage(it),
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
                text = setupMessage,
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

    override fun sendAudio(base64Pcm16: String) {
        sendPostSetupMessage(
            text = codec.realtimeAudioMessage(base64Pcm16),
            errorMessage = "Failed to send Gemini audio message",
            queueBeforeSetup = true,
        )
    }

    override fun sendAudio(base64Pcm16: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            synchronized(lock) {
                if (sessionId != null && outboundSessionId != sessionId) {
                    return false
                }
            }
            return sendPostSetupMessage(
                text = codec.realtimeAudioMessage(base64Pcm16),
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
                text = codec.realtimeAudioStreamEndMessage(),
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

    override fun sendToolResponse(callId: String, answer: String, name: String): Boolean {
        return sendPostSetupMessage(
            text = codec.toolResponseMessage(callId = callId, answer = answer, name = name),
            errorMessage = "Failed to send Gemini tool response message",
            queueBeforeSetup = false,
        )
    }

    override fun sendToolResponse(
        callId: String,
        answer: String,
        sessionId: Long?,
        name: String,
    ): Boolean {
        synchronized(outboundSendLock) {
            return sendPostSetupMessage(
                text = codec.toolResponseMessage(callId = callId, answer = answer, name = name),
                errorMessage = "Failed to send Gemini tool response message",
                queueBeforeSetup = false,
                requiredOutboundSessionId = sessionId,
            )
        }
    }

    override fun sendTextTurn(text: String): Boolean {
        return sendPostSetupMessage(
            text = codec.clientContentMessage(listOf(GeminiContentTurn(role = "user", text = text))),
            errorMessage = "Failed to send Gemini text turn message",
            queueBeforeSetup = false,
        )
    }

    override fun sendTextTurn(text: String, sessionId: Long?): Boolean {
        synchronized(outboundSendLock) {
            return sendPostSetupMessage(
                text = codec.clientContentMessage(listOf(GeminiContentTurn(role = "user", text = text))),
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
        debugObserver(GeminiLiveDebugEvent.Receive(kind = message.geminiDebugMessageKind()))
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
                    text = message.text,
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
        text: String,
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
                    text = text,
                    errorMessage = errorMessage,
                )
                return true
            }
            state.generation
        }
        return sendOrEmitError(
            generation = generation,
            text = text,
            errorMessage = errorMessage,
        )
    }

    private fun sendOrEmitError(
        generation: Long,
        text: String,
        errorMessage: String,
    ): Boolean {
        return when (val result = sendOrGetPendingError(
            generation = generation,
            text = text,
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
        text: String,
        errorMessage: String,
    ): SendResult {
        synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return SendResult.Stale
        }
        val sent = socket.send(text)
        debugObserver(
            GeminiLiveDebugEvent.Send(
                kind = text.geminiDebugMessageKind(),
                sent = sent,
                dataBytes = text.geminiDebugAudioDataBytes(),
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
        val text: String,
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

    private fun JsonObject.withInitialHistoryConfigIfNeeded(
        contextTurns: List<GeminiContentTurn>,
    ): JsonObject {
        if (contextTurns.isEmpty()) return this

        val updated = toMutableMap()
        val historyConfig = (updated["historyConfig"] as? JsonObject)
            ?.toMutableMap()
            ?: mutableMapOf()
        historyConfig.putIfAbsent("initialHistoryInClientContent", JsonPrimitive(true))
        updated["historyConfig"] = JsonObject(historyConfig)
        return JsonObject(updated)
    }
}

private fun String.geminiDebugMessageKind(): String = runCatching {
    val root = JsonInstant.parseToJsonElement(this).jsonObject
    when {
        "setup" in root -> "setup"
        "clientContent" in root -> "clientContent"
        "toolResponse" in root -> "toolResponse"
        "toolCall" in root -> "toolCall"
        "toolCallCancellation" in root -> "toolCallCancellation"
        "setupComplete" in root -> "setupComplete"
        "sessionResumptionUpdate" in root -> "sessionResumptionUpdate"
        "serverContent" in root -> "serverContent"
        "realtimeInput" in root -> {
            val realtimeInput = root["realtimeInput"]?.jsonObject
            when {
                realtimeInput?.get("audio") != null -> "realtimeInput.audio"
                realtimeInput?.get("audioStreamEnd") != null -> "realtimeInput.audioStreamEnd"
                else -> "realtimeInput"
            }
        }
        else -> "unknown"
    }
}.getOrDefault("invalid-json")

private fun String.geminiDebugAudioDataBytes(): Int? = runCatching {
    val root = JsonInstant.parseToJsonElement(this).jsonObject
    val data = root["realtimeInput"]
        ?.jsonObject
        ?.get("audio")
        ?.jsonObject
        ?.get("data")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: return null
    data.estimatedBase64DecodedBytes()
}.getOrNull()

private fun String.geminiDebugSetupEvent(): GeminiLiveDebugEvent.Setup? = runCatching {
    val setup = JsonInstant.parseToJsonElement(this)
        .jsonObject["setup"]
        ?.jsonObject
        ?: return null
    val functionCallingConfig = setup["toolConfig"]
        ?.jsonObject
        ?.get("functionCallingConfig")
        ?.jsonObject
    val generationConfig = setup["generationConfig"]?.jsonObject
    GeminiLiveDebugEvent.Setup(
        hasAskHermesTool = setup.declaresAskHermesTool(),
        toolConfigMode = functionCallingConfig
            ?.get("mode")
            ?.jsonPrimitive
            ?.contentOrNull,
        allowedFunctionNames = functionCallingConfig
            ?.get("allowedFunctionNames")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList(),
        responseModalities = generationConfig
            ?.get("responseModalities")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList(),
        systemInstructionChars = setup["systemInstruction"]
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.sumOf { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.length ?: 0
            }
            ?: 0,
        realtimeInputConfig = setup.geminiDebugRealtimeInputConfig(),
    )
}.getOrNull()

private fun JsonObject.geminiDebugRealtimeInputConfig(): String? {
    val config = this["realtimeInputConfig"]?.jsonObject ?: return null
    val activityDetection = config["automaticActivityDetection"]?.jsonObject
    return if (activityDetection != null) {
        "automaticActivityDetection.disabled=${activityDetection.stringValue("disabled") ?: "n/a"} " +
            "start=${activityDetection.stringValue("startOfSpeechSensitivity") ?: "n/a"} " +
            "end=${activityDetection.stringValue("endOfSpeechSensitivity") ?: "n/a"} " +
            "prefixPaddingMs=${activityDetection.stringValue("prefixPaddingMs") ?: "n/a"} " +
            "silenceDurationMs=${activityDetection.stringValue("silenceDurationMs") ?: "n/a"}"
    } else {
        "automaticActivityDetection=missing"
    }
}

private fun JsonObject.stringValue(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.declaresAskHermesTool(): Boolean =
    this["tools"]
        ?.jsonArray
        ?.any { tool ->
            tool.jsonObject["functionDeclarations"]
                ?.jsonArray
                ?.any { declaration ->
                    declaration.jsonObject["name"]?.jsonPrimitive?.contentOrNull == VoiceAgentToolNames.ASK_HERMES
                } == true
        } == true

private fun String.estimatedBase64DecodedBytes(): Int {
    val padding = takeLastWhile { it == '=' }.length.coerceAtMost(2)
    return ((length * 3) / 4 - padding).coerceAtLeast(0)
}

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
