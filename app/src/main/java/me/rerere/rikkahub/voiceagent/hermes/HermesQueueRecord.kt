package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames

const val HERMES_TOOL_STATUS_KEY = "voice_tool_status"
const val HERMES_TOOL_JOB_ID_KEY = "voice_tool_job_id"
const val HERMES_TOOL_CREATED_AT_KEY = "voice_tool_created_at"
const val HERMES_TOOL_UPDATED_AT_KEY = "voice_tool_updated_at"
const val HERMES_TOOL_ANNOUNCEMENT_KEY = "voice_tool_announcement"

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

enum class HermesAnnouncementEvent { StillWorkingFired, VisibleMessageWritten, ResultAnnounced }

sealed interface HermesAnnouncementState {
    val wireName: String

    data object NotAnnounced : HermesAnnouncementState {
        override val wireName: String = "not_announced"
    }

    data object StillWorkingAnnounced : HermesAnnouncementState {
        override val wireName: String = "still_working_announced"
    }

    data object MessageWritten : HermesAnnouncementState {
        override val wireName: String = "message_written"
    }

    data object Announced : HermesAnnouncementState {
        override val wireName: String = "announced"
    }

    companion object {
        private val all = listOf(NotAnnounced, StillWorkingAnnounced, MessageWritten, Announced)

        fun fromWireName(value: String?): HermesAnnouncementState? =
            all.firstOrNull { it.wireName == value }
    }
}

data class HermesQueueRecord(
    val callId: String,
    val jobId: String?,
    val prompt: String,
    val status: HermesQueueStatus,
    val answer: String?,
    val error: String?,
    val announcement: HermesAnnouncementState,
    val createdAt: String?,
    val updatedAt: String?,
) {
    val resultAnnounced: Boolean
        get() = announcement == HermesAnnouncementState.Announced

    val stillWorkingAnnounced: Boolean
        get() = announcement == HermesAnnouncementState.StillWorkingAnnounced

    val messageWritten: Boolean
        get() = announcement == HermesAnnouncementState.MessageWritten

    /**
     * Legal announcement transitions. Returns null for every illegal or repeated
     * event, which is what makes each announcement at-most-once: callers write
     * back only non-null results.
     */
    fun advance(event: HermesAnnouncementEvent): HermesQueueRecord? = when (event) {
        HermesAnnouncementEvent.StillWorkingFired ->
            if (announcement == HermesAnnouncementState.NotAnnounced && !status.isTerminal) {
                copy(announcement = HermesAnnouncementState.StillWorkingAnnounced)
            } else null

        HermesAnnouncementEvent.VisibleMessageWritten ->
            if (
                status.isTerminal &&
                (announcement == HermesAnnouncementState.NotAnnounced ||
                    announcement == HermesAnnouncementState.StillWorkingAnnounced)
            ) {
                copy(announcement = HermesAnnouncementState.MessageWritten)
            } else null

        HermesAnnouncementEvent.ResultAnnounced ->
            if (announcement != HermesAnnouncementState.Announced) {
                copy(announcement = HermesAnnouncementState.Announced)
            } else null
    }

    fun matchesIdentity(callId: String, jobId: String?): Boolean =
        this.callId == callId && this.jobId == jobId

    /**
     * A record without a jobId may adopt a newly returned one while it is still
     * active, or when a canceled record receives the canceled update that carries
     * the job id a racing submit returned (see the cancel-during-submit path).
     */
    fun mayAdoptJobId(newStatus: HermesQueueStatus): Boolean =
        jobId == null && (
            !status.isTerminal ||
                (status == HermesQueueStatus.Canceled && newStatus == HermesQueueStatus.Canceled)
            )

    fun toMetadata(nowIso: String): JsonObject = buildJsonObject {
        put(HERMES_TOOL_STATUS_KEY, status.wireName)
        put(HERMES_TOOL_ANNOUNCEMENT_KEY, announcement.wireName)
        jobId?.let { put(HERMES_TOOL_JOB_ID_KEY, it) }
        put(HERMES_TOOL_CREATED_AT_KEY, createdAt ?: nowIso)
        put(HERMES_TOOL_UPDATED_AT_KEY, nowIso)
    }

    companion object {
        fun fromToolPart(part: UIMessagePart.Tool): HermesQueueRecord? {
            if (part.toolName != VoiceAgentToolNames.ASK_HERMES) return null
            val metadata = part.metadata ?: return null
            val parsedStatus = HermesQueueStatus.fromWireName(metadata.stringOrNull(HERMES_TOOL_STATUS_KEY))
            val prompt = runCatching {
                Json.parseToJsonElement(part.input).jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
            }.getOrDefault("")
            val outputText = part.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString(separator = "\n") { it.text }
                .trim()
            val status = parsedStatus ?: when {
                outputText.isNotBlank() && metadata.hasAnnouncementSignal() -> HermesQueueStatus.Failed
                else -> return null
            }
            val announcement = HermesAnnouncementState.fromWireName(
                metadata.stringOrNull(HERMES_TOOL_ANNOUNCEMENT_KEY)
            )
                ?: if (status.isTerminal) HermesAnnouncementState.Announced else HermesAnnouncementState.NotAnnounced

            return HermesQueueRecord(
                callId = part.toolCallId,
                jobId = metadata.stringOrNull(HERMES_TOOL_JOB_ID_KEY),
                prompt = prompt,
                status = status,
                answer = outputText.takeIf { status == HermesQueueStatus.Complete && it.isNotBlank() },
                error = outputText.takeIf { status != HermesQueueStatus.Complete && status.isTerminal && it.isNotBlank() },
                announcement = announcement,
                createdAt = metadata.stringOrNull(HERMES_TOOL_CREATED_AT_KEY),
                updatedAt = metadata.stringOrNull(HERMES_TOOL_UPDATED_AT_KEY),
            )
        }

        private fun JsonObject.hasAnnouncementSignal(): Boolean =
            HERMES_TOOL_ANNOUNCEMENT_KEY in this
    }
}

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
        .mapNotNull { HermesQueueRecord.fromToolPart(it) }
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

private fun JsonObject.stringOrNull(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}
