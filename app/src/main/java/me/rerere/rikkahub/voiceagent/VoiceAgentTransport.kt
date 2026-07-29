package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceAgentTransport(val wireName: String) {
    @SerialName("direct_gemini")
    DirectGemini("direct_gemini"),

    @SerialName("livekit_experimental")
    LiveKitExperimental("livekit_experimental"),
    ;

    companion object {
        fun fromWireName(value: String?): VoiceAgentTransport? =
            entries.firstOrNull { it.wireName == value }
    }
}
