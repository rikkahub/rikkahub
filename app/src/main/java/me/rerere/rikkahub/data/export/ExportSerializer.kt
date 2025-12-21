package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import kotlin.uuid.Uuid

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(exportData: ExportData): Result<T>

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 便捷方法：直接从 JSON 字符串导入
    fun importFromJson(jsonString: String, json: Json = DefaultJson): Result<T> {
        return runCatching {
            json.decodeFromString(ExportData.serializer(), jsonString)
        }.fold(
            onSuccess = { import(it) },
            onFailure = { Result.failure(it) }
        )
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object AssistantSerializer : ExportSerializer<Assistant> {
    override val type = "assistant"

    override fun export(data: Assistant): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson
                .encodeToJsonElement(data.copy(

                ))
        )
    }

    override fun import(exportData: ExportData): Result<Assistant> {
        if (exportData.type != type) {
            return Result.failure(IllegalArgumentException("Type mismatch: expected $type, got ${exportData.type}"))
        }
        return runCatching {
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Assistant>(exportData.data)
                .copy(
                    id = Uuid.random(),
                )
        }
    }
}
