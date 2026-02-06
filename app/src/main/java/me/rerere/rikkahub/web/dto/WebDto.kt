package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val content: String,
    val parts: List<UIMessagePart>? = null
)

@Serializable
data class RegenerateRequest(
    val messageId: String
)

@Serializable
data class ToolApprovalRequest(
    val toolCallId: String,
    val approved: Boolean,
    val reason: String = ""
)

@Serializable
data class UpdateAssistantRequest(
    val assistantId: String
)

// ========== Response DTOs ==========

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean = nextOffset != null
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val truncateIndex: Int,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)

@Serializable
data class MessageNodeDto(
    val id: String,
    val messages: List<MessageDto>,
    val selectIndex: Int
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val parts: List<UIMessagePart>,
    val createdAt: String,
    val finishedAt: String? = null,
    val translation: String? = null
)

// ========== Error Response ==========

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int
)

// ========== SSE Event DTOs ==========

@Serializable
data class ConversationUpdateEvent(
    val type: String = "update",
    val conversation: ConversationDto
)

@Serializable
data class ConversationSnapshotEvent(
    val type: String = "snapshot",
    val seq: Long,
    val conversation: ConversationDto
)

@Serializable
data class ConversationNodeUpdateEvent(
    val type: String = "node_update",
    val seq: Long,
    val conversationId: String,
    val nodeId: String,
    val nodeIndex: Int,
    val node: MessageNodeDto,
    val updateAt: Long,
    val isGenerating: Boolean
)

@Serializable
data class GenerationDoneEvent(
    val type: String = "done",
    val conversationId: String
)

@Serializable
data class ErrorEvent(
    val type: String = "error",
    val message: String
)

@Serializable
data class ConversationListInvalidateEvent(
    val type: String = "invalidate",
    val assistantId: String,
    val timestamp: Long
)

// ========== Conversion Extensions ==========

fun Conversation.toListDto() = ConversationListDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    isPinned = isPinned,
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli()
)

fun Conversation.toDto(isGenerating: Boolean = false) = ConversationDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    messages = messageNodes.map { it.toDto() },
    truncateIndex = truncateIndex,
    chatSuggestions = chatSuggestions,
    isPinned = isPinned,
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)

fun MessageNode.toDto() = MessageNodeDto(
    id = id.toString(),
    messages = messages.map { it.toDto() },
    selectIndex = selectIndex
)

fun UIMessage.toDto() = MessageDto(
    id = id.toString(),
    role = role.name,
    parts = parts,
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    translation = translation
)

fun SendMessageRequest.toUIParts(): List<UIMessagePart> {
    return parts ?: listOf(UIMessagePart.Text(content))
}
