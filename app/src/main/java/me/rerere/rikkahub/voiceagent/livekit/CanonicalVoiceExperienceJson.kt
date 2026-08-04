package me.rerere.rikkahub.voiceagent.livekit

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

internal object CanonicalVoiceExperienceJson {
    private val canonicalInstant = Regex(
        "^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:\\.(?:[0-9]{3}|[0-9]{6}|[0-9]{9}))?Z$",
    )
    private val canonicalInteger = Regex("-?(?:0|[1-9][0-9]*)")

    fun encodeObject(fields: Map<String, JsonElement>): String = encode(JsonObject(fields))

    fun isCanonicalInstant(value: String): Boolean {
        if (!canonicalInstant.matches(value) || value.substringBeforeLast('Z').endsWith("000")) {
            return false
        }
        val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return false
        return instant.toString() == value
    }

    private fun encode(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedWith { left, right ->
            compareByCodePoint(left.key, right.key)
        }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, child) ->
                Json.encodeToString(key) + ":" + encode(child)
            }

        is JsonArray -> value.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
            transform = ::encode,
        )
        is JsonPrimitive -> when {
            value.isString -> Json.encodeToString(value.content)
            value.booleanOrNull != null -> value.boolean.toString()
            value.longOrNull != null && value.content.matches(canonicalInteger) ->
                value.long.toString()

            else -> error("Unsupported canonical JSON primitive")
        }

        JsonNull -> error("Canonical voice evidence forbids null")
    }

    private fun compareByCodePoint(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = Character.codePointAt(left, leftIndex)
            val rightCodePoint = Character.codePointAt(right, rightIndex)
            if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        return left.length.compareTo(right.length)
    }
}
