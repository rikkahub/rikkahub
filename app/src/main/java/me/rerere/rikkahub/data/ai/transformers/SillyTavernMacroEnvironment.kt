package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.effectiveUserPersona
import java.time.LocalDateTime as JavaLocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class StMacroState(
    val localVariables: MutableMap<String, String> = linkedMapOf(),
    val globalVariables: MutableMap<String, String> = linkedMapOf(),
    val pickCache: MutableMap<String, Int> = linkedMapOf(),
    val outlets: MutableMap<String, String> = linkedMapOf(),
)

internal data class StMacroEnvironment(
    val user: String,
    val char: String,
    val group: String,
    val groupNotMuted: String,
    val notChar: String,
    val characterDescription: String,
    val characterPersonality: String,
    val scenario: String,
    val persona: String,
    val charPrompt: String,
    val charInstruction: String,
    val charDepthPrompt: String,
    val creatorNotes: String,
    val exampleMessagesRaw: String,
    val lastChatMessage: String,
    val lastUserMessage: String,
    val lastAssistantMessage: String,
    val modelName: String,
    val input: String = "",
    val original: String = "",
    val charVersion: String = "",
    val lastMessageId: String = "",
    val firstIncludedMessageId: String = "",
    val firstDisplayedMessageId: String = "",
    val lastSwipeId: String = "",
    val currentSwipeId: String = "",
    val maxPrompt: String = "",
    val defaultSystemPrompt: String = "",
    val systemPrompt: String = "",
    val generationType: String = "normal",
    val instructStoryStringPrefix: String = "",
    val instructStoryStringSuffix: String = "",
    val instructUserPrefix: String = "",
    val instructUserSuffix: String = "",
    val instructAssistantPrefix: String = "",
    val instructAssistantSuffix: String = "",
    val instructSystemPrefix: String = "",
    val instructSystemSuffix: String = "",
    val instructFirstAssistantPrefix: String = "",
    val instructLastAssistantPrefix: String = "",
    val instructStop: String = "",
    val instructUserFiller: String = "",
    val instructSystemInstructionPrefix: String = "",
    val instructFirstUserPrefix: String = "",
    val instructLastUserPrefix: String = "",
    val exampleSeparator: String = "",
    val chatStart: String = "",
    val isMobile: Boolean = true,
    val outlets: Map<String, String> = emptyMap(),
    val availableExtensions: Set<String> = emptySet(),
    val now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    val lastUserMessageCreatedAt: JavaLocalDateTime? = null,
) {
    fun hasExtension(name: String): Boolean {
        return name.trim().lowercase() in availableExtensions
    }

    companion object {
        fun from(
            ctx: TransformerContext,
            messages: List<UIMessage>,
            template: SillyTavernPromptTemplate?,
            characterData: SillyTavernCharacterData?,
        ): StMacroEnvironment {
            val userName = ctx.settings.effectiveUserName().ifBlank { "user" }
            val charName = ctx.assistant.name.ifBlank {
                characterData?.name?.ifBlank { "assistant" } ?: "assistant"
            }
            val chatMessages = messages.filter { it.role != MessageRole.SYSTEM }
            val lastChatMessage = chatMessages.lastOrNull()?.toText().orEmpty()
            val lastUserMessageEntry = chatMessages.lastOrNull { it.role == MessageRole.USER }
            val lastAssistantMessageEntry = chatMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val lastUserMessage = lastUserMessageEntry?.toText().orEmpty()
            val lastAssistantMessage = lastAssistantMessageEntry?.toText().orEmpty()
            val groupValue = charName
            val notCharValue = userName
            val assistantSystemPrompt = ctx.assistant.systemPrompt
            val characterSystemPrompt = characterData?.systemPromptOverride.orEmpty()
            val activeSystemPrompt = characterSystemPrompt.ifBlank { assistantSystemPrompt }

            return StMacroEnvironment(
                user = userName,
                char = charName,
                group = groupValue,
                groupNotMuted = groupValue,
                notChar = notCharValue,
                characterDescription = characterData?.description.orEmpty(),
                characterPersonality = characterData?.personality.orEmpty(),
                scenario = characterData?.scenario.orEmpty(),
                persona = ctx.settings.effectiveUserPersona(ctx.assistant),
                charPrompt = characterSystemPrompt,
                charInstruction = characterData?.postHistoryInstructions.orEmpty(),
                charDepthPrompt = characterData?.depthPrompt?.prompt.orEmpty(),
                creatorNotes = characterData?.creatorNotes.orEmpty(),
                exampleMessagesRaw = characterData?.exampleMessagesRaw.orEmpty(),
                lastChatMessage = lastChatMessage.ifBlank { lastAssistantMessage },
                lastUserMessage = lastUserMessage,
                lastAssistantMessage = lastAssistantMessage,
                modelName = ctx.model.displayName.ifBlank { template?.sourceName.orEmpty() },
                input = lastUserMessage,
                original = lastUserMessage,
                charVersion = characterData?.version.orEmpty(),
                lastMessageId = chatMessages.lastIndex.takeIf { it >= 0 }?.toString().orEmpty(),
                firstIncludedMessageId = chatMessages.firstOrNull()?.let { "0" }.orEmpty(),
                firstDisplayedMessageId = chatMessages.firstOrNull()?.let { "0" }.orEmpty(),
                maxPrompt = ctx.assistant.contextMessageSize.takeIf { it > 0 }?.toString().orEmpty(),
                defaultSystemPrompt = assistantSystemPrompt,
                systemPrompt = activeSystemPrompt,
                generationType = ctx.stGenerationType.trim().lowercase().ifBlank { "normal" },
                exampleSeparator = template?.newExampleChatPrompt.orEmpty(),
                chatStart = template?.newChatPrompt.orEmpty(),
                isMobile = true,
                outlets = ctx.stMacroState?.outlets.orEmpty(),
                now = ZonedDateTime.now(ZoneId.systemDefault()),
                lastUserMessageCreatedAt = lastUserMessageEntry?.createdAt?.toJavaLocalDateTime(),
            )
        }
    }
}
