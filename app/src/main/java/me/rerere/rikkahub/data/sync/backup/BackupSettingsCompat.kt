package me.rerere.rikkahub.data.sync.backup

import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar

// Keep in sync with historical type migrations:
// - Migration_13_14 (UIMessagePart old type names -> @SerialName)
// - PreferenceStoreV1Migration (McpServerConfig old type names -> @SerialName)
private val LEGACY_TYPE_NAME_MAP = mapOf(
    // UIMessagePart legacy aliases
    "Text" to "text",
    "UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart\$Text" to "text",
    "Image" to "image",
    "UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart\$Image" to "image",
    "Video" to "video",
    "UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart\$Video" to "video",
    "Audio" to "audio",
    "UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart\$Audio" to "audio",
    "Document" to "document",
    "UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart\$Document" to "document",
    "Reasoning" to "reasoning",
    "UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart\$Reasoning" to "reasoning",
    "Search" to "search",
    "UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart\$Search" to "search",
    "ToolCall" to "tool_call",
    "UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart\$ToolCall" to "tool_call",
    "ToolResult" to "tool_result",
    "UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart\$ToolResult" to "tool_result",
    "Tool" to "tool",
    "UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart\$Tool" to "tool",

    // UIMessageAnnotation legacy aliases
    "UrlCitation" to "url_citation",
    "UIMessageAnnotation.UrlCitation" to "url_citation",
    "me.rerere.ai.ui.UIMessageAnnotation.UrlCitation" to "url_citation",
    "me.rerere.ai.ui.UIMessageAnnotation\$UrlCitation" to "url_citation",

    // ToolApprovalState legacy aliases
    "Auto" to "auto",
    "ToolApprovalState.Auto" to "auto",
    "me.rerere.ai.ui.ToolApprovalState.Auto" to "auto",
    "me.rerere.ai.ui.ToolApprovalState\$Auto" to "auto",
    "Pending" to "pending",
    "ToolApprovalState.Pending" to "pending",
    "me.rerere.ai.ui.ToolApprovalState.Pending" to "pending",
    "me.rerere.ai.ui.ToolApprovalState\$Pending" to "pending",
    "Approved" to "approved",
    "ToolApprovalState.Approved" to "approved",
    "me.rerere.ai.ui.ToolApprovalState.Approved" to "approved",
    "me.rerere.ai.ui.ToolApprovalState\$Approved" to "approved",
    "Denied" to "denied",
    "ToolApprovalState.Denied" to "denied",
    "me.rerere.ai.ui.ToolApprovalState.Denied" to "denied",
    "me.rerere.ai.ui.ToolApprovalState\$Denied" to "denied",

    // McpServerConfig legacy aliases
    "me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer" to "sse",
    "me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer" to "streamable_http",
)

internal fun decodeSettingsWithLegacyCompat(json: Json, raw: String): Settings {
    return runCatching {
        json.decodeFromString<Settings>(raw)
    }.getOrElse { primaryError ->
        // 仅在直解失败时做旧类型名归一化，避免影响新版本备份路径。
        val normalized = normalizeLegacySerializedTypeNames(raw)
        if (normalized == raw) throw primaryError
        json.decodeFromString<Settings>(normalized)
    }
}

internal fun normalizeLegacySerializedTypeNames(raw: String): String {
    var normalized = raw
    LEGACY_TYPE_NAME_MAP.forEach { (legacy, current) ->
        normalized = normalized.replace("\"$legacy\"", "\"$current\"")
    }
    return normalized
}

private const val FILE_URI_PREFIX = "file://"
private const val UPLOAD_PATH_MARKER = "/files/upload/"

data class BrokenSettingsMediaRef(
    val source: String,
    val url: String,
)

internal fun rewriteLegacyUploadUri(url: String, filesDir: File): String {
    val targetFile = resolveUploadFileFromUri(url, filesDir) ?: return url
    return targetFile.toPath().toUri().toString()
}

internal fun rewriteLegacyUploadUrisInSettings(settings: Settings, filesDir: File): Settings {
    val rewrittenUserAvatar = when (val avatar = settings.displaySetting.userAvatar) {
        is Avatar.Image -> avatar.copy(url = rewriteLegacyUploadUri(avatar.url, filesDir))
        else -> avatar
    }

    val rewrittenAssistants = settings.assistants.map { assistant ->
        rewriteAssistantMediaUris(assistant, filesDir)
    }

    return settings.copy(
        displaySetting = settings.displaySetting.copy(userAvatar = rewrittenUserAvatar),
        assistants = rewrittenAssistants
    )
}

