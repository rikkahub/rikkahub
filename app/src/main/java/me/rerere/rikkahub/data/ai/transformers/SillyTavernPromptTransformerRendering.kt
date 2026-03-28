package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.matchesGenerationType
import me.rerere.rikkahub.utils.applyPlaceholders

internal data class StAbsoluteMessage(
    val depth: Int,
    val order: Int,
    val role: MessageRole,
    val content: String,
)

internal fun buildAbsoluteMessages(
    orderedPrompts: List<Pair<SillyTavernPromptOrderItem, SillyTavernPromptItem>>,
    template: SillyTavernPromptTemplate,
    characterData: SillyTavernCharacterData?,
    worldInfoBefore: String,
    worldInfoAfter: String,
    generationType: String,
): List<StAbsoluteMessage> {
    val promptMessages = orderedPrompts
        .asSequence()
        .filter { (orderItem, prompt) ->
            orderItem.enabled &&
                prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE &&
                prompt.matchesGenerationType(generationType)
        }
        .mapNotNull { (_, prompt) ->
            resolvePromptText(
                prompt = prompt,
                template = template,
                characterData = characterData,
                worldInfoBefore = worldInfoBefore,
                worldInfoAfter = worldInfoAfter,
            )?.let { content ->
                StAbsoluteMessage(
                    depth = prompt.injectionDepth.coerceAtLeast(0),
                    order = prompt.injectionOrder,
                    role = prompt.role,
                    content = content,
                )
            }
        }
        .toList()
    val depthPrompt = characterData?.depthPrompt
    val depthPromptMessage = depthPrompt
        ?.prompt
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { content ->
            StAbsoluteMessage(
                depth = depthPrompt.depth.coerceAtLeast(0),
                order = 100,
                role = depthPrompt.role,
                content = content,
            )
        }
    return if (depthPromptMessage != null) {
        promptMessages + depthPromptMessage
    } else {
        promptMessages
    }
}

internal fun buildAuthorNoteAbsoluteMessage(
    entries: List<PromptInjection.RegexInjection>,
): StAbsoluteMessage? {
    val topEntries = entries.filter { it.position == InjectionPosition.AUTHOR_NOTE_TOP }
    val bottomEntries = entries.filter { it.position == InjectionPosition.AUTHOR_NOTE_BOTTOM }
    val content = buildAuthorNoteContent(topEntries, bottomEntries)
    if (content.isBlank()) return null

    return StAbsoluteMessage(
        depth = DEFAULT_ST_AUTHOR_NOTE_DEPTH,
        order = Int.MAX_VALUE,
        role = MessageRole.SYSTEM,
        content = content,
    )
}

internal fun resolveRelativePromptMessages(
    prompt: SillyTavernPromptItem,
    assistant: Assistant,
    template: SillyTavernPromptTemplate,
    characterData: SillyTavernCharacterData?,
    worldInfoBefore: String,
    worldInfoAfter: String,
    chatHistoryMessages: List<UIMessage>,
    personaDescription: String,
    exampleMessagesBefore: List<String>,
    exampleMessagesAfter: List<String>,
): List<UIMessage> {
    return when (prompt.identifier) {
        "chatHistory" -> buildChatHistoryMessages(chatHistoryMessages, template)
        "dialogueExamples" -> buildDialogueExampleMessages(
            raw = characterData?.exampleMessagesRaw.orEmpty(),
            introPrompt = template.newExampleChatPrompt,
            beforeEntries = exampleMessagesBefore,
            afterEntries = exampleMessagesAfter,
        )
        "personaDescription" -> personaDescription
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { text -> listOf(createMessage(prompt.role, text)) }
            ?: emptyList()
        else -> resolvePromptText(
            prompt = prompt,
            template = template,
            characterData = characterData,
            worldInfoBefore = worldInfoBefore,
            worldInfoAfter = worldInfoAfter,
        )?.let { text ->
            listOf(createMessage(prompt.role, text))
        } ?: emptyList()
    }
}

internal fun applySendIfEmpty(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    val sendIfEmpty = template.sendIfEmpty.trim()
    val lastChatMessage = chatHistoryMessages.lastOrNull()
    if (sendIfEmpty.isBlank() || lastChatMessage?.role != MessageRole.ASSISTANT) {
        return chatHistoryMessages
    }

    return chatHistoryMessages + UIMessage.user(sendIfEmpty)
}

internal fun appendResolvedMessages(
    promptIdentifier: String,
    resolvedMessages: List<UIMessage>,
    leadingSystemSections: MutableList<String>,
    result: MutableList<UIMessage>,
) {
    val hoistSystemMessages = promptIdentifier != "chatHistory"
    resolvedMessages.forEach { message ->
        if (hoistSystemMessages && message.role == MessageRole.SYSTEM) {
            message.toText()
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(leadingSystemSections::add)
        } else {
            result += message
        }
    }
}

internal fun collapseLeadingSystemMessages(messages: List<UIMessage>): List<UIMessage> {
    val leadingSystemCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
    if (leadingSystemCount <= 1) return messages

    val mergedSystemText = messages
        .take(leadingSystemCount)
        .joinToString("\n") { it.toText().trim() }
        .trim()

    return listOf(UIMessage.system(mergedSystemText)) + messages.drop(leadingSystemCount)
}

