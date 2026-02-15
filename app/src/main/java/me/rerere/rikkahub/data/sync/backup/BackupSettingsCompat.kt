package me.rerere.rikkahub.data.sync.backup

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings

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
