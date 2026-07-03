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
    fun toStatusQuestionPromptSummary(): String {
        if (active.isEmpty() && unannouncedTerminal.isEmpty()) return ""

        val completedWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Complete)
        val failedWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Failed)
        val expiredWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Expired)
        val canceledWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Canceled)

        return buildString {
            appendLine("Durable Hermes queue status:")
            active.forEach { record ->
                appendLine("- Still ${record.status.wireName}: ${record.prompt}")
            }
            if (unannouncedTerminal.isNotEmpty()) {
                appendLine(
                    "- Unannounced terminal results: " +
                        "completed=$completedWaiting, " +
                        "failed=$failedWaiting, " +
                        "expired=$expiredWaiting, " +
                        "canceled=$canceledWaiting"
                )
            }
            append(
                "When the user asks about Hermes status, answer only from this durable queue status. " +
                    "Do not invent completed results. Do not describe terminal result contents unless a later " +
                    "Hermes completion follow-up turn provides them."
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

private fun List<HermesQueueRecord>.countStatus(status: HermesQueueStatus): Int =
    count { it.status == status }

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
    val parsedStatus = HermesQueueStatus.fromWireName(metadata.stringOrNull(HERMES_TOOL_STATUS_KEY))
    val prompt = runCatching {
        Json.parseToJsonElement(input).jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")
    val outputText = output.filterIsInstance<UIMessagePart.Text>()
        .joinToString(separator = "\n") { it.text }
        .trim()
    val hasResultAnnounced = HERMES_TOOL_RESULT_ANNOUNCED_KEY in metadata
    val resultAnnounced = if (hasResultAnnounced) {
        metadata.booleanOrNull(HERMES_TOOL_RESULT_ANNOUNCED_KEY) == true
    } else {
        parsedStatus?.isTerminal == true
    }
    val status = parsedStatus ?: when {
        outputText.isNotBlank() && hasResultAnnounced -> HermesQueueStatus.Failed
        else -> return null
    }

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
