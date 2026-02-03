package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val content: String,
    val parts: List<MessagePartDto>? = null
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
    val parts: List<MessagePartDto>,
    val createdAt: String,
    val finishedAt: String? = null,
    val translation: String? = null
)

@Serializable
sealed class MessagePartDto {
    @Serializable
    data class Text(val text: String) : MessagePartDto()

    @Serializable
    data class Image(val url: String) : MessagePartDto()

    @Serializable
    data class Video(val url: String) : MessagePartDto()

    @Serializable
    data class Audio(val url: String) : MessagePartDto()

    @Serializable
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String
    ) : MessagePartDto()

    @Serializable
    data class Reasoning(
        val reasoning: String,
        val isFinished: Boolean
    ) : MessagePartDto()

    @Serializable
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: String?,
        val isPending: Boolean,
        val isExecuted: Boolean
    ) : MessagePartDto()
}

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
data class GenerationDoneEvent(
    val type: String = "done",
    val conversationId: String
)

@Serializable
data class ErrorEvent(
    val type: String = "error",
    val message: String
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
    parts = parts.map { it.toDto() },
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    translation = translation
)

fun UIMessagePart.toDto(): MessagePartDto = when (this) {
    is UIMessagePart.Text -> MessagePartDto.Text(text)
    is UIMessagePart.Image -> MessagePartDto.Image(url)
    is UIMessagePart.Video -> MessagePartDto.Video(url)
    is UIMessagePart.Audio -> MessagePartDto.Audio(url)
    is UIMessagePart.Document -> MessagePartDto.Document(url, fileName, mime)
    is UIMessagePart.Reasoning -> MessagePartDto.Reasoning(
        reasoning = reasoning,
        isFinished = finishedAt != null
    )

    is UIMessagePart.Tool -> MessagePartDto.Tool(
        toolCallId = toolCallId,
        toolName = toolName,
        input = input,
        output = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.ifBlank { null },
        isPending = isPending,
        isExecuted = isExecuted
    )

    else -> MessagePartDto.Text("[Unsupported part type]")
}

fun MessagePartDto.toUIPart(): UIMessagePart = when (this) {
    is MessagePartDto.Text -> UIMessagePart.Text(text)
    is MessagePartDto.Image -> UIMessagePart.Image(url)
    is MessagePartDto.Video -> UIMessagePart.Video(url)
    is MessagePartDto.Audio -> UIMessagePart.Audio(url)
    is MessagePartDto.Document -> UIMessagePart.Document(url, fileName, mime)
    is MessagePartDto.Reasoning -> UIMessagePart.Reasoning(reasoning, finishedAt = null)
    is MessagePartDto.Tool -> UIMessagePart.Tool(toolCallId, toolName, input, emptyList())
}

fun SendMessageRequest.toUIParts(): List<UIMessagePart> {
    return parts?.map { it.toUIPart() } ?: listOf(UIMessagePart.Text(content))
}
