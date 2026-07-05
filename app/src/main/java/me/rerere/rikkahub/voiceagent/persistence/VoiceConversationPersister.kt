package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_CREATED_AT_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_JOB_ID_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_RESULT_ANNOUNCED_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_SOURCE_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_STATUS_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_UPDATED_AT_KEY
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
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

enum class VoiceTranscriptStatus(val statusName: String) {
    Partial("partial"),
    Complete("complete"),
    Interrupted("interrupted"),
    SessionClosedBeforeFinal("session-closed-before-final"),
}

class VoiceConversationPersister {
    fun removeLegacyVoiceSessionStartedNotes(conversation: Conversation): Conversation {
        return conversation.copy(
            messageNodes = conversation.messageNodes.filterNot { node ->
                node.messages.any { it.isLegacyVoiceSessionStartedNote() }
            }
        )
    }

    fun appendUserTurn(
        conversation: Conversation,
        text: String,
    ): Conversation = conversation.appendMessage(UIMessage.user(text))

    fun appendAssistantTurn(
        conversation: Conversation,
        text: String,
        interrupted: Boolean,
        sessionId: String? = null,
        turnId: String? = null,
    ): Conversation {
        return conversation.appendMessage(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(
                        text = text,
                        metadata = voiceArtifactMetadata(
                            sessionId = sessionId,
                            eventId = turnId,
                            status = if (interrupted) "interrupted" else "complete",
                        ),
                    )
                ),
            )
        )
    }

    fun upsertUserTranscriptTurn(
        conversation: Conversation,
        text: String,
        turnId: String,
        sessionId: String? = null,
        status: VoiceTranscriptStatus = VoiceTranscriptStatus.Complete,
    ): Conversation = upsertTranscriptTurn(
        conversation = conversation,
        message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = voiceTranscriptMetadata(
                        role = VOICE_TRANSCRIPT_USER_ROLE,
                        turnId = turnId,
                        sessionId = sessionId,
                        status = status.statusName,
                    ),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_USER_ROLE,
        turnId = turnId,
        sessionId = sessionId,
    )

    fun upsertAssistantTranscriptTurn(
        conversation: Conversation,
        text: String,
        interrupted: Boolean,
        turnId: String,
        sessionId: String? = null,
        status: VoiceTranscriptStatus = if (interrupted) {
            VoiceTranscriptStatus.Interrupted
        } else {
            VoiceTranscriptStatus.Complete
        },
    ): Conversation = upsertTranscriptTurn(
        conversation = conversation,
        message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = voiceTranscriptMetadata(
                        role = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
                        turnId = turnId,
                        sessionId = sessionId,
                        status = status.statusName,
                    ),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
        turnId = turnId,
        sessionId = sessionId,
    )

    fun upsertHermesTool(
        conversation: Conversation,
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        sessionId: String? = null,
        jobId: String? = null,
        resultAnnounced: Boolean? = null,
    ): Conversation {
        val currentMessages = conversation.currentMessages
        val existingToolIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool &&
                    part.shouldReplaceHermesTool(callId = callId, newStatus = status, newJobId = jobId)
            }
        }
        val existingTool = if (existingToolIndex >= 0) {
            currentMessages[existingToolIndex]
                .parts
                .filterIsInstance<UIMessagePart.Tool>()
                .lastOrNull { it.shouldReplaceHermesTool(callId = callId, newStatus = status, newJobId = jobId) }
        } else {
            null
        }
        val createdAt = (existingTool?.metadata).stringOrNull(HERMES_TOOL_CREATED_AT_KEY)
        val effectiveResultAnnounced = resultAnnounced ?: existingTool?.resultAnnouncedOrDefault() ?: false
        val tool = UIMessagePart.Tool(
            toolCallId = callId,
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = JsonInstant.encodeToString(
                buildJsonObject {
                    put("prompt", prompt)
                }
            ),
            output = status.toOutputParts(
                sessionId = sessionId,
                callId = callId,
                jobId = jobId,
                resultAnnounced = effectiveResultAnnounced,
                createdAt = createdAt,
            ),
            metadata = status.toMetadata(
                sessionId = sessionId,
                callId = callId,
                jobId = jobId,
                resultAnnounced = effectiveResultAnnounced,
                createdAt = createdAt,
            ),
        )

        if (existingToolIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingToolIndex]
            updatedMessages[existingToolIndex] = existingMessage.copy(
                parts = existingMessage.parts.map { part ->
                    if (
                        part is UIMessagePart.Tool &&
                        part.shouldReplaceHermesTool(callId = callId, newStatus = status, newJobId = jobId)
                    ) {
                        tool
                    } else {
                        part
                    }
                }
            )
            return conversation.updateCurrentMessages(updatedMessages)
        }

        return conversation.appendMessage(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(tool),
            )
        )
    }

    fun markHermesToolResultAnnounced(
        conversation: Conversation,
        callId: String,
        jobId: String? = null,
        matchMissingJobId: Boolean = false,
    ): Conversation {
        return if (jobId != null || matchMissingJobId) {
            markAllMatchingHermesResultsAnnounced(
                conversation = conversation,
                callId = callId,
                jobId = jobId,
                matchMissingJobId = matchMissingJobId,
            )
        } else {
            markLatestHermesResultAnnounced(conversation = conversation, callId = callId)
        }
    }

    private fun markLatestHermesResultAnnounced(
        conversation: Conversation,
        callId: String,
    ): Conversation {
        val currentMessages = conversation.currentMessages
        val latestMessageIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool && part.isTerminalHermesTool(callId = callId)
            }
        }
        if (latestMessageIndex < 0) return conversation

        val latestPartIndex = currentMessages[latestMessageIndex].parts.indexOfLast { part ->
            part is UIMessagePart.Tool && part.isTerminalHermesTool(callId = callId)
        }
        if (latestPartIndex < 0) return conversation

        val updatedMessages = currentMessages.mapIndexed { currentMessageIndex, message ->
            if (currentMessageIndex != latestMessageIndex) {
                message
            } else {
                message.copy(
                    parts = message.parts.mapIndexed { currentPartIndex, part ->
                        if (currentPartIndex == latestPartIndex && part is UIMessagePart.Tool) {
                            part.withResultAnnounced()
                        } else {
                            part
                        }
                    }
                )
            }
        }
        return conversation.updateCurrentMessages(updatedMessages)
    }

    private fun markAllMatchingHermesResultsAnnounced(
        conversation: Conversation,
        callId: String,
        jobId: String?,
        matchMissingJobId: Boolean,
    ): Conversation {
        val currentMessages = conversation.currentMessages
        var markedAny = false
        val updatedMessages = currentMessages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (
                        part is UIMessagePart.Tool &&
                        part.isTerminalHermesTool(
                            callId = callId,
                            jobId = jobId,
                            matchMissingJobId = matchMissingJobId,
                        )
                    ) {
                        markedAny = true
                        part.withResultAnnounced()
                    } else {
                        part
                    }
                }
            )
        }
        if (!markedAny) return conversation
        return conversation.updateCurrentMessages(updatedMessages)
    }

    private fun UIMessagePart.Tool.withResultAnnounced(): UIMessagePart.Tool {
        return copy(
            metadata = metadata.withResultAnnounced(),
            output = output.map { outputPart ->
                if (outputPart is UIMessagePart.Text) {
                    outputPart.copy(metadata = outputPart.metadata.withResultAnnounced())
                } else {
                    outputPart
                }
            },
        )
    }

    private fun upsertTranscriptTurn(
        conversation: Conversation,
        message: UIMessage,
        transcriptRole: String,
        turnId: String,
        sessionId: String?,
    ): Conversation {
        if (message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.isBlank()) {
            return conversation
        }

        val currentMessages = conversation.currentMessages
        val existingIndex = currentMessages.indexOfLast {
            it.isVoiceTranscript(transcriptRole = transcriptRole, turnId = turnId, sessionId = sessionId)
        }
        if (existingIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingIndex]
            updatedMessages[existingIndex] = message.copy(id = existingMessage.id)
            return conversation.updateCurrentMessages(updatedMessages)
        }

        return conversation.appendMessage(message)
    }

    private fun Conversation.appendMessage(message: UIMessage): Conversation {
        return updateCurrentMessages(currentMessages + message)
    }

    private fun VoiceToolRecordStatus.toOutputParts(
        sessionId: String?,
        callId: String,
        jobId: String?,
        resultAnnounced: Boolean,
        createdAt: String?,
    ): List<UIMessagePart> {
        val text = when (this) {
            VoiceToolRecordStatus.Pending,
            VoiceToolRecordStatus.Queued,
            VoiceToolRecordStatus.Running,
                -> null

            is VoiceToolRecordStatus.Complete -> answer
            is VoiceToolRecordStatus.Failed -> message
            is VoiceToolRecordStatus.Expired -> message
            is VoiceToolRecordStatus.Canceled -> message
        } ?: return emptyList()

        return listOf(
            UIMessagePart.Text(
                text,
                metadata = toMetadata(
                    sessionId = sessionId,
                    callId = callId,
                    jobId = jobId,
                    resultAnnounced = resultAnnounced,
                    createdAt = createdAt,
                ),
            )
        )
    }

    private fun UIMessagePart.Tool.shouldReplaceHermesTool(
        callId: String,
        newStatus: VoiceToolRecordStatus,
        newJobId: String?,
    ): Boolean {
        if (!isHermesTool(callId)) return false
        val existingJobId = metadata.stringOrNull(HERMES_TOOL_JOB_ID_KEY)
        return if (!newStatus.queueStatus.isTerminal) {
            isActiveHermesTool() && if (newJobId == null) {
                existingJobId == null
            } else {
                existingJobId == null || existingJobId == newJobId
            }
        } else if (newJobId != null) {
            existingJobId == newJobId ||
                (existingJobId == null && canReceiveReturnedJobId(newStatus))
        } else {
            existingJobId == null
        }
    }

    private fun UIMessagePart.Tool.isHermesTool(callId: String): Boolean {
        return toolCallId == callId && toolName == VoiceAgentToolNames.ASK_HERMES
    }

    private fun UIMessagePart.Tool.isActiveHermesTool(): Boolean {
        return metadata.queueStatus()?.isTerminal == false
    }

    private fun UIMessagePart.Tool.canReceiveReturnedJobId(newStatus: VoiceToolRecordStatus): Boolean {
        return isActiveHermesTool() ||
            (metadata.queueStatus() == HermesQueueStatus.Canceled && newStatus.queueStatus == HermesQueueStatus.Canceled)
    }

    private fun UIMessagePart.Tool.isTerminalHermesTool(
        callId: String,
        jobId: String? = null,
        matchMissingJobId: Boolean = false,
    ): Boolean {
        if (!isHermesTool(callId)) return false
        val existingJobId = metadata.stringOrNull(HERMES_TOOL_JOB_ID_KEY)
        if (jobId != null && existingJobId != jobId) return false
        if (matchMissingJobId && existingJobId != null) return false
        return metadata.queueStatus()?.isTerminal == true
    }

    private fun UIMessagePart.Tool.resultAnnouncedOrDefault(): Boolean {
        val toolMetadata = metadata
        return if (toolMetadata != null && HERMES_TOOL_RESULT_ANNOUNCED_KEY in toolMetadata) {
            toolMetadata.booleanOrNull(HERMES_TOOL_RESULT_ANNOUNCED_KEY) == true
        } else {
            metadata.queueStatus()?.isTerminal == true
        }
    }

    private fun VoiceToolRecordStatus.toMetadata(
        sessionId: String?,
        callId: String,
        jobId: String?,
        resultAnnounced: Boolean,
        createdAt: String?,
    ) = buildJsonObject {
        put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
        put(HERMES_TOOL_STATUS_KEY, statusName)
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, resultAnnounced)
        jobId?.let { put(HERMES_TOOL_JOB_ID_KEY, it) }
        val timestamp = Clock.System.now().toString()
        put(HERMES_TOOL_CREATED_AT_KEY, createdAt ?: timestamp)
        put(HERMES_TOOL_UPDATED_AT_KEY, timestamp)
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = callId,
            status = statusName,
            callId = callId,
        )
    }

    private fun voiceTranscriptMetadata(
        role: String,
        turnId: String,
        sessionId: String?,
        status: String,
    ) = buildJsonObject {
        put(VOICE_TRANSCRIPT_ROLE_KEY, role)
        put(VOICE_TRANSCRIPT_TURN_ID_KEY, turnId)
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = turnId,
            status = status,
        )
    }

    private fun voiceArtifactMetadata(sessionId: String?, eventId: String?, status: String) = buildJsonObject {
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = eventId,
            status = status,
        )
    }

    private fun JsonObjectBuilder.putVoiceArtifactMetadata(
        sessionId: String?,
        eventId: String?,
        status: String,
        callId: String? = null,
    ) {
        val timestamp = Clock.System.now().toString()
        put(VOICE_STATUS_KEY, status)
        if (sessionId != null) {
            put(VOICE_SOURCE_KEY, VOICE_SOURCE_AGENT)
            put(VOICE_SESSION_ID_KEY, sessionId)
            eventId?.let { put(VOICE_EVENT_ID_KEY, it) }
            callId?.let { put(VOICE_CALL_ID_KEY, it) }
            put(VOICE_CREATED_AT_KEY, timestamp)
            put(VOICE_UPDATED_AT_KEY, timestamp)
        }
    }

    private fun JsonObject?.withResultAnnounced(): JsonObject = buildJsonObject {
        this@withResultAnnounced?.forEach { (existingKey, existingValue) -> put(existingKey, existingValue) }
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, true)
        put(HERMES_TOOL_UPDATED_AT_KEY, Clock.System.now().toString())
    }

    private fun UIMessage.isVoiceTranscript(
        transcriptRole: String,
        turnId: String,
        sessionId: String?,
    ): Boolean {
        return parts.any { part ->
            if (part !is UIMessagePart.Text) return@any false
            val metadata = part.metadata ?: return@any false
            val roleMatches = metadata[VOICE_TRANSCRIPT_ROLE_KEY]?.jsonPrimitive?.content == transcriptRole
            val turnMatches = metadata[VOICE_TRANSCRIPT_TURN_ID_KEY]?.jsonPrimitive?.content == turnId
            val existingSessionId = metadata[VOICE_SESSION_ID_KEY]?.jsonPrimitive?.content
            val sessionMatches = if (sessionId == null) {
                existingSessionId == null
            } else {
                existingSessionId == sessionId
            }
            roleMatches && turnMatches && sessionMatches
        }
    }

    private fun UIMessage.isLegacyVoiceSessionStartedNote(): Boolean {
        return parts.any { part ->
            if (part !is UIMessagePart.Text) return@any false
            val metadata = part.metadata ?: return@any false
            metadata[VOICE_SOURCE_KEY]?.jsonPrimitive?.content == VOICE_SOURCE_AGENT &&
                metadata[VOICE_STATUS_KEY]?.jsonPrimitive?.content == VOICE_SESSION_STARTED_STATUS
        }
    }

    private val VoiceToolRecordStatus.statusName: String
        get() = queueStatus.wireName

    private val VoiceToolRecordStatus.queueStatus: HermesQueueStatus
        get() = when (this) {
            VoiceToolRecordStatus.Pending -> HermesQueueStatus.Pending
            VoiceToolRecordStatus.Queued -> HermesQueueStatus.Queued
            VoiceToolRecordStatus.Running -> HermesQueueStatus.Running
            is VoiceToolRecordStatus.Complete -> HermesQueueStatus.Complete
            is VoiceToolRecordStatus.Failed -> HermesQueueStatus.Failed
            is VoiceToolRecordStatus.Expired -> HermesQueueStatus.Expired
            is VoiceToolRecordStatus.Canceled -> HermesQueueStatus.Canceled
        }

    private fun JsonObject?.queueStatus(): HermesQueueStatus? {
        return HermesQueueStatus.fromWireName(stringOrNull(HERMES_TOOL_STATUS_KEY))
    }

    private fun JsonObject?.stringOrNull(key: String): String? {
        return (this?.get(key) as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject?.booleanOrNull(key: String): Boolean? {
        return (this?.get(key) as? JsonPrimitive)?.booleanOrNull
    }

    private companion object {
        const val VOICE_SOURCE_KEY = "voice_source"
        const val VOICE_SOURCE_AGENT = "voice_agent"
        const val VOICE_SESSION_ID_KEY = "voice_session_id"
        const val VOICE_EVENT_ID_KEY = "voice_event_id"
        const val VOICE_CALL_ID_KEY = "voice_call_id"
        const val VOICE_STATUS_KEY = "voice_status"
        const val VOICE_CREATED_AT_KEY = "voice_created_at"
        const val VOICE_UPDATED_AT_KEY = "voice_updated_at"
        const val VOICE_TRANSCRIPT_ROLE_KEY = "voice_transcript_role"
        const val VOICE_TRANSCRIPT_TURN_ID_KEY = "voice_transcript_turn_id"
        const val VOICE_TRANSCRIPT_USER_ROLE = "user"
        const val VOICE_TRANSCRIPT_ASSISTANT_ROLE = "assistant"
        const val VOICE_SESSION_STARTED_STATUS = "session-started"
    }
}
