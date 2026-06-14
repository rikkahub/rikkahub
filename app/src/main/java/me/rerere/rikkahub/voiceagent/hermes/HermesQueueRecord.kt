package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames

const val HERMES_TOOL_SOURCE_KEY = "voice_tool_source"
const val HERMES_TOOL_STATUS_KEY = "voice_tool_status"
const val HERMES_TOOL_JOB_ID_KEY = "voice_tool_job_id"
const val HERMES_TOOL_CREATED_AT_KEY = "voice_tool_created_at"
const val HERMES_TOOL_UPDATED_AT_KEY = "voice_tool_updated_at"
const val HERMES_TOOL_RESULT_ANNOUNCED_KEY = "voice_tool_result_announced"

enum class HermesQueueStatus(val wireName: String) {
    Pending("pending"),
    Queued("queued"),
    Running("running"),
    Complete("complete"),
    Failed("failed"),
    Expired("expired"),
    Canceled("canceled");

    val isTerminal: Boolean
        get() = this == Complete || this == Failed || this == Expired || this == Canceled

    companion object {
        fun fromWireName(value: String?): HermesQueueStatus? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

data class HermesQueueRecord(
    val callId: String,
    val jobId: String?,
    val prompt: String,
    val status: HermesQueueStatus,
    val answer: String?,
    val error: String?,
    val resultAnnounced: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)

data class HermesQueueSnapshot(
    val active: List<HermesQueueRecord>,
    val unannouncedTerminal: List<HermesQueueRecord>,
    val announcedTerminal: List<HermesQueueRecord>,
) {
    val hasPromptSummary: Boolean
        get() = active.isNotEmpty() || unannouncedTerminal.isNotEmpty()

    val hasActivePromptSummary: Boolean
        get() = active.isNotEmpty()

    fun toActivePromptSummary(): String {
        if (!hasActivePromptSummary) return ""
        return buildString {
            appendLine("Durable Hermes queue status:")
            active.forEach { record ->
                appendLine("- Still ${record.status.wireName}: ${record.prompt}")
            }
            append("If there are active queue items above, briefly tell the user they are still in progress.")
        }.trim()
    }

    fun toPromptSummary(): String {
        if (!hasPromptSummary) return ""
        return buildString {
            appendLine("Durable Hermes queue status:")
            active.forEach { record ->
                appendLine("- Still ${record.status.wireName}: ${record.prompt}")
            }
            unannouncedTerminal.forEach { record ->
                when (record.status) {
                    HermesQueueStatus.Complete -> appendLine(
                        "- Completed: ${record.prompt}\n  Hermes answer: ${record.answer.orEmpty()}"
                    )

                    HermesQueueStatus.Failed,
                    HermesQueueStatus.Expired,
                    HermesQueueStatus.Canceled,
                        -> appendLine(
                            "- ${record.status.wireName.replaceFirstChar { it.uppercase() }}: " +
                                "${record.prompt}\n  Reason: ${record.error.orEmpty()}"
                        )

                    HermesQueueStatus.Pending,
                    HermesQueueStatus.Queued,
                    HermesQueueStatus.Running,
                        -> Unit
                }
            }
            append(
                "Briefly tell the user about any completed, failed, expired, or canceled Hermes queue items above. " +
                    "Do not repeat terminal results after they have already been announced."
            )
        }.trim()
    }

    companion object {
        fun from(conversation: Conversation): HermesQueueSnapshot {
            val records = conversation.hermesQueueRecords().latestByHermesDurableIdentity()
            return HermesQueueSnapshot(
                active = records.filter { !it.status.isTerminal },
                unannouncedTerminal = records.filter { it.status.isTerminal && !it.resultAnnounced },
                announcedTerminal = records.filter { it.status.isTerminal && it.resultAnnounced },
            )
        }
    }
}

fun Conversation.hermesQueueRecords(): List<HermesQueueRecord> {
    return currentMessages
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .mapNotNull { it.toHermesQueueRecord() }
}

internal fun List<HermesQueueRecord>.latestByHermesDurableIdentity(): List<HermesQueueRecord> {
    val seen = mutableSetOf<HermesDurableIdentity>()
    return asReversed()
        .filter { record -> seen.add(record.durableIdentity()) }
        .asReversed()
}

private data class HermesDurableIdentity(
    val callId: String,
    val jobId: String?,
)

private fun HermesQueueRecord.durableIdentity(): HermesDurableIdentity {
    return HermesDurableIdentity(callId = callId, jobId = jobId)
}

private fun UIMessagePart.Tool.toHermesQueueRecord(): HermesQueueRecord? {
    if (toolName != VoiceAgentToolNames.ASK_HERMES) return null
    val metadata = metadata ?: return null
    val status = HermesQueueStatus.fromWireName(metadata.stringOrNull(HERMES_TOOL_STATUS_KEY))
        ?: return null
    val prompt = runCatching {
        Json.parseToJsonElement(input).jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")
    val outputText = output.filterIsInstance<UIMessagePart.Text>()
        .joinToString(separator = "\n") { it.text }
        .trim()
    val resultAnnounced = metadata.booleanOrNull(HERMES_TOOL_RESULT_ANNOUNCED_KEY)
        ?: status.isTerminal

    return HermesQueueRecord(
        callId = toolCallId,
        jobId = metadata.stringOrNull(HERMES_TOOL_JOB_ID_KEY),
        prompt = prompt,
        status = status,
        answer = outputText.takeIf { status == HermesQueueStatus.Complete && it.isNotBlank() },
        error = outputText.takeIf { status != HermesQueueStatus.Complete && status.isTerminal && it.isNotBlank() },
        resultAnnounced = resultAnnounced,
        createdAt = metadata.stringOrNull(HERMES_TOOL_CREATED_AT_KEY),
        updatedAt = metadata.stringOrNull(HERMES_TOOL_UPDATED_AT_KEY),
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.booleanOrNull(key: String): Boolean? {
    return (this[key] as? JsonPrimitive)?.booleanOrNull
}
