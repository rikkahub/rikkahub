package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole

internal fun String?.toMessageRole(): MessageRole {
    return when (this?.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> MessageRole.SYSTEM
    }
}

internal fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun isSlashDelimitedRegex(value: String): Boolean {
    return Regex("""^/(.*?)(?<!\\)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        .matches(value.trim())
}
