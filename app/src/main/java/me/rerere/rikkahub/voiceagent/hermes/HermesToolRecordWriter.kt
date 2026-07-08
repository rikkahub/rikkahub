package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.persistence.VOICE_CALL_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_CREATED_AT_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_EVENT_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SESSION_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SOURCE_AGENT
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SOURCE_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_UPDATED_AT_KEY
import kotlin.time.Clock

sealed interface VoiceToolRecordStatus {
    data object Pending : VoiceToolRecordStatus
    data object Queued : VoiceToolRecordStatus
    data object Running : VoiceToolRecordStatus
    data class Complete(val answer: String) : VoiceToolRecordStatus
    data class Failed(val message: String) : VoiceToolRecordStatus
    data class Expired(val message: String) : VoiceToolRecordStatus
    data class Canceled(val message: String) : VoiceToolRecordStatus
}

internal val VoiceToolRecordStatus.queueStatus: HermesQueueStatus
    get() = when (this) {
        VoiceToolRecordStatus.Pending -> HermesQueueStatus.Pending
        VoiceToolRecordStatus.Queued -> HermesQueueStatus.Queued
        VoiceToolRecordStatus.Running -> HermesQueueStatus.Running
        is VoiceToolRecordStatus.Complete -> HermesQueueStatus.Complete
        is VoiceToolRecordStatus.Failed -> HermesQueueStatus.Failed
        is VoiceToolRecordStatus.Expired -> HermesQueueStatus.Expired
        is VoiceToolRecordStatus.Canceled -> HermesQueueStatus.Canceled
    }

private val VoiceToolRecordStatus.outputText: String?
    get() = when (this) {
        VoiceToolRecordStatus.Pending,
        VoiceToolRecordStatus.Queued,
        VoiceToolRecordStatus.Running,
            -> null

        is VoiceToolRecordStatus.Complete -> answer
        is VoiceToolRecordStatus.Failed -> message
        is VoiceToolRecordStatus.Expired -> message
        is VoiceToolRecordStatus.Canceled -> message
    }

/**
 * The single write path for Hermes tool records. Every operation is:
 * locate part -> HermesQueueRecord.fromToolPart -> typed transition -> toMetadata
 * -> re-encode. The HERMES_TOOL_* schema has exactly two homes: HermesQueueRecord
 * (both wire directions) and this writer (part placement).
 */
