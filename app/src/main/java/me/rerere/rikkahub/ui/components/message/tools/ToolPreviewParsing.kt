package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.json.JsonInstant
import me.rerere.common.http.jsonPrimitiveOrNull

/**
 * Pure, JVM-testable parsing / JSON-argument helpers extracted out of ChatMessageTools.kt
 * (issue #106). These hold the exact logic that was previously inlined in the tool-step
 * composables so the mechanical extraction is provably behaviour-preserving.
 */

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

internal data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

internal fun parseAskUserQuestions(arguments: JsonElement): List<AskUserQuestion> =
    runCatching {
        arguments.jsonObject["questions"]?.jsonArray?.map { q ->
            val obj = q.jsonObject
            AskUserQuestion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
            )
        } ?: emptyList()
    }.getOrElse { emptyList() }

internal fun buildAskUserAnswerPayload(
    questions: List<AskUserQuestion>,
    answers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): String {
    val answerPayload = buildJsonObject {
        put("answers", buildJsonObject {
            questions.forEach { q ->
                when (q.selectionType) {
                    "multi" -> put(q.id, JsonPrimitive(multiAnswers[q.id]?.joinToString(", ") ?: ""))
                    else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                }
            }
        })
    }
    return answerPayload.toString()
}

/**
 * Parse a tool's executed output into a [JsonElement] (issue #109). Holds the exact logic
 * previously inlined in ChatMessageToolStep so the value can be memoized in the composable
 * without changing behaviour: null when the tool is not yet executed, otherwise the
 * newline-joined [UIMessagePart.Text] parts parsed as JSON, falling back to an empty
 * [JsonObject] on a parse failure.
 */
internal fun parseToolOutputContent(output: List<UIMessagePart>, isExecuted: Boolean): JsonElement? {
    if (!isExecuted) return null
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    return runCatching { JsonInstant.parseToJsonElement(text) }.getOrElse { JsonObject(emptyMap()) }
}

internal fun extractAskUserAnsweredText(rawAnswer: String, questionId: String): String {
    val answerJson = runCatching {
        JsonInstant.parseToJsonElement(rawAnswer)
    }.getOrNull()
    return answerJson?.jsonObject?.get("answers")
        ?.jsonObject?.get(questionId)?.jsonPrimitive?.contentOrNull
        ?: rawAnswer
}
