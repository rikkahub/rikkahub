package me.rerere.rikkahub.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

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

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(exportData: ExportData): Result<PromptInjection.ModeInjection> {
        if (exportData.type != type) {
            return Result.failure(IllegalArgumentException("Type mismatch: expected $type, got ${exportData.type}"))
        }
        return runCatching {
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(exportData: ExportData): Result<Lorebook> {
        if (exportData.type != type) {
            return Result.failure(IllegalArgumentException("Type mismatch: expected $type, got ${exportData.type}"))
        }
        return runCatching {
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }
    }
}
