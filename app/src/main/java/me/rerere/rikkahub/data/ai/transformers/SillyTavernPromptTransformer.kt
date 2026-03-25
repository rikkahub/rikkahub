package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.utils.applyPlaceholders

object SillyTavernPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.assistant.stPromptTemplate ?: return messages
        return transformSillyTavernPrompt(
            messages = messages,
            assistant = ctx.assistant,
            lorebooks = ctx.settings.lorebooks,
            template = template,
        )
    }
}

internal fun transformSillyTavernPrompt(
    messages: List<UIMessage>,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    val leadingSystemMessages = messages.takeWhile { it.role == MessageRole.SYSTEM }
    val chatHistoryMessages = messages.drop(leadingSystemMessages.size)
    val characterData = assistant.stCharacterData

    val triggeredLorebookEntries = collectTriggeredLorebookEntries(
        historyMessages = chatHistoryMessages,
        assistant = assistant,
        lorebooks = lorebooks,
        characterData = characterData,
    )

    val worldInfoBefore = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.BEFORE_SYSTEM_PROMPT }
        .joinToString("\n") { it.content.trim() }
        .trim()
    val worldInfoAfter = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.AFTER_SYSTEM_PROMPT }
        .joinToString("\n") { it.content.trim() }
        .trim()

    val absoluteMessages = buildAbsoluteMessages(
        template = template,
        characterData = characterData,
        worldInfoBefore = worldInfoBefore,
        worldInfoAfter = worldInfoAfter,
    ) + triggeredLorebookEntries
        .filter { it.position == InjectionPosition.AT_DEPTH }
        .mapNotNull { entry ->
            entry.content.trim().takeIf { it.isNotBlank() }?.let { content ->
                StAbsoluteMessage(
                    depth = entry.injectDepth.coerceAtLeast(0),
                    order = entry.priority,
                    role = entry.role,
                    content = content,
                )
            }
        }

    var processedHistoryMessages = applyAbsoluteMessages(chatHistoryMessages, absoluteMessages)

    val floatingLorebookEntries = triggeredLorebookEntries.filter {
        it.position == InjectionPosition.TOP_OF_CHAT || it.position == InjectionPosition.BOTTOM_OF_CHAT
    }
    if (floatingLorebookEntries.isNotEmpty()) {
        processedHistoryMessages = applyInjections(
            messages = processedHistoryMessages,
            byPosition = floatingLorebookEntries
                .sortedByDescending { it.priority }
                .groupBy { it.position }
        )
    }

    val orderedPromptIds = template.orderedPromptIds.ifEmpty {
        template.prompts.filter { it.enabled }.map { it.identifier }
    }
    val result = leadingSystemMessages.toMutableList()
    val appendedPromptIds = mutableSetOf<String>()
    var appendedChatHistory = false

    orderedPromptIds.forEach { identifier ->
        val prompt = template.findPrompt(identifier) ?: return@forEach
        if (!prompt.enabled || prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
            return@forEach
        }

        val resolvedMessages = resolveRelativePromptMessages(
            prompt = prompt,
            template = template,
            characterData = characterData,
            worldInfoBefore = worldInfoBefore,
            worldInfoAfter = worldInfoAfter,
            chatHistoryMessages = processedHistoryMessages,
        )
        if (identifier == "chatHistory" && resolvedMessages.isNotEmpty()) {
            appendedChatHistory = true
        }
        result += resolvedMessages
        appendedPromptIds += identifier
    }

    template.prompts
        .filter {
            it.enabled &&
                it.injectionPosition != StPromptInjectionPosition.ABSOLUTE &&
                it.identifier !in appendedPromptIds
        }
        .forEach { prompt ->
            val resolvedMessages = resolveRelativePromptMessages(
                prompt = prompt,
                template = template,
                characterData = characterData,
                worldInfoBefore = worldInfoBefore,
                worldInfoAfter = worldInfoAfter,
                chatHistoryMessages = processedHistoryMessages,
            )
            if (prompt.identifier == "chatHistory" && resolvedMessages.isNotEmpty()) {
                appendedChatHistory = true
            }
            result += resolvedMessages
        }

    if (!appendedChatHistory) {
        result += processedHistoryMessages
    }

    return result
}

private fun collectTriggeredLorebookEntries(
    historyMessages: List<UIMessage>,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    characterData: SillyTavernCharacterData?,
): List<PromptInjection.RegexInjection> {
    val enabledLorebooks = lorebooks.filter {
        it.enabled && assistant.lorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isEmpty()) return emptyList()

    val nonSystemMessages = historyMessages.filter { it.role != MessageRole.SYSTEM }
    val triggerContext = LorebookTriggerContext(
        recentMessagesText = nonSystemMessages.joinToString("\n") { it.toText() },
        characterDescription = characterData?.description.orEmpty(),
        characterPersonality = characterData?.personality.orEmpty(),
        scenario = characterData?.scenario.orEmpty(),
        creatorNotes = characterData?.creatorNotes.orEmpty(),
        characterDepthPrompt = characterData?.depthPrompt?.prompt.orEmpty(),
    )

    return enabledLorebooks
        .flatMap { lorebook ->
            lorebook.entries.filter { entry ->
                val context = extractContextForMatching(nonSystemMessages, entry.scanDepth)
                entry.isTriggered(context, triggerContext)
            }
        }
        .sortedByDescending { it.priority }
}

private fun buildAbsoluteMessages(
    template: SillyTavernPromptTemplate,
    characterData: SillyTavernCharacterData?,
    worldInfoBefore: String,
    worldInfoAfter: String,
): List<StAbsoluteMessage> {
    val promptMessages = template.prompts
        .filter { it.enabled && it.injectionPosition == StPromptInjectionPosition.ABSOLUTE }
        .mapNotNull { prompt ->
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

private fun resolveRelativePromptMessages(
    prompt: SillyTavernPromptItem,
    template: SillyTavernPromptTemplate,
    characterData: SillyTavernCharacterData?,
    worldInfoBefore: String,
    worldInfoAfter: String,
    chatHistoryMessages: List<UIMessage>,
): List<UIMessage> {
    return when (prompt.identifier) {
        "chatHistory" -> buildChatHistoryMessages(chatHistoryMessages, template)
        "dialogueExamples" -> parseDialogueExampleMessages(
            raw = characterData?.exampleMessagesRaw.orEmpty(),
            introPrompt = template.newExampleChatPrompt,
        )
        "personaDescription" -> emptyList()
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

        else -> stripInlineRegexBlocks(prompt.content)
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

private fun createMessage(role: MessageRole, content: String): UIMessage {
    return when (role) {
        MessageRole.SYSTEM -> UIMessage.system(content)
        MessageRole.ASSISTANT -> UIMessage.assistant(content)
        else -> UIMessage.user(content)
    }
}

private fun applyAbsoluteMessages(
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

private data class StAbsoluteMessage(
    val depth: Int,
    val order: Int,
    val role: MessageRole,
    val content: String,
)
