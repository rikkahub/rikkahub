package me.rerere.rikkahub.web.a2a

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isPendingToolApprovalFor
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal val A2A_FINAL_ARTIFACT_NODE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

fun Settings.toA2aAgentCard(baseUrl: String, jwtRequired: Boolean): AgentCard = AgentCard(
    name = "RikkaHub A2A Agent",
    description = "RikkaHub assistant card",
    url = baseUrl,
    capabilities = AgentCapabilities(),
    skills = assistants
        .filter { it.spawnable }
        .map { it.toA2aSkill() },
    securitySchemes = if (jwtRequired) {
        mapOf("bearerAuth" to AgentSecurityScheme())
    } else {
        emptyMap()
    },
    security = if (jwtRequired) {
        listOf(mapOf("bearerAuth" to listOf("write:agent")))
    } else {
        emptyList()
    },
)

fun Assistant.toA2aSkill(): AgentSkill = AgentSkill(
    id = id.toString(),
    name = name,
    description = description,
)

fun A2aMessage.toUiTextParts(): List<UIMessagePart> = parts
    .filterIsInstance<A2aPart.TextPart>()
    .map { part ->
        UIMessagePart.Text(
            text = part.text,
            metadata = part.metadata,
        )
    }

fun Conversation.pendingA2aInputRequests(): List<A2aInputRequest> = currentMessages
    .flatMap { message ->
        message.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.approvalState is ToolApprovalState.Pending }
            .map { part ->
                A2aInputRequest(
                    toolCallId = part.toolCallId,
                    toolName = part.toolName,
                    prompt = part.input,
                )
            }
    }

private fun A2aTaskEntry.latestA2aTextArtifact(): A2aArtifact? {
    val text = lastSentTextByNode[A2A_FINAL_ARTIFACT_NODE_ID]?.takeIf { it.isNotBlank() }
        ?: lastSentTextByNode
            .entries
            .sortedBy { it.key.toString() }
            .map { it.value }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    if (text.isEmpty()) {
        return null
    }

    return A2aArtifact(
        artifactId = taskId,
        parts = listOf(A2aPart.TextPart(text = text)),
    )
}

fun A2aTaskEntry.toA2aTask(
    conversation: Conversation?,
): A2aTask = A2aTask(
    id = taskId,
    contextId = contextId.toString(),
    status = toStatus(
        now = System.currentTimeMillis(),
        input = conversation?.pendingA2aInputRequests() ?: emptyList(),
        message = conversation?.latestA2aTextMessage(),
    ),
    artifacts = latestA2aTextArtifact()?.let(::listOf) ?: emptyList(),
)

fun A2aTaskEntry.toStatus(
    now: Long,
    input: List<A2aInputRequest> = emptyList(),
    message: A2aMessage? = null,
): A2aTaskStatus = A2aTaskStatus(
    state = state,
    message = message,
    input = input,
    timestamp = now,
)

fun validateSpawnableSkill(settings: Settings, skillId: String): Assistant {
    val assistantId = runCatching { Uuid.parse(skillId) }
        .getOrElse { throw IllegalArgumentException("invalid skill id: $skillId") }

    val assistant = settings.getAssistantById(assistantId)
        ?: throw IllegalArgumentException("skill not found: $skillId")

    if (!assistant.spawnable) {
        throw IllegalArgumentException("skill is not spawnable: $skillId")
    }

    return assistant
}

fun validatePendingApproval(conversation: Conversation, approval: A2aToolApproval) {
    val isPending = conversation.currentMessages
        .flatMap { it.parts }
        .any { it.isPendingToolApprovalFor(approval.toolCallId) }

    if (!isPending) {
        throw IllegalArgumentException("tool call is not pending: ${approval.toolCallId}")
    }
}

internal fun Conversation.latestA2aTextMessage(): A2aMessage? {
    val message = currentMessages.lastOrNull() ?: return null
    val textParts = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .toList()
    if (textParts.isEmpty()) {
        return null
    }

    return A2aMessage(
        messageId = message.id.toString(),
        role = when (message.role) {
            MessageRole.USER -> A2aRole.USER
            MessageRole.ASSISTANT -> A2aRole.AGENT
            MessageRole.TOOL, MessageRole.SYSTEM -> A2aRole.USER
        },
        parts = textParts.map { part ->
            A2aPart.TextPart(
                text = part.text,
                metadata = part.metadata,
            )
        },
        taskId = message.id.toString(),
        contextId = id.toString(),
        metadata = textParts.firstOrNull()?.metadata,
    )
}
