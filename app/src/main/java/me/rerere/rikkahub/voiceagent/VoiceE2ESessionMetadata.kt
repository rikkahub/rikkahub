package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class VoiceE2ESessionMetadata(
    val voiceTraceId: String,
    val voiceSessionId: String,
    val conversationId: String?,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val debuggable: Boolean,
    val voiceModelId: String,
    val providerModel: String?,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val closeStatus: String? = null,
    val sentryDsnConfigured: Boolean,
    val sentryTracingEnabled: Boolean,
    val sentryPropagationCreated: Boolean,
) {
    fun toJson(): String = buildJsonObject {
        put("voiceTraceId", voiceTraceId)
        put("voiceSessionId", voiceSessionId)
        put("conversationId", conversationId)
        put("packageName", packageName)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("debuggable", debuggable)
        put("voiceModelId", voiceModelId)
        put("providerModel", providerModel)
        put("status", status)
        put("startedAtEpochMs", startedAtEpochMs)
        put("endedAtEpochMs", endedAtEpochMs)
        put("closeStatus", closeStatus)
        put("sentryDsnConfigured", sentryDsnConfigured)
        put("sentryTracingEnabled", sentryTracingEnabled)
        put("sentryPropagationCreated", sentryPropagationCreated)
    }.toString()
}
