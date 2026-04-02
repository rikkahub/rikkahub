package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal const val RIKKAHUB_INLINE_PROMPT_REGEXES_KEY = "rikkahub_inline_prompt_regexes"

internal fun String?.toMessageRole(): MessageRole {
    return when (this?.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> MessageRole.SYSTEM
    }
}

internal fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.hasRikkaHubInlinePromptRegexMarker(): Boolean {
    return this["extensions"]
        ?.jsonObjectOrNull()
        ?.get(RIKKAHUB_INLINE_PROMPT_REGEXES_KEY)
        ?.jsonPrimitiveOrNull
        ?.booleanOrNull
        ?: false
}

internal fun isSlashDelimitedRegex(value: String): Boolean {
    return Regex("""^/(.*?)(?<!\\)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        .matches(value.trim())
}
