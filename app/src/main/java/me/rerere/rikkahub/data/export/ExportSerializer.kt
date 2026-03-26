package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.MessageInjectionTemplate
import me.rerere.rikkahub.data.model.withNewNodeIds
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.normalizedForSystemPromptSupplement
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDateTime
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

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

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

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
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

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

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
                .normalizedForSystemPromptSupplement()
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

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
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
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

    internal fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                recursiveScanning = stLorebook.recursiveScanning ?: false,
                tokenBudget = stLorebook.tokenBudget,
                entries = stLorebook.entries.values.map { entry ->
                    val useRegex = entry.useRegex ?: (
                        entry.key.any(::isSlashDelimitedRegex) || entry.secondaryKeys.any(::isSlashDelimitedRegex)
                        )
                    val useProbability = entry.useProbability ?: true
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position),
                        injectDepth = entry.depth,
                        content = entry.content,
                        keywords = entry.key,
                        secondaryKeywords = entry.secondaryKeys,
                        selective = entry.selective,
                        useRegex = useRegex,
                        caseSensitive = entry.caseSensitive ?: false,
                        matchWholeWords = entry.matchWholeWords ?: false,
                        probability = entry.probability?.takeIf { useProbability },
                        scanDepth = entry.scanDepth ?: 4,
                        constantActive = entry.constant,
                        role = mapSillyTavernRole(entry.role),
                        stMetadata = buildMap {
                            putIfPresent("uid", entry.uid)
                            putIfPresent("displayIndex", entry.displayIndex)
                            putIfPresent("exclude_recursion", entry.excludeRecursion)
                            putIfPresent("prevent_recursion", entry.preventRecursion)
                            putIfPresent("group", entry.group)
                            putIfPresent("group_override", entry.groupOverride)
                            putIfPresent("group_weight", entry.groupWeight)
                            putIfPresent("use_group_scoring", entry.useGroupScoring)
                            putIfPresent("sticky", entry.sticky)
                            putIfPresent("cooldown", entry.cooldown)
                            putIfPresent("delay", entry.delay)
                            putIfPresent("delay_until_recursion", entry.delayUntilRecursion)
                            putIfPresent("triggers", entry.triggers)
                            putIfPresent("ignore_budget", entry.ignoreBudget)
                            putIfPresent("outlet_name", entry.outletName)
                            putIfPresent("useProbability", useProbability)
                            putIfPresent("probability", entry.probability)
                            putIfPresent("vectorized", entry.vectorized)
                            putIfPresent("automation_id", entry.automationId)
                        },
                    )
                }
            )
        }.getOrNull()
    }

    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2, 5 -> InjectionPosition.TOP_OF_CHAT
            3, 6 -> InjectionPosition.BOTTOM_OF_CHAT
            4 -> InjectionPosition.AT_DEPTH
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }

    private fun mapSillyTavernRole(role: Int?): me.rerere.ai.core.MessageRole {
        return when (role) {
            1 -> me.rerere.ai.core.MessageRole.USER
            2 -> me.rerere.ai.core.MessageRole.ASSISTANT
            else -> me.rerere.ai.core.MessageRole.SYSTEM
        }
    }
}

object MessageTemplateSerializer : ExportSerializer<MessageInjectionTemplate> {
    override val type = "message_template"

    override fun getExportFileName(data: MessageInjectionTemplate): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: MessageInjectionTemplate): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<MessageInjectionTemplate> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportNative(json)
                ?: tryImportTemplateJson(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): MessageInjectionTemplate? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<MessageInjectionTemplate>(exportData.data)
                .withNewNodeIds()
        }.getOrNull()
    }

    internal fun tryImportTemplateJson(json: String): MessageInjectionTemplate? {
        return runCatching {
            val element = ExportSerializer.DefaultJson.parseToJsonElement(json)
            val obj = element as? JsonObject ?: return null
            // Avoid accepting arbitrary JSON objects as a default template.
            if (!obj.containsKey("template")) return null

            ExportSerializer.DefaultJson
                .decodeFromJsonElement<MessageInjectionTemplate>(obj)
                .withNewNodeIds()
        }.getOrNull()
    }
}

@Serializable
private data class SillyTavernLorebook(
    @SerialName("recursive_scanning")
    val recursiveScanning: Boolean? = null,
    @SerialName("token_budget")
    val tokenBudget: Int? = null,
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val uid: Int? = null,
    val key: List<String> = emptyList(),
    @SerialName("keysecondary")
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val displayIndex: Int? = null,
    val excludeRecursion: Boolean? = null,
    val group: String? = null,
    val groupOverride: Boolean? = null,
    val groupWeight: Int? = null,
    val preventRecursion: Boolean? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val probability: Int? = null,
    val useProbability: Boolean? = null,
    val depth: Int = 4,
    val role: Int? = null,
    val vectorized: Boolean? = null,
    val delayUntilRecursion: Boolean? = null,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val useGroupScoring: Boolean? = null,
    val triggers: List<String> = emptyList(),
    val ignoreBudget: Boolean? = null,
    val outletName: String? = null,
    val useRegex: Boolean? = null,
    val automationId: String? = null,
)

private fun MutableMap<String, String>.putIfPresent(key: String, value: Any?) {
    when (value) {
        null -> {}
        is JsonElement -> {
            if (value is JsonPrimitive) {
                value.contentOrNull?.let { put(key, it) }
            } else {
                put(key, value.toString())
            }
        }
        else -> put(key, value.toString())
    }
}

private fun isSlashDelimitedRegex(value: String): Boolean {
    return Regex("""^/(.*?)(?<!\\)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        .matches(value.trim())
}
