package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class VoiceSessionMetadata(
    val voiceTraceId: String,
    val voiceSessionId: String,
    val conversationId: String,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
)

data class VoiceSessionDebugDisplay(
    val traceId: String,
    val sessionId: String?,
)

data class VoiceSessionDebugLine(
    val label: String,
    val value: String,
)

class VoiceSessionMetadataStore(
    private val rootDirectory: File,
) {
    fun latestForConversation(conversationId: String): VoiceSessionMetadata? {
        return VoiceE2EArtifactPaths.rootDirectory(rootDirectory)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory -> parseSessionJson(File(directory, VoiceE2EArtifact.SessionJson.fileName)) }
            .filter { it.conversationId == conversationId }
            .maxWithOrNull(
                compareBy<VoiceSessionMetadata> { it.startedAtEpochMs }
                    .thenBy { it.endedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.voiceTraceId }
            )
    }

    private fun parseSessionJson(file: File): VoiceSessionMetadata? {
        if (!file.isFile) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            VoiceSessionMetadata(
                voiceTraceId = root.string("voiceTraceId"),
                voiceSessionId = root.string("voiceSessionId"),
                conversationId = root.string("conversationId"),
                status = root.string("status"),
                startedAtEpochMs = root.long("startedAtEpochMs"),
                endedAtEpochMs = root.longOrNull("endedAtEpochMs"),
            )
        }.getOrNull()
    }
}

fun VoiceSessionMetadata.toDebugDisplay(): VoiceSessionDebugDisplay = VoiceSessionDebugDisplay(
    traceId = voiceTraceId,
    sessionId = voiceSessionId.takeIf { it != voiceTraceId },
)

fun VoiceSessionDebugDisplay.debugLines(): List<VoiceSessionDebugLine> = buildList {
    add(VoiceSessionDebugLine(label = "Trace ID", value = traceId))
    sessionId?.let { add(VoiceSessionDebugLine(label = "Session ID", value = it)) }
}

private fun Map<String, kotlinx.serialization.json.JsonElement>.string(key: String): String =
    requireNotNull(this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) {
        "Missing $key"
    }

private fun Map<String, kotlinx.serialization.json.JsonElement>.long(key: String): Long =
    requireNotNull(longOrNull(key)) { "Missing $key" }

private fun Map<String, kotlinx.serialization.json.JsonElement>.longOrNull(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
