package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val parts: List<UIMessagePart>,
    val modeInjectionIds: List<String>? = null,
    val lorebookIds: List<String>? = null,
)

@Serializable
data class RegenerateRequest(
    val messageId: String
)

@Serializable
data class ToolApprovalRequest(
    val toolCallId: String,
    val approved: Boolean,
    val reason: String = "",
    val answer: String? = null,
)

@Serializable
data class EditMessageRequest(
    val parts: List<UIMessagePart>
)

@Serializable
data class ForkConversationRequest(
    val messageId: String
)

@Serializable
data class SelectMessageNodeRequest(
    val selectIndex: Int
)

@Serializable
data class MoveConversationRequest(
    val assistantId: String
)

@Serializable
data class UpdateConversationTitleRequest(
    val title: String
)

@Serializable
data class UpdateConversationInjectionsRequest(
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
)

@Serializable
data class CreateFolderRequest(
    val name: String
)

@Serializable
data class RenameFolderRequest(
    val name: String
)

@Serializable
data class MoveConversationToFolderRequest(
    // null 表示移出文件夹（未归类）
    val folderId: String? = null
)

@Serializable
data class UpdateAssistantRequest(
    val assistantId: String
)

@Serializable
data class UpdateAssistantModelRequest(
    val assistantId: String,
    val modelId: String,
)

@Serializable
data class UpdateAssistantReasoningLevelRequest(
    val assistantId: String,
    val reasoningLevel: ReasoningLevel,
)

@Serializable
data class UpdateAssistantMcpServersRequest(
    val assistantId: String,
    val mcpServerIds: List<String>,
)

@Serializable
data class UpdateAssistantInjectionsRequest(
    val assistantId: String,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val quickMessageIds: List<String> = emptyList(),
)

@Serializable
data class UpdateSearchEnabledRequest(
    val assistantId: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateSearchServiceRequest(
    val index: Int,
)

@Serializable
data class UpdateBuiltInToolRequest(
    val modelId: String,
    val tool: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateFavoriteModelsRequest(
    val modelIds: List<String>,
)

@Serializable
data class WebAuthTokenRequest(
    val password: String,
)

// ========== Response DTOs ==========

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val folderId: String? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)

@Serializable
data class FolderDto(
    val id: String,
    val assistantId: String,
    val name: String,
    val sortIndex: Int,
    val createAt: Long,
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean = nextOffset != null
)

@Serializable
data class UploadedFileDto(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String,
    val size: Long
)

@Serializable
data class UploadFilesResponseDto(
    val files: List<UploadedFileDto>
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val customSystemPrompt: String? = null,
    val modeInjectionIds: List<String> = emptyList(),
    val lorebookIds: List<String> = emptyList(),
    val workspaceCwd: String? = null,
    val folderId: String? = null,
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
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: String,
    val finishedAt: String? = null,
    val modelId: String? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null
)

@Serializable
data class ForkConversationResponse(
    val conversationId: String
)

@Serializable
data class MessageSearchResultDto(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Long,
    val snippet: String,
)

@Serializable
data class WebAuthTokenResponse(
    val token: String,
    val expiresAt: Long,
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
    val conversation: ConversationDto,
    val serverTime: Long = System.currentTimeMillis()
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
    val isGenerating: Boolean,
    val serverTime: Long = System.currentTimeMillis()
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

@Serializable
data class FolderListEvent(
    val assistantId: String,
    val folders: List<FolderDto>,
)

// ========== Conversion Extensions ==========

fun Conversation.toListDto(isGenerating: Boolean = false) = ConversationListDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    isPinned = isPinned,
    folderId = folderId?.toString(),
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)

fun me.rerere.rikkahub.data.model.Folder.toDto() = FolderDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)

fun Conversation.toDto(isGenerating: Boolean = false) = ConversationDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    messages = messageNodes.map { it.toDto() },
    chatSuggestions = chatSuggestions,
    isPinned = isPinned,
    customSystemPrompt = customSystemPrompt,
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    workspaceCwd = workspaceCwd,
    folderId = folderId?.toString(),
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
    annotations = annotations,
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    modelId = modelId?.toString(),
    usage = usage,
    translation = translation
)
