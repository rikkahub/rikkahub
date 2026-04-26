package me.rerere.rikkahub.data.ai.groupchat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatParticipant
import me.rerere.rikkahub.data.model.MessageParticipantInfo
import me.rerere.rikkahub.data.model.SpeakingOrder
import kotlin.uuid.Uuid

fun buildGroupRoster(
    conversation: Conversation,
    selfParticipantId: Uuid,
    getAssistant: (Uuid) -> Assistant?,
    getModelDisplayName: (Uuid?) -> String,
): String {
    val config = conversation.groupChatConfig
    val participants = config.enabledParticipants

    val lines = participants.map { p ->
        val label = getParticipantLabel(p, participants, getAssistant, getModelDisplayName)
        val isSelf = p.id == selfParticipantId
        "- ${label}${if (isSelf) "  ← you" else ""}"
    }

    return buildString {
        appendLine("You are in a group chat with multiple AI participants and one human user.")
        appendLine("Participants:")
        lines.forEach { appendLine(it) }
        appendLine()
        appendLine("The human user's messages appear as: [User said]: content")
        appendLine("Other AI participants' messages appear as: [Name said]: content")
        appendLine("Your own previous messages appear as role=assistant (no prefix).")
        appendLine("Always distinguish between the human user and other AI participants.")
        appendLine("Think independently — form your own opinions and do not simply agree with or echo others.")
        appendLine("If you disagree, say so directly and explain why. Constructive debate is encouraged.")
        appendLine("Do not repeat, summarize, or rephrase what others said unless asked.")
    }
}

fun getParticipantLabel(
    participant: GroupChatParticipant,
    allParticipants: List<GroupChatParticipant>,
    getAssistant: (Uuid) -> Assistant?,
    getModelDisplayName: (Uuid?) -> String,
): String {
    val assistant = getAssistant(participant.assistantId)
    val modelName = participant.displayName
        ?: assistant?.name?.takeIf { it.isNotBlank() }
        ?: getModelDisplayName(participant.modelId ?: assistant?.chatModelId)
        ?: participant.assistantId.toString().take(8)

    val sameModelParticipants = allParticipants.filter { p ->
        if (p.id == participant.id) return@filter false
        val pAssistant = getAssistant(p.assistantId)
        val pModelId = p.modelId ?: pAssistant?.chatModelId
        val participantModelId = participant.modelId ?: assistant?.chatModelId
        pModelId == participantModelId
    }

    if (sameModelParticipants.isNotEmpty()) {
        val index = allParticipants.indexOfFirst { it.id == participant.id } + 1
        return "$modelName #$index"
    }

    return modelName
}

fun resolveTargetParticipants(
    conversation: Conversation,
    mentionedParticipantIds: List<Uuid>? = null,
): List<GroupChatParticipant> {
    val config = conversation.groupChatConfig

    if (!config.isGroupChat || config.enabledParticipants.isEmpty()) {
        return emptyList()
    }

    val targets = if (!mentionedParticipantIds.isNullOrEmpty()) {
        val mentionedSet = mentionedParticipantIds.toSet()
        config.enabledParticipants.filter { mentionedSet.contains(it.id) }
    } else {
        config.enabledParticipants
    }

    return when (config.speakingOrder) {
        SpeakingOrder.Random -> targets.shuffled()
        SpeakingOrder.Sequential,
        SpeakingOrder.Parallel -> targets
    }
}

fun buildSystemPromptForParticipant(
    conversation: Conversation,
    participant: GroupChatParticipant,
    baseSystemPrompt: String,
    getAssistant: (Uuid) -> Assistant?,
    getModelDisplayName: (Uuid?) -> String,
): String {
    val config = conversation.groupChatConfig

    return buildString {
        if (baseSystemPrompt.isNotBlank()) {
            appendLine(baseSystemPrompt)
            appendLine()
        }

        if (config.groupSystemPrompt?.isNotBlank() == true) {
            appendLine(config.groupSystemPrompt)
            appendLine()
        }

        if (config.isGroupChat && config.enabledParticipants.size > 1) {
            val roster = buildGroupRoster(
                conversation = conversation,
                selfParticipantId = participant.id,
                getAssistant = getAssistant,
                getModelDisplayName = getModelDisplayName,
            )
            append(roster)
        }
    }
}

fun transformMessageForGroupChat(
    message: UIMessage,
    conversation: Conversation,
    selfParticipantId: Uuid,
    getParticipantInfo: (Uuid) -> MessageParticipantInfo?,
): UIMessage {
    val config = conversation.groupChatConfig
    if (!config.isGroupChat) return message

    val textContent = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }

    if (message.role == MessageRole.USER) {
        val prefixedContent = "[User said]: $textContent"
        val newParts = message.parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = prefixedContent)
            } else {
                part
            }
        }
        return message.copy(parts = newParts)
    }

    if (message.role == MessageRole.ASSISTANT) {
        val participantInfo = message.modelId?.let { getParticipantInfo(it) }
            ?: message.parts.filterIsInstance<UIMessagePart.Text>()
                .firstOrNull()
                ?.metadata
                ?.get("participantId")
                ?.toString()
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { getParticipantInfo(it) }

        if (participantInfo != null && participantInfo.participantId != selfParticipantId) {
            val senderName = participantInfo.displayName
            val strippedContent = textContent.replace(
                Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>\\s*"),
                ""
            ).trim()
            val prefixedContent = "[$senderName said]: $strippedContent"

            val newParts = message.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(text = prefixedContent)
                } else {
                    part
                }
            }
            return message.copy(role = MessageRole.USER, parts = newParts)
        }
    }

    return message
}

fun extractMentionedParticipants(
    content: String,
    conversation: Conversation,
    selfParticipantId: Uuid,
    getAssistant: (Uuid) -> Assistant?,
    getModelDisplayName: (Uuid?) -> String,
): List<Uuid> {
    val config = conversation.groupChatConfig
    if (!config.isGroupChat) return emptyList()

    val mentioned = mutableSetOf<Uuid>()
    val participants = config.enabledParticipants

    for (participant in participants) {
        if (participant.id == selfParticipantId) continue

        val label = getParticipantLabel(
            participant = participant,
            allParticipants = participants,
            getAssistant = getAssistant,
            getModelDisplayName = getModelDisplayName,
        )

        if (content.contains("@$label")) {
            mentioned.add(participant.id)
        }
    }

    return mentioned.toList()
}

data class GroupChatGenerationContext(
    val conversation: Conversation,
    val targetParticipants: List<GroupChatParticipant>,
    val speakingOrder: SpeakingOrder,
    val userMessage: UIMessage?,
    val isAutoDiscuss: Boolean = false,
    val autoDiscussRound: Int = 0,
)
