package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

@Serializable
enum class SpeakingOrder {
    Sequential,
    Random,
    Parallel
}

@Serializable
data class GroupChatParticipant(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val modelId: Uuid? = null,
    val order: Int = 0,
    val enabled: Boolean = true,
    val displayName: String? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
)

@Serializable
data class GroupChatConfig(
    val isGroupChat: Boolean = false,
    val participants: List<GroupChatParticipant> = emptyList(),
    val speakingOrder: SpeakingOrder = SpeakingOrder.Sequential,
    val groupSystemPrompt: String? = null,
    val autoDiscussEnabled: Boolean = false,
    val autoDiscussRounds: Int = 0,
    @Transient
    val autoDiscussRemaining: Int = 0,
) {
    val enabledParticipants: List<GroupChatParticipant>
        get() = participants.filter { it.enabled }.sortedBy { it.order }

    fun getParticipantById(id: Uuid): GroupChatParticipant? =
        participants.find { it.id == id }

    fun getParticipantByAssistant(assistantId: Uuid): GroupChatParticipant? =
        participants.find { it.assistantId == assistantId }
}

@Serializable
data class MessageParticipantInfo(
    val participantId: Uuid,
    val assistantId: Uuid,
    val modelId: Uuid? = null,
    val displayName: String,
)

fun extractParticipantInfoFromMessage(message: UIMessage): MessageParticipantInfo? {
    val textPart = message.parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
    val metadata = textPart?.metadata ?: return null
    val participantIdStr = metadata["participantId"]?.jsonPrimitive?.content ?: return null
    val assistantIdStr = metadata["assistantId"]?.jsonPrimitive?.content ?: return null
    val displayName = metadata["displayName"]?.jsonPrimitive?.content ?: return null
    val modelIdStr = metadata["modelId"]?.jsonPrimitive?.content

    return MessageParticipantInfo(
        participantId = Uuid.parse(participantIdStr),
        assistantId = Uuid.parse(assistantIdStr),
        modelId = modelIdStr?.let { Uuid.parse(it) },
        displayName = displayName,
    )
}
