package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.activeStPresetTemplate
import me.rerere.rikkahub.data.model.effectiveUserPersona
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.matchesGenerationType
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.utils.applyPlaceholders

object SillyTavernPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.settings.activeStPresetTemplate()
            ?.takeIf { ctx.settings.stPresetEnabled }
            ?: return messages
        return transformSillyTavernPrompt(
            messages = messages,
            assistant = ctx.assistant,
            settings = ctx.settings,
            lorebooks = ctx.settings.lorebooks,
            template = template,
            generationType = ctx.stGenerationType,
            personaDescription = ctx.settings.effectiveUserPersona(ctx.assistant),
            runtimeState = ctx.lorebookRuntimeState,
        )
    }
}

internal fun transformSillyTavernPrompt(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings? = null,
    lorebooks: List<Lorebook>,
    template: SillyTavernPromptTemplate,
    generationType: String = "normal",
    personaDescription: String = assistant.userPersona,
    runtimeState: LorebookRuntimeState? = null,
): List<UIMessage> {
    val normalizedGenerationType = generationType.trim().lowercase().ifBlank { "normal" }
    val normalizedPersonaDescription = personaDescription.trim()
    val rawLeadingSystemCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
    val leadingSystemMessages = collectLeadingSystemMessages(
        messages = messages,
        assistant = assistant,
        template = template,
    )
    val chatHistoryMessages = applyNamesBehaviorToChatHistory(
        messages.drop(rawLeadingSystemCount),
        template = template,
    )
    val characterData = assistant.stCharacterData
    val runtimeBehavior = applyGenerationTypeRuntimeBehavior(
        chatHistoryMessages = chatHistoryMessages,
        template = template,
        generationType = normalizedGenerationType,
    )

    val triggeredLorebookEntries = collectTriggeredLorebookEntries(
        historyMessages = runtimeBehavior.chatHistoryMessages,
        assistant = assistant,
        lorebooks = lorebooks,
        triggerContext = LorebookTriggerContext(
            characterDescription = characterData?.description.orEmpty(),
            characterPersonality = characterData?.personality.orEmpty(),
            personaDescription = normalizedPersonaDescription,
            scenario = characterData?.scenario.orEmpty(),
            creatorNotes = characterData?.creatorNotes.orEmpty(),
            characterDepthPrompt = characterData?.depthPrompt?.prompt.orEmpty(),
            generationType = generationType,
        ),
        settings = settings,
        runtimeState = runtimeState,
    )

    val worldInfoBefore = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.BEFORE_SYSTEM_PROMPT }
        .joinToString("\n") { it.content.trim() }
        .trim()
    val worldInfoAfter = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.AFTER_SYSTEM_PROMPT }
        .joinToString("\n") { it.content.trim() }
        .trim()
    val authorNoteMessage = buildAuthorNoteAbsoluteMessage(triggeredLorebookEntries)
    val exampleMessagesBefore = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.EXAMPLE_MESSAGES_TOP }
        .mapNotNull { it.content.trim().takeIf(String::isNotBlank) }
    val exampleMessagesAfter = triggeredLorebookEntries
        .filter { it.position == InjectionPosition.EXAMPLE_MESSAGES_BOTTOM }
        .mapNotNull { it.content.trim().takeIf(String::isNotBlank) }
    val orderedPrompts = template.resolvePromptOrder()
        .mapNotNull { orderItem ->
            template.findPrompt(orderItem.identifier)?.let { prompt ->
                orderItem to prompt
            }
        }

    val absoluteMessages = buildAbsoluteMessages(
        orderedPrompts = orderedPrompts,
        template = template,
        characterData = characterData,
        worldInfoBefore = worldInfoBefore,
        worldInfoAfter = worldInfoAfter,
        generationType = generationType,
    ) + listOfNotNull(authorNoteMessage) + triggeredLorebookEntries
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

    var processedHistoryMessages = applyAbsoluteMessages(runtimeBehavior.chatHistoryMessages, absoluteMessages)

    val floatingLorebookEntries = triggeredLorebookEntries.filter {
        it.position == InjectionPosition.TOP_OF_CHAT ||
            it.position == InjectionPosition.BOTTOM_OF_CHAT
    }
    if (floatingLorebookEntries.isNotEmpty()) {
        processedHistoryMessages = applyInjections(
            messages = processedHistoryMessages,
            byPosition = floatingLorebookEntries
                .sortedByDescending { it.priority }
                .groupBy { it.position }
        )
    }
    processedHistoryMessages = applySendIfEmpty(processedHistoryMessages, template)

    val leadingSystemSections = leadingSystemMessages
        .map { it.toText().trim() }
        .filter { it.isNotBlank() }
        .toMutableList()
    val result = mutableListOf<UIMessage>()

    orderedPrompts.forEach { (orderItem, prompt) ->
        if (
            !orderItem.enabled ||
            prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE ||
            !prompt.matchesGenerationType(generationType)
        ) {
            return@forEach
        }

        val resolvedMessages = resolveRelativePromptMessages(
            prompt = prompt,
            assistant = assistant,
            template = template,
            characterData = characterData,
            worldInfoBefore = worldInfoBefore,
            worldInfoAfter = worldInfoAfter,
            chatHistoryMessages = processedHistoryMessages,
            personaDescription = normalizedPersonaDescription,
            exampleMessagesBefore = exampleMessagesBefore,
            exampleMessagesAfter = exampleMessagesAfter,
        )
        appendResolvedMessages(
            promptIdentifier = prompt.identifier,
            resolvedMessages = resolvedMessages,
            leadingSystemSections = leadingSystemSections,
            result = result,
        )
    }
    result += runtimeBehavior.controlMessages

    return collapseLeadingSystemMessages(
        buildList {
            if (leadingSystemSections.isNotEmpty()) {
                add(UIMessage.system(leadingSystemSections.joinToString("\n")))
            }
            addAll(result)
        }
    )
}

private data class StRuntimePromptBehavior(
    val chatHistoryMessages: List<UIMessage>,
    val controlMessages: List<UIMessage> = emptyList(),
)

private fun applyGenerationTypeRuntimeBehavior(
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

private fun applyNamesBehaviorToChatHistory(
    chatHistoryMessages: List<UIMessage>,
    template: SillyTavernPromptTemplate,
): List<UIMessage> {
    if (template.namesBehavior != 2) {
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

private fun collectLeadingSystemMessages(
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

private fun buildAbsoluteMessages(
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

private fun buildAuthorNoteAbsoluteMessage(
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

private fun resolveRelativePromptMessages(
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

private fun applySendIfEmpty(
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

private fun createMessage(role: MessageRole, content: String): UIMessage {
    return when (role) {
        MessageRole.SYSTEM -> UIMessage.system(content)
        MessageRole.ASSISTANT -> UIMessage.assistant(content)
        else -> UIMessage.user(content)
    }
}

private fun appendResolvedMessages(
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

private fun collapseLeadingSystemMessages(messages: List<UIMessage>): List<UIMessage> {
    val leadingSystemCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
    if (leadingSystemCount <= 1) return messages

    val mergedSystemText = messages
        .take(leadingSystemCount)
        .joinToString("\n") { it.toText().trim() }
        .trim()

    return listOf(UIMessage.system(mergedSystemText)) + messages.drop(leadingSystemCount)
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
