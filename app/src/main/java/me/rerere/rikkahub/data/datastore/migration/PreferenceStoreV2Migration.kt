package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

class PreferenceStoreV2Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 2
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.ASSISTANTS] = prefs[SettingsStore.ASSISTANTS]?.let { json ->
            migrateAssistantsJson(json)
        } ?: "[]"

        prefs[SettingsStore.VERSION] = 2
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

private val partTypeMapping = mapOf(
    "Text" to "text",
    "UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart.Text" to "text",
    "Image" to "image",
    "UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart.Image" to "image",
    "Video" to "video",
    "UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart.Video" to "video",
    "Audio" to "audio",
    "UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
    "Document" to "document",
    "UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart.Document" to "document",
    "Reasoning" to "reasoning",
    "UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
    "Search" to "search",
    "UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart.Search" to "search",
    "ToolCall" to "tool_call",
    "UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
    "ToolResult" to "tool_result",
    "UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
    "Tool" to "tool",
    "UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
)

private fun migrateAssistantsJson(assistantsJson: String): String {
    return runCatching {
        val element = JsonInstant.parseToJsonElement(assistantsJson)
        val root = element as? JsonArray ?: return@runCatching assistantsJson
        val migrated = JsonArray(
            root.map { assistant ->
                val assistantObj = assistant as? JsonObject ?: return@map assistant
                val presetMessages = assistantObj["presetMessages"] as? JsonArray ?: return@map assistant
                val migratedPresetMessages = JsonArray(
                    presetMessages.map { message ->
                        val messageObj = message as? JsonObject ?: return@map message
                        val parts = messageObj["parts"] as? JsonArray ?: return@map message
                        val migratedParts = migratePartsArray(parts)
                        if (migratedParts == parts) {
                            message
                        } else {
                            JsonObject(messageObj.toMutableMap().apply {
                                put("parts", migratedParts)
                            })
                        }
                    }
                )
                if (migratedPresetMessages == presetMessages) {
                    assistant
                } else {
                    JsonObject(assistantObj.toMutableMap().apply {
                        put("presetMessages", migratedPresetMessages)
                    })
                }
            }
        )
        if (migrated == root) assistantsJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { assistantsJson }
}

private fun migratePartsArray(parts: JsonArray): JsonArray {
    return JsonArray(
        parts.map { part ->
            val partObj = part as? JsonObject ?: return@map part
            val typeValue = partObj["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val mappedType = typeValue?.let { partTypeMapping[it] } ?: typeValue

            var updatedPart: JsonObject = partObj
            if (mappedType != null && mappedType != typeValue) {
                updatedPart = JsonObject(partObj.toMutableMap().apply {
                    put("type", JsonPrimitive(mappedType))
                })
            }

            val output = updatedPart["output"] as? JsonArray ?: return@map updatedPart
            val migratedOutput = migratePartsArray(output)
            if (migratedOutput == output) {
                updatedPart
            } else {
                JsonObject(updatedPart.toMutableMap().apply {
                    put("output", migratedOutput)
                })
            }
        }
    )
}