internal fun rewriteLegacyUploadUrisInUiMessages(messages: List<UIMessage>, filesDir: File): List<UIMessage> {
    return messages.map { message ->
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Image -> part.copy(url = rewriteLegacyUploadUri(part.url, filesDir))
                    is UIMessagePart.Video -> part.copy(url = rewriteLegacyUploadUri(part.url, filesDir))
                    is UIMessagePart.Audio -> part.copy(url = rewriteLegacyUploadUri(part.url, filesDir))
                    is UIMessagePart.Document -> part.copy(url = rewriteLegacyUploadUri(part.url, filesDir))
                    else -> part
                }
            }
        )
    }
}

internal fun collectBrokenSettingsMediaRefs(settings: Settings, filesDir: File): List<BrokenSettingsMediaRef> {
    val refs = mutableListOf<BrokenSettingsMediaRef>()
    val userAvatar = settings.displaySetting.userAvatar
    if (userAvatar is Avatar.Image && isMissingLocalUploadUri(userAvatar.url, filesDir)) {
        refs.add(BrokenSettingsMediaRef("displaySetting.userAvatar", userAvatar.url))
    }

    settings.assistants.forEach { assistant ->
        val avatar = assistant.avatar
        if (avatar is Avatar.Image && isMissingLocalUploadUri(avatar.url, filesDir)) {
            refs.add(BrokenSettingsMediaRef("assistants[${assistant.id}].avatar", avatar.url))
        }
        val background = assistant.background
        if (background != null && isMissingLocalUploadUri(background, filesDir)) {
            refs.add(BrokenSettingsMediaRef("assistants[${assistant.id}].background", background))
        }
    }

    return refs
}

internal fun clearBrokenSettingsMediaRefs(settings: Settings, filesDir: File): Settings {
    val cleanedUserAvatar = when (val avatar = settings.displaySetting.userAvatar) {
        is Avatar.Image -> if (isMissingLocalUploadUri(avatar.url, filesDir)) Avatar.Dummy else avatar
        else -> avatar
    }
    val cleanedAssistants = settings.assistants.map { assistant ->
        val cleanedAvatar = when (val avatar = assistant.avatar) {
            is Avatar.Image -> if (isMissingLocalUploadUri(avatar.url, filesDir)) Avatar.Dummy else avatar
            else -> avatar
        }
        val cleanedBackground = assistant.background?.takeUnless { isMissingLocalUploadUri(it, filesDir) }
        assistant.copy(
            avatar = cleanedAvatar,
            background = cleanedBackground
        )
    }
    return settings.copy(
        displaySetting = settings.displaySetting.copy(userAvatar = cleanedUserAvatar),
        assistants = cleanedAssistants
    )
}

private fun rewriteAssistantMediaUris(assistant: Assistant, filesDir: File): Assistant {
    val rewrittenAvatar = when (val avatar = assistant.avatar) {
        is Avatar.Image -> avatar.copy(url = rewriteLegacyUploadUri(avatar.url, filesDir))
        else -> avatar
    }
    return assistant.copy(
        avatar = rewrittenAvatar,
        background = assistant.background?.let { rewriteLegacyUploadUri(it, filesDir) },
        presetMessages = rewriteLegacyUploadUrisInUiMessages(assistant.presetMessages, filesDir)
    )
}

private fun resolveUploadFileFromUri(url: String, filesDir: File): File? {
    if (!url.startsWith(FILE_URI_PREFIX)) return null
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    val markerIndex = path.indexOf(UPLOAD_PATH_MARKER)
    if (markerIndex == -1) return null
    val relativePath = path.substring(markerIndex + "/files/".length).trimStart('/')
    return resolveUploadRelativePath(filesDir, relativePath)
}

private fun resolveUploadRelativePath(filesDir: File, relativePath: String): File? {
    if (!relativePath.startsWith("upload/")) return null
    if (relativePath.contains("..")) return null
    val basePath = filesDir.toPath().normalize()
    val resolvedPath = basePath.resolve(relativePath).normalize()
    if (!resolvedPath.startsWith(basePath)) return null
    return resolvedPath.toFile()
}

private fun isMissingLocalUploadUri(url: String, filesDir: File): Boolean {
    val targetFile = resolveUploadFileFromUri(url, filesDir) ?: return false
    return !targetFile.exists()
}