internal fun applyAbsoluteMessages(
    messages: List<UIMessage>,
    prompts: List<StAbsoluteMessage>,
): List<UIMessage> {
    if (prompts.isEmpty()) return messages

    val result = messages.toMutableList()
    prompts
        .groupBy { it.depth }
        .keys
        .sortedDescending()
        .forEach { depth ->
            val depthPrompts = prompts.filter { it.depth == depth }
            var insertIndex = (result.size - depth).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)

            depthPrompts
                .groupBy { it.order }
                .toSortedMap(compareByDescending { it })
                .values
                .forEach { orderPrompts ->
                    listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT).forEach { role ->
                        val content = orderPrompts
                            .filter { it.role == role }
                            .joinToString("\n") { it.content.trim() }
                            .trim()
                        if (content.isBlank()) return@forEach
                        result.add(insertIndex, createMessage(role, content))
                        insertIndex++
                    }
                }
        }

    return result
}

internal fun stripInlineRegexBlocks(content: String): String {
    if (content.isBlank()) return content
    return content.replace(Regex("""<regex(?:\s+order=(-?\d+))?>[\s\S]*?</regex>"""), "").trim()
}

internal fun createMessage(role: MessageRole, content: String): UIMessage {
    return when (role) {
        MessageRole.SYSTEM -> UIMessage.system(content)
        MessageRole.ASSISTANT -> UIMessage.assistant(content)
        else -> UIMessage.user(content)
    }
}

private fun resolvePromptText(
    prompt: SillyTavernPromptItem,
    template: SillyTavernPromptTemplate,
    characterData: SillyTavernCharacterData?,
    worldInfoBefore: String,
    worldInfoAfter: String,
): String? {
    val text = when (prompt.identifier) {
        "main" -> {
            val override = characterData?.systemPromptOverride.orEmpty()
            if (override.isNotBlank() && !prompt.forbidOverrides) {
                override
            } else {
                prompt.content.ifBlank { template.mainPrompt }
            }
        }

        "jailbreak" -> {
            val override = characterData?.postHistoryInstructions.orEmpty()
            if (override.isNotBlank() && !prompt.forbidOverrides) {
                override
            } else {
                prompt.content
            }
        }

        "worldInfoBefore" -> formatWorldInfo(worldInfoBefore, template.wiFormat)
        "worldInfoAfter" -> formatWorldInfo(worldInfoAfter, template.wiFormat)
        "charDescription" -> characterData?.description.orEmpty()
        "charPersonality" -> formatCharacterField(
            value = characterData?.personality.orEmpty(),
            format = template.personalityFormat,
            key = "personality",
        )
        "scenario" -> formatCharacterField(
            value = characterData?.scenario.orEmpty(),
            format = template.scenarioFormat,
            key = "scenario",
        )

        else -> prompt.content
    }.trim()

    return text.takeIf { it.isNotBlank() }
}

private fun formatWorldInfo(value: String, format: String): String {
    if (value.isBlank()) return ""
    if (format.isBlank()) return value
    return format.applyPlaceholders("0" to value)
}

private fun formatCharacterField(
    value: String,
    format: String,
    key: String,
): String {
    if (value.isBlank()) return ""
    if (format.isBlank()) return value
    return format
        .replace("{{${key}}}", value, ignoreCase = true)
        .replace("{${key}}", value, ignoreCase = true)
}

private fun buildChatHistoryMessages(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    val newChatPrompt = template.newChatPrompt.trim()
    return buildList {
        if (newChatPrompt.isNotBlank()) {
            add(UIMessage.system(newChatPrompt))
        }
        addAll(chatHistoryMessages)
    }
}

private fun buildDialogueExampleMessages(
    raw: String,
    introPrompt: String,
    beforeEntries: List<String> = emptyList(),
    afterEntries: List<String> = emptyList(),
): List<UIMessage> {
    return beforeEntries.flatMap { parseDialogueExampleMessages(it, introPrompt) } +
        parseDialogueExampleMessages(raw, introPrompt) +
        afterEntries.flatMap { parseDialogueExampleMessages(it, introPrompt) }
}

private fun parseDialogueExampleMessages(
    raw: String,
    introPrompt: String,
): List<UIMessage> {
    if (raw.isBlank()) return emptyList()
    val normalizedIntroPrompt = introPrompt.trim().ifBlank { "[Example Chat]" }

    return raw
        .replace("\r", "")
        .split(Regex("(?i)<START>"))
        .map { block -> block.trim() }
        .filter { it.isNotBlank() }
        .flatMap { block ->
            val turns = splitDialogueTurns(block)
            buildList {
                add(UIMessage.system(normalizedIntroPrompt))
                if (turns.isEmpty()) {
                    add(UIMessage.system(block))
                } else {
                    turns.forEach { turn ->
                        add(UIMessage.system(turn))
                    }
                }
            }
        }
}

private fun splitDialogueTurns(block: String): List<String> {
    val speakerRegex = Regex("""^[^:\n]{1,80}:""")
    val result = mutableListOf<String>()
    val current = mutableListOf<String>()

    block
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            if (speakerRegex.containsMatchIn(line) && current.isNotEmpty()) {
                result += current.joinToString("\n").trim()
                current.clear()
            }
            current += line
        }

    if (current.isNotEmpty()) {
        result += current.joinToString("\n").trim()
    }

    return result
}
