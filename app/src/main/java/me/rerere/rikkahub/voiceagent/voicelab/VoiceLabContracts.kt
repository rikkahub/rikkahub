@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MobileVoiceSessionRequest(
    val modelId: String,
)

@Serializable
data class MobileVoiceSessionResponse(
    val token: String,
    val modelId: String,
    val providerModel: String,
    val apiVersion: String,
    val websocketUrl: String,
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val liveConnectConfig: JsonObject,
) {
    override fun toString(): String =
        "MobileVoiceSessionResponse(" +
            "token=[redacted], " +
            "modelId=$modelId, " +
            "providerModel=$providerModel, " +
            "apiVersion=$apiVersion, " +
            "websocketUrl=[redacted], " +
            "inputSampleRate=$inputSampleRate, " +
            "outputSampleRate=$outputSampleRate, " +
            "liveConnectConfig=[redacted]" +
            ")"
}

@Serializable
data class MobileHermesRequest(
    val callId: String,
    val prompt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val profileId: String? = null,
) {
    override fun toString(): String =
        "MobileHermesRequest(callId=$callId, prompt=[redacted], profileId=$profileId)"
}

@Serializable
data class MobileHermesResponse(
    val callId: String,
    val answer: String,
    val model: String,
    val profileId: String,
    val profileLabel: String,
    val elapsedMs: Long? = null,
) {
    override fun toString(): String =
        "MobileHermesResponse(" +
            "callId=$callId, " +
            "answer=[redacted], " +
            "model=$model, " +
            "profileId=$profileId, " +
            "profileLabel=$profileLabel, " +
            "elapsedMs=$elapsedMs" +
            ")"
}

@Serializable
data class VoiceLabMobileCredentials(
    val hermesProfileApiKey: String,
    val cloudflareClientId: String? = null,
    val cloudflareClientSecret: String? = null,
) {
    override fun toString(): String =
        "VoiceLabMobileCredentials(" +
            "hermesProfileApiKey=[redacted], " +
            "cloudflareClientId=${cloudflareClientId?.let { "[redacted]" }}, " +
            "cloudflareClientSecret=${cloudflareClientSecret?.let { "[redacted]" }}" +
            ")"
}
