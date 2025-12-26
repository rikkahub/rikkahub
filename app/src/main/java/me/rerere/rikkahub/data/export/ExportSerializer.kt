package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
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
    fun import(context: Context, uri: Uri): Result<T>

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
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

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
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

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = stLorebook.name.orEmpty(),
                description = stLorebook.description.orEmpty(),
                enabled = true,
                entries = stLorebook.entries.values.map { entry ->
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position, entry.depth),
                        content = entry.content,
                        keywords = entry.key,
                        useRegex = false, // SillyTavern 格式不支持 useRegex
                        caseSensitive = entry.caseSensitive ?: false,
                        scanDepth = entry.scanDepth ?: 5, // scanDepth 为 null 时使用默认值 5
                        constantActive = entry.constant,
                    )
                }
            )
        }.getOrNull()
    }

    private fun mapSillyTavernPosition(position: Int, depth: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2 -> InjectionPosition.TOP_OF_CHAT
            3 -> InjectionPosition.TOP_OF_CHAT // After Examples -> 聊天历史开头
            4 -> {
                // @Depth 模式：根据 depth 决定插入位置
                // depth 小（0-2）靠近底部影响大，depth 大靠近顶部影响小
                if (depth <= 2) InjectionPosition.BOTTOM_OF_CHAT
                else InjectionPosition.TOP_OF_CHAT
            }

            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
}

/**
 * SillyTavern Lorebook 格式
 */
@Serializable
private data class SillyTavernLorebook(
    val name: String? = null,
    val description: String? = null,
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
)