class HermesToolRecordWriter(
    private val nowIso: () -> String = { Clock.System.now().toString() },
) {
    fun upsertHermesTool(
        conversation: Conversation,
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        sessionId: String? = null,
        jobId: String? = null,
        announceOnWrite: Boolean = false,
    ): Conversation {
        val newStatus = status.queueStatus
        // Terminal freeze: a terminal record with this exact identity is immutable.
        val latestSameIdentity = conversation.hermesQueueRecords()
            .lastOrNull { it.matchesIdentity(callId = callId, jobId = jobId) }
        if (latestSameIdentity?.status?.isTerminal == true) return conversation

        val now = nowIso()
        val currentMessages = conversation.currentMessages
        val existingToolIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part -> part.replaceableRecord(callId, newStatus, jobId) != null }
        }
        val existingRecord = if (existingToolIndex >= 0) {
            currentMessages[existingToolIndex].parts
                .mapNotNull { it.replaceableRecord(callId, newStatus, jobId) }
                .last()
        } else {
            null
        }
        val record = HermesQueueRecord(
            callId = callId,
            jobId = jobId,
            prompt = prompt,
            status = newStatus,
            answer = (status as? VoiceToolRecordStatus.Complete)?.answer,
            error = status.outputText.takeIf { newStatus != HermesQueueStatus.Complete && newStatus.isTerminal },
            announcement = when {
                announceOnWrite -> HermesAnnouncementState.Announced
                else -> existingRecord?.announcement ?: HermesAnnouncementState.NotAnnounced
            },
            createdAt = existingRecord?.createdAt,
            updatedAt = now,
        )
        val tool = UIMessagePart.Tool(
            toolCallId = callId,
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = JsonInstant.encodeToString(
                buildJsonObject {
                    put("prompt", prompt)
                }
            ),
            output = status.outputText?.let { listOf(UIMessagePart.Text(it, metadata = null)) } ?: emptyList(),
            metadata = record.toMetadata(now).withArtifactStamps(
                sessionId = sessionId,
                callId = callId,
                now = now,
            ),
        )

        if (existingToolIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingToolIndex]
            updatedMessages[existingToolIndex] = existingMessage.copy(
                parts = existingMessage.parts.map { part ->
                    if (part.replaceableRecord(callId, newStatus, jobId) != null) tool else part
                }
            )
            return conversation.updateCurrentMessages(updatedMessages)
        }

        return conversation.updateCurrentMessages(
            currentMessages + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        )
    }

    fun markResultAnnounced(conversation: Conversation, callId: String, jobId: String?): Conversation =
        updateHermesRecord(
            conversation = conversation,
            match = { it.matchesIdentity(callId = callId, jobId = jobId) && it.status.isTerminal },
            transform = { it.advance(HermesAnnouncementEvent.ResultAnnounced) },
        )

    fun markStillWorkingAnnounced(conversation: Conversation, callId: String, jobId: String?): Conversation =
        updateHermesRecord(
            conversation = conversation,
            match = { it.matchesIdentity(callId = callId, jobId = jobId) },
            transform = { it.advance(HermesAnnouncementEvent.StillWorkingFired) },
        )

    fun markMessageWritten(conversation: Conversation, callId: String, jobId: String?): Conversation =
        updateHermesRecord(
            conversation = conversation,
            match = { it.matchesIdentity(callId = callId, jobId = jobId) },
            transform = { it.advance(HermesAnnouncementEvent.VisibleMessageWritten) },
        )

    fun updateHermesRecord(
        conversation: Conversation,
        match: (HermesQueueRecord) -> Boolean,
        transform: (HermesQueueRecord) -> HermesQueueRecord?,
    ): Conversation {
        val now = nowIso()
        var changed = false
        val updatedMessages = conversation.currentMessages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool) return@map part
                    val record = HermesQueueRecord.fromToolPart(part) ?: return@map part
                    if (!match(record)) return@map part
                    val transformed = transform(record) ?: return@map part
                    changed = true
                    part.copy(
                        metadata = buildJsonObject {
                            part.metadata?.forEach { (key, value) ->
                                if (!key.startsWith("voice_tool_")) put(key, value)
                            }
                            transformed.toMetadata(now).forEach { (key, value) -> put(key, value) }
                        }
                    )
                }
            )
        }
        if (!changed) return conversation
        return conversation.updateCurrentMessages(updatedMessages)
    }

    private fun UIMessagePart.replaceableRecord(
        callId: String,
        newStatus: HermesQueueStatus,
        newJobId: String?,
    ): HermesQueueRecord? {
        if (this !is UIMessagePart.Tool) return null
        val record = HermesQueueRecord.fromToolPart(this) ?: return null
        if (record.callId != callId) return null
        val replaceable = if (!newStatus.isTerminal) {
            !record.status.isTerminal && if (newJobId == null) {
                record.jobId == null
            } else {
                record.jobId == null || record.jobId == newJobId
            }
        } else if (newJobId != null) {
            record.jobId == newJobId || record.mayAdoptJobId(newStatus)
        } else {
            record.jobId == null
        }
        return record.takeIf { replaceable }
    }

    private fun JsonObject.withArtifactStamps(
        sessionId: String?,
        callId: String,
        now: String,
    ): JsonObject {
        if (sessionId == null) return this
        return buildJsonObject {
            this@withArtifactStamps.forEach { (key, value) -> put(key, value) }
            put(VOICE_SOURCE_KEY, VOICE_SOURCE_AGENT)
            put(VOICE_SESSION_ID_KEY, sessionId)
            put(VOICE_EVENT_ID_KEY, callId)
            put(VOICE_CALL_ID_KEY, callId)
            put(VOICE_CREATED_AT_KEY, now)
            put(VOICE_UPDATED_AT_KEY, now)
        }
    }
}
