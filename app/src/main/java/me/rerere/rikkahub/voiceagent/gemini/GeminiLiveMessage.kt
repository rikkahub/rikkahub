package me.rerere.rikkahub.voiceagent.gemini

import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames

sealed interface GeminiLiveEvent {
    data object SetupComplete : GeminiLiveEvent

    data object GenerationComplete : GeminiLiveEvent

    data object TurnComplete : GeminiLiveEvent

    data class Events(
        val events: List<GeminiLiveEvent>,
    ) : GeminiLiveEvent

    data class InputTranscript(
        val text: String,
    ) : GeminiLiveEvent

    data class OutputTranscript(
        val text: String,
    ) : GeminiLiveEvent

    data class OutputAudio(
        val base64Pcm16: String,
    ) : GeminiLiveEvent

    data class Interrupted(
        val reason: String = "serverContent.interrupted",
    ) : GeminiLiveEvent

    sealed interface ToolCall : GeminiLiveEvent {
        val callId: String
        val name: String
    }

    data class AskHermesCall(
        override val callId: String,
        val prompt: String,
    ) : ToolCall {
        override val name: String get() = VoiceAgentToolNames.ASK_HERMES
    }

    data class CancelHermesCall(
        override val callId: String,
        val question: String,
    ) : ToolCall {
        override val name: String get() = VoiceAgentToolNames.CANCEL_HERMES
    }

    data class UnsupportedToolCall(
        val callId: String,
        val name: String,
    )

    data class ToolCalls(
        val calls: List<ToolCall>,
        val unsupportedCalls: List<UnsupportedToolCall> = emptyList(),
    ) : GeminiLiveEvent

    data class ToolCallCancellation(
        val callIds: List<String>,
    ) : GeminiLiveEvent

    data class SessionResumptionUpdate(
        val newHandle: String?,
        val resumable: Boolean,
    ) : GeminiLiveEvent

    data class Error(
        val message: String,
        val raw: String,
    ) : GeminiLiveEvent

    data class WebSocketClosed(
        val code: Int,
        val reason: String,
    ) : GeminiLiveEvent

    data class WebSocketFailure(
        val message: String,
    ) : GeminiLiveEvent

    data class Ignored(
        val raw: String,
    ) : GeminiLiveEvent
}

data class GeminiContentTurn(
    val role: String,
    val text: String,
)
