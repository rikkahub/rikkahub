package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val ST_RUNTIME_SNAPSHOT_METADATA_KEY = "rikkahub_st_runtime_snapshot"

@Serializable
data class StRuntimeSnapshot(
    val generationType: String = "normal",
    val localVariables: Map<String, String> = emptyMap(),
    val lorebookRuntimeState: LorebookRuntimeStateSnapshot = LorebookRuntimeStateSnapshot(),
)

@Serializable
data class LorebookRuntimeStateSnapshot(
    val stickyEffects: Map<Uuid, TimedLorebookEffectSnapshot> = emptyMap(),
    val cooldownEffects: Map<Uuid, TimedLorebookEffectSnapshot> = emptyMap(),
)

@Serializable
data class TimedLorebookEffectSnapshot(
    val startMessageCount: Int,
    val endMessageCount: Int,
    val protected: Boolean = false,
)

internal fun UIMessage.readStRuntimeSnapshot(): StRuntimeSnapshot? {
    return parts.asSequence()
        .mapNotNull { part -> part.metadata?.get(ST_RUNTIME_SNAPSHOT_METADATA_KEY) }
        .mapNotNull { element ->
            runCatching {
                JsonInstant.decodeFromJsonElement(StRuntimeSnapshot.serializer(), element)
            }.getOrNull()
        }
        .firstOrNull()
}

internal fun UIMessage.withStRuntimeSnapshot(snapshot: StRuntimeSnapshot?): UIMessage {
    if (parts.isEmpty()) return this

    val snapshotElement = snapshot?.let {
        JsonInstant.encodeToJsonElement(StRuntimeSnapshot.serializer(), it)
    }
    val targetIndex = parts.indexOfFirst { it !is UIMessagePart.Search }.takeIf { it >= 0 } ?: return this
    var stored = false

    return copy(
        parts = parts.mapIndexed { index, part ->
            val updatedMetadata = part.metadata
                .orEmpty()
                .toMutableMap()
                .apply {
                    remove(ST_RUNTIME_SNAPSHOT_METADATA_KEY)
                    if (index == targetIndex && snapshotElement != null && !stored) {
                        put(ST_RUNTIME_SNAPSHOT_METADATA_KEY, snapshotElement)
                        stored = true
                    }
                }
                .toJsonObjectOrNull()
            part.copyWithMetadata(updatedMetadata)
        }
    )
}

private fun UIMessagePart.copyWithMetadata(metadata: JsonObject?): UIMessagePart {
    return when (this) {
        is UIMessagePart.Text -> copy(metadata = metadata)
        is UIMessagePart.Image -> copy(metadata = metadata)
        is UIMessagePart.Video -> copy(metadata = metadata)
        is UIMessagePart.Audio -> copy(metadata = metadata)
        is UIMessagePart.Document -> copy(metadata = metadata)
        is UIMessagePart.Reasoning -> copy(metadata = metadata)
        is UIMessagePart.ToolCall -> copy(metadata = metadata)
        is UIMessagePart.ToolResult -> copy(metadata = metadata)
        is UIMessagePart.Tool -> copy(metadata = metadata)
        is UIMessagePart.Search -> this
    }
}

private fun Map<String, JsonElement>.toJsonObjectOrNull(): JsonObject? {
    return if (isEmpty()) {
        null
    } else {
        JsonObject(this)
    }
}
