package me.rerere.rikkahub.ui.pages.assistant.detail

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Assistant

internal val CompatSettingsJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

internal const val MergeEditorExtensionName = "SillyTavernExtension-mergeEditor"

private val extensionNameDeclarationRegex = Regex(
    """(?:const|let|var)\s+extensionName\s*=\s*["']([^"']+)["']""",
    setOf(RegexOption.IGNORE_CASE)
)
private val extensionSettingsIndexRegex = Regex(
    """extensionSettings\[\s*["']([^"']+)["']\s*]""",
    setOf(RegexOption.IGNORE_CASE)
)

@Serializable
internal data class MergeEditorCaptureRule(
    val enabled: Boolean = true,
    val regex: String = "",
    val tag: String = "",
    val updateMode: String = "accumulate",
    val range: String = "",
)

@Serializable
internal data class MergeEditorConfig(
    val user: String = "Human",
    val assistant: String = "Assistant",
    @SerialName("example_user")
    val exampleUser: String = "H",
    @SerialName("example_assistant")
    val exampleAssistant: String = "A",
    val system: String = "SYSTEM",
    val separator: String = "",
    @SerialName("separator_system")
    val separatorSystem: String = "",
    @SerialName("prefill_user")
    val prefillUser: String = "Continue the conversation.",
    @SerialName("capture_enabled")
    val captureEnabled: Boolean = true,
    @SerialName("capture_rules")
    val captureRules: List<MergeEditorCaptureRule> = emptyList(),
    @SerialName("stored_data")
    val storedData: JsonObject = buildJsonObject { },
)

internal enum class DetectedStCompatPluginKind {
    MERGE_EDITOR,
    GENERIC,
}

internal data class DetectedStCompatPlugin(
    val key: String,
    val displayName: String,
    val description: String,
    val kind: DetectedStCompatPluginKind,
    val detectedInScript: Boolean,
    val hasSettings: Boolean,
)

internal fun JsonObject.toPrettyCompatJson(): String {
    return CompatSettingsJson.encodeToString(this)
}

internal fun String.parseCompatSettingsJson(): JsonObject {
    if (isBlank()) return buildJsonObject { }
    return CompatSettingsJson.parseToJsonElement(this).jsonObject
}

internal fun detectStCompatPlugins(assistant: Assistant): List<DetectedStCompatPlugin> {
    val scriptKeys = linkedSetOf<String>()
    extensionNameDeclarationRegex.findAll(assistant.stCompatScriptSource).forEach { match ->
        match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(scriptKeys::add)
    }
    extensionSettingsIndexRegex.findAll(assistant.stCompatScriptSource).forEach { match ->
        match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(scriptKeys::add)
    }

    val allKeys = linkedSetOf<String>()
    allKeys += scriptKeys
    allKeys += assistant.stCompatExtensionSettings.keys

    return allKeys.map { key ->
        when (key) {
            MergeEditorExtensionName -> DetectedStCompatPlugin(
                key = key,
                displayName = "Merge Editor",
                description = "消息合并、标签替换与捕获规则。",
                kind = DetectedStCompatPluginKind.MERGE_EDITOR,
                detectedInScript = key in scriptKeys,
                hasSettings = key in assistant.stCompatExtensionSettings,
            )

            else -> DetectedStCompatPlugin(
                key = key,
                displayName = prettifyCompatPluginName(key),
                description = "已检测到自定义插件，当前使用通用 JSON 设置面板。",
                kind = DetectedStCompatPluginKind.GENERIC,
                detectedInScript = key in scriptKeys,
                hasSettings = key in assistant.stCompatExtensionSettings,
            )
        }
    }.sortedWith(
        compareBy<DetectedStCompatPlugin> { it.kind != DetectedStCompatPluginKind.MERGE_EDITOR }
            .thenByDescending { it.detectedInScript }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
    )
}

internal fun Assistant.shouldShowMergeEditorConfig(): Boolean {
    return stCompatScriptSource.contains(MergeEditorExtensionName) ||
        stCompatExtensionSettings.containsKey(MergeEditorExtensionName)
}

internal fun Assistant.readCompatPluginSettings(key: String): JsonObject {
    return runCatching { stCompatExtensionSettings[key]?.jsonObject }.getOrNull()
        ?: buildJsonObject { }
}

internal fun Assistant.withCompatPluginSettings(
    key: String,
    settings: JsonObject,
): Assistant {
    val updated = stCompatExtensionSettings.toMutableMap().apply {
        if (settings.isEmpty()) {
            remove(key)
        } else {
            put(key, settings)
        }
    }
    return copy(stCompatExtensionSettings = JsonObject(updated))
}

internal fun Assistant.readMergeEditorConfig(): MergeEditorConfig {
    val raw = runCatching { stCompatExtensionSettings[MergeEditorExtensionName]?.jsonObject }.getOrNull()
        ?: return MergeEditorConfig()
    return runCatching {
        CompatSettingsJson.decodeFromJsonElement(MergeEditorConfig.serializer(), raw)
    }.getOrElse {
        MergeEditorConfig()
    }
}

internal fun Assistant.withMergeEditorConfig(config: MergeEditorConfig): Assistant {
    val updated = stCompatExtensionSettings.toMutableMap().apply {
        put(
            MergeEditorExtensionName,
            CompatSettingsJson.encodeToJsonElement(MergeEditorConfig.serializer(), config)
        )
    }
    return copy(stCompatExtensionSettings = JsonObject(updated))
}

private fun prettifyCompatPluginName(key: String): String {
    return key
        .removePrefix("SillyTavernExtension-")
        .replace(Regex("""[_\-.]+"""), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
        .ifBlank { "Custom Plugin" }
}
