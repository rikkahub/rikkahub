package me.rerere.rikkahub.voiceagent.persistence

import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_STATUS_KEY
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAccessor
import java.util.Locale
import java.util.TimeZone

data class VoiceContext(
    val systemInstruction: String,
    val turns: List<GeminiContentTurn>,
)

data class VoicePromptPlaceholderValues(
    val now: () -> LocalDateTime = { LocalDateTime.now() },
    val locale: () -> Locale = { Locale.getDefault() },
    val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    val systemVersion: () -> String = { "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})" },
    val deviceInfo: () -> String = { "${Build.BRAND} ${Build.MODEL}".trim().ifBlank { "Android device" } },
    val batteryLevel: () -> String = { "unknown" },
    val user: () -> String = { "user" },
)

class VoiceContextBuilder(
    private val placeholderValues: VoicePromptPlaceholderValues = VoicePromptPlaceholderValues(),
) {
    fun build(
        assistantName: String,
        assistantPrompt: String,
        conversation: Conversation,
        maxTurns: Int = 20,
        voiceModelName: String = "Gemini Live",
        userNickname: String = "",
    ): VoiceContext {
        val turns = conversation.currentMessages
            .mapNotNull { message -> message.toGeminiTurn() }
            .foldAdjacentSameRoleTurns()
        val leadingSummaryTurns = turns.takeWhile { turn ->
            turn.role == GEMINI_USER_ROLE && turn.text.looksLikeCompressedSummary()
        }
        val recentBudget = (maxTurns - leadingSummaryTurns.size).coerceAtLeast(0)
        val recentTurns = turns
            .drop(leadingSummaryTurns.size)
            .takeLast(recentBudget)
        val hermesQueueSnapshot = HermesQueueSnapshot.from(conversation)
        return VoiceContext(
            systemInstruction = buildSystemInstruction(
                assistantName = assistantName,
                assistantPrompt = assistantPrompt,
                voiceModelName = voiceModelName,
                userNickname = userNickname,
                hermesQueueSummary = hermesQueueSnapshot.toStatusQuestionPromptSummary(),
            ),
            turns = (leadingSummaryTurns + recentTurns).takeLast(maxTurns),
        )
    }

    private fun buildSystemInstruction(
        assistantName: String,
        assistantPrompt: String,
        voiceModelName: String,
        userNickname: String,
        hermesQueueSummary: String,
    ): String {
        val renderedAssistantPrompt = assistantPrompt.renderVoicePlaceholders(
            assistantName = assistantName,
            voiceModelName = voiceModelName,
            userNickname = userNickname,
        )
        return buildString {
            appendLine("You are $assistantName in RikkaHub voice mode.")
            append(VOICE_HERMES_TOOL_POLICY)
            if (hermesQueueSummary.isNotBlank()) {
                appendLine()
                appendLine()
                append(hermesQueueSummary)
            }
            if (renderedAssistantPrompt.isNotBlank()) {
                appendLine()
                appendLine()
                append(renderedAssistantPrompt)
            }
        }
    }

    private fun UIMessage.toGeminiTurn(): GeminiContentTurn? {
        val text = parts
            .mapNotNull { part -> part.toContextText() }
            .joinToString(separator = "\n")
            .trim()

        if (text.isBlank()) return null

        return GeminiContentTurn(
            role = if (role == MessageRole.ASSISTANT) GEMINI_MODEL_ROLE else GEMINI_USER_ROLE,
            text = text,
        )
    }

    private fun List<GeminiContentTurn>.foldAdjacentSameRoleTurns(): List<GeminiContentTurn> {
        return fold(emptyList()) { folded, turn ->
            val previous = folded.lastOrNull()
            if (previous?.role == turn.role && turn.role == GEMINI_MODEL_ROLE) {
                folded.dropLast(1) + previous.copy(text = previous.text + "\n\n" + turn.text)
            } else {
                folded + turn
            }
        }
    }

    private fun UIMessagePart.toContextText(): String? = when (this) {
        is UIMessagePart.Text -> text
        is UIMessagePart.Tool -> toContextText()
        else -> null
    }

    private fun UIMessagePart.Tool.toContextText(): String? {
        if (!isExecuted) return null
        if (shouldHideHermesToolOutputFromContext()) return null

        val outputText = output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
            .trim()

        if (outputText.isBlank()) return null

        return "Tool $toolName ($toolCallId)\nInput: $input\nOutput: $outputText"
    }

    private fun UIMessagePart.Tool.shouldHideHermesToolOutputFromContext(): Boolean {
        if (toolName != VoiceAgentToolNames.ASK_HERMES) return false
        val metadata = metadata ?: return false
        val status = HermesQueueStatus.fromWireName(metadata.stringOrNull(HERMES_TOOL_STATUS_KEY))
            ?: return hasTextOutput()
        return status.isTerminal
    }

    private fun UIMessagePart.Tool.hasTextOutput(): Boolean {
        return output
            .filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
    }

    private fun String.looksLikeCompressedSummary(): Boolean {
        val head = trim().take(SUMMARY_DETECTION_PREFIX_LENGTH).lowercase()
        return head.contains("summary") ||
            head.contains("summarized") ||
            head.contains("conversation so far") ||
            head.contains("previous conversation")
    }

    private fun String.renderVoicePlaceholders(
        assistantName: String,
        voiceModelName: String,
        userNickname: String,
    ): String {
        val now = placeholderValues.now()
        val locale = placeholderValues.locale()
        val user = userNickname.ifBlank { placeholderValues.user() }.ifBlank { "user" }
        val values = mapOf<String, () -> String>(
            "cur_date" to { now.toLocalDate().formatLocalizedDate(locale) },
            "cur_time" to { now.toLocalTime().formatLocalizedTime(locale) },
            "cur_datetime" to { now.formatLocalizedDateTime(locale) },
            "model_id" to { voiceModelName },
            "model_name" to { voiceModelName },
            "locale" to { locale.displayName },
            "timezone" to { placeholderValues.timeZone().displayName },
            "system_version" to placeholderValues.systemVersion,
            "device_info" to placeholderValues.deviceInfo,
            "battery_level" to placeholderValues.batteryLevel,
            "char" to { assistantName.ifBlank { "assistant" } },
            "user" to { user },
            "nickname" to { user },
        )

        return values.entries.fold(this) { current, (key, valueProvider) ->
            val hasBracePlaceholder = current.contains("{{$key}}", ignoreCase = true)
            val hasSingleBracePlaceholder = current.contains("{$key}", ignoreCase = true)
            if (!hasBracePlaceholder && !hasSingleBracePlaceholder) {
                current
            } else {
                val value = valueProvider()
                current
                    .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                    .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun LocalDate.formatLocalizedDate(locale: Locale): String =
        formatLocalized(locale, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    private fun LocalTime.formatLocalizedTime(locale: Locale): String =
        formatLocalized(locale, DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))

    private fun LocalDateTime.formatLocalizedDateTime(locale: Locale): String =
        formatLocalized(locale, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))

    private fun TemporalAccessor.formatLocalized(locale: Locale, formatter: DateTimeFormatter): String =
        formatter.withLocale(locale).format(this)

    private companion object {
        const val GEMINI_USER_ROLE = "user"
        const val GEMINI_MODEL_ROLE = "model"
        const val SUMMARY_DETECTION_PREFIX_LENGTH = 240
        private const val VOICE_HERMES_TOOL_POLICY =
            "Hermes is the source of truth for substantive answers in RikkaHub voice mode.\n" +
                "You are the voice interface to Hermes, not a replacement for Hermes.\n\n" +
                "Treat every user request as substantive unless it is clearly one of the direct-answer exceptions below.\n" +
                "Default rule: if the user asks for any substantive answer that was not already " +
                "provided by Hermes in this session, you MUST call ask_hermes before answering.\n" +
                "When in doubt, call ask_hermes.\n\n" +
                "A request is substantive if answering it would require facts, explanation, advice, status, state, memory, code or project context, access or authorization details, debugging, plans, decisions, or any knowledge outside this small interaction.\n" +
                "Substantive answers include facts, state, context, memory, code, projects, " +
                "decisions, status, access, debugging, plans, and questions such as \"do we\", " +
                "\"did we\", or \"are we\". These examples clarify the rule; they are not the full boundary.\n\n" +
                "If you would otherwise say that you do not know, do not have access, cannot check, or can only give generic advice, call ask_hermes instead.\n" +
                "Do not use your general knowledge to answer ordinary factual, explanatory, or advice questions. Route them to Hermes with ask_hermes.\n" +
                "Do not answer substantive questions from your own general knowledge, assumptions, " +
                "generic advice, or troubleshooting steps. If speech transcription is imperfect but " +
                "the user's intent appears substantive or Hermes-related, call ask_hermes with the " +
                "best-effort question.\n\n" +
                "Answer directly only for greetings, brief acknowledgements, voice controls, " +
                "clarification questions, or restating, interpreting, and summarizing information " +
                "Hermes already provided in the current session.\n\n" +
                "Multiple Hermes requests may be pending at the same time. If the user asks a new independent substantive question while another Hermes request is pending, call ask_hermes again for the new question instead of waiting for the earlier request.\n" +
                "While any Hermes request is pending, stay conversational, but only discuss interaction-level responses, clarifying questions, pending-request status, or Hermes answers that have already arrived.\n" +
                "Never answer the factual content of a pending Hermes request yourself.\n\n" +
                "If ask_hermes returns that Hermes has not answered yet or that the request is pending, " +
                "briefly acknowledge that you are checking Hermes for that request. Do not say this is the final answer. Continue the conversation if the user asks something else, and create additional Hermes requests for additional substantive questions.\n\n" +
                "When a Hermes completion follow-up arrives, connect the answer to the original request and summarize the Hermes answer naturally and briefly."
    }
}
