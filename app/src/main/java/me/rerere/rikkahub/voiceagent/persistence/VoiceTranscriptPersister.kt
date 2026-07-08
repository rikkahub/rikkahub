package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import kotlin.time.Clock

internal const val VOICE_SOURCE_KEY = "voice_source"
internal const val VOICE_SOURCE_AGENT = "voice_agent"
internal const val VOICE_SESSION_ID_KEY = "voice_session_id"
internal const val VOICE_EVENT_ID_KEY = "voice_event_id"
internal const val VOICE_CALL_ID_KEY = "voice_call_id"
internal const val VOICE_STATUS_KEY = "voice_status"
internal const val VOICE_CREATED_AT_KEY = "voice_created_at"
internal const val VOICE_UPDATED_AT_KEY = "voice_updated_at"

enum class VoiceTranscriptStatus(val statusName: String) {
    Partial("partial"),
    Complete("complete"),
    Interrupted("interrupted"),
    SessionClosedBeforeFinal("session-closed-before-final"),
}

class VoiceTranscriptPersister {
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

    private companion object {
        const val VOICE_TRANSCRIPT_ROLE_KEY = "voice_transcript_role"
        const val VOICE_TRANSCRIPT_TURN_ID_KEY = "voice_transcript_turn_id"
        const val VOICE_TRANSCRIPT_USER_ROLE = "user"
        const val VOICE_TRANSCRIPT_ASSISTANT_ROLE = "assistant"
        const val VOICE_SESSION_STARTED_STATUS = "session-started"
    }
}
