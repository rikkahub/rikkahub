package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.stExtension

internal data class StRuntimePromptBehavior(
    val chatHistoryMessages: List<UIMessage>,
    val controlMessages: List<UIMessage> = emptyList(),
)

internal fun applyGenerationTypeRuntimeBehavior(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
    generationType: String,
): StRuntimePromptBehavior {
    return when (generationType) {
        "continue" -> buildContinueRuntimeBehavior(chatHistoryMessages, template)
        "impersonate" -> buildImpersonationRuntimeBehavior(chatHistoryMessages, template)
        else -> StRuntimePromptBehavior(chatHistoryMessages = chatHistoryMessages)
    }
}

internal fun applyNamesBehaviorToChatHistory(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    if (template.namesBehavior != 1 && template.namesBehavior != 2) {
        return chatHistoryMessages
    }

    return chatHistoryMessages.map { message ->
        when (message.role) {
            MessageRole.USER -> prefixMessageContent(message, "{{user}}")
            MessageRole.ASSISTANT -> prefixMessageContent(message, "{{char}}")
            else -> message
        }
    }
}

internal fun updateActiveLorebookOutlets(
    entries: List<PromptInjection.RegexInjection>,
    stMacroState: StMacroState?,
) {
    val outlets = stMacroState?.outlets ?: return
    outlets.clear()
    entries
        .asSequence()
        .filter { it.position == InjectionPosition.OUTLET }
        .groupBy { entry ->
            entry.stExtension().outletName
                .takeIf { it.isNotBlank() }
                ?: entry.name.trim().takeIf { it.isNotEmpty() }
                ?: return@groupBy ""
        }
        .forEach { (name, groupedEntries) ->
            if (name.isBlank()) return@forEach
            val content = groupedEntries
                .sortedByDescending { it.priority }
                .mapNotNull { it.content.trim().takeIf(String::isNotBlank) }
                .joinToString("\n")
                .trim()
            if (content.isNotEmpty()) {
                outlets[name] = content
            }
        }
}

internal fun collectLeadingSystemMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    val leadingSystemMessages = messages.takeWhile { it.role == MessageRole.SYSTEM }
    if (leadingSystemMessages.isEmpty()) return emptyList()
    if (template.useSystemPrompt) return leadingSystemMessages

    val assistantSystemPrompt = assistant.systemPrompt
    val firstSystemMessage = leadingSystemMessages.first().toText()
    val strippedText = if (
        assistantSystemPrompt.isNotBlank() &&
        firstSystemMessage.startsWith(assistantSystemPrompt)
    ) {
        firstSystemMessage
            .removePrefix(assistantSystemPrompt)
            .trimStart('\r', '\n')
    } else {
        firstSystemMessage
    }.trim()

    return strippedText
        .takeIf { it.isNotBlank() }
        ?.let { listOf(UIMessage.system(it)) }
        ?: emptyList()
}

private fun buildContinueRuntimeBehavior(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): StRuntimePromptBehavior {
    if (chatHistoryMessages.lastOrNull()?.role != MessageRole.ASSISTANT) {
        return StRuntimePromptBehavior(chatHistoryMessages = chatHistoryMessages)
    }

    val continueIndex = chatHistoryMessages.lastIndex
    val continuedMessage = chatHistoryMessages[continueIndex]
    val remainingHistory = chatHistoryMessages.toMutableList().apply { removeAt(continueIndex) }
    val continuedText = appendContinuationPostfix(
        text = continuedMessage.toText(),
        postfix = template.continuePostfix,
    )
    val continuedPrompt = buildString {
        if (template.continuePrefill) {
            val assistantPrefill = template.assistantPrefill
            if (assistantPrefill.isNotBlank()) {
                append(assistantPrefill)
                if (continuedText.isNotBlank()) {
                    append("\n\n")
                }
            }
        }
        append(continuedText)
    }.takeIf { it.isNotBlank() }?.let { content ->
        createMessage(
            role = continuedMessage.role,
            content = content,
        )
    }

    val controlMessages = buildList {
        if (continuedPrompt != null) {
            add(continuedPrompt)
        }
        if (!template.continuePrefill) {
            val nudgePrompt = replaceRuntimePlaceholder(
                text = stripInlineRegexBlocks(template.continueNudgePrompt),
                key = "lastChatMessage",
                value = continuedText,
            ).trim()
            if (nudgePrompt.isNotBlank()) {
                add(UIMessage.system(nudgePrompt))
            }
        }
    }

    return StRuntimePromptBehavior(
        chatHistoryMessages = remainingHistory,
        controlMessages = controlMessages,
    )
}

private fun buildImpersonationRuntimeBehavior(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): StRuntimePromptBehavior {
    val controlMessages = buildList {
        val impersonationPrompt = stripInlineRegexBlocks(template.impersonationPrompt).trim()
        if (impersonationPrompt.isNotBlank()) {
            add(UIMessage.system(impersonationPrompt))
        }

        val assistantImpersonation = template.assistantImpersonation
        if (assistantImpersonation.isNotBlank()) {
            add(UIMessage.assistant(assistantImpersonation))
        }
    }

    return StRuntimePromptBehavior(
        chatHistoryMessages = chatHistoryMessages,
        controlMessages = controlMessages,
    )
}

private fun prefixMessageContent(
    message: UIMessage,
    speaker: String,
): UIMessage {
    val prefix = "$speaker: "
    var prefixed = false
    val updatedParts = message.parts.map { part ->
        if (!prefixed && part is UIMessagePart.Text) {
            prefixed = true
            part.copy(text = prefix + part.text)
        } else {
            part
        }
    }

    return if (prefixed) {
        message.copy(parts = updatedParts)
    } else if (message.parts.isNotEmpty()) {
        message.copy(parts = listOf(UIMessagePart.Text(prefix.trimEnd())) + message.parts)
    } else {
        message
    }
}

private fun appendContinuationPostfix(
    text: String,
    postfix: String,
): String {
    if (text.isBlank()) return text
    return text + postfix
}

private fun replaceRuntimePlaceholder(
    text: String,
    key: String,
    value: String,
): String {
    return text
        .replace("{{$key}}", value, ignoreCase = true)
        .replace("{${key}}", value, ignoreCase = true)
}
