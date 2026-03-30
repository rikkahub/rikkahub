package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.activeStPresetTemplate
import me.rerere.rikkahub.data.model.effectiveUserPersona
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.matchesGenerationType
import me.rerere.rikkahub.data.model.resolvePromptOrder

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
            stMacroState = ctx.stMacroState,
        )
    }
}

internal fun transformSillyTavernPrompt(
    messages: List<UIMessage>,
    assistant: Assistant,
    settings: Settings? = null,
    lorebooks: List<Lorebook>,
    template: me.rerere.rikkahub.data.model.SillyTavernPromptTemplate,
    generationType: String = "normal",
    personaDescription: String = assistant.userPersona,
    runtimeState: LorebookRuntimeState? = null,
    stMacroState: StMacroState? = null,
): List<UIMessage> {
    val normalizedGenerationType = generationType.trim().lowercase().ifBlank { "normal" }
    val normalizedPersonaDescription = personaDescription.trim()
    val rawLeadingSystemCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
    val leadingSystemMessages = collectLeadingSystemMessages(
        messages = messages,
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
    updateActiveLorebookOutlets(
        entries = triggeredLorebookEntries,
        stMacroState = stMacroState,
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
            prompt.injectionPosition == me.rerere.rikkahub.data.model.StPromptInjectionPosition.ABSOLUTE ||
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

    return collapseLeadingSystemMessages(
        buildList {
            if (leadingSystemSections.isNotEmpty()) {
                add(UIMessage.system(leadingSystemSections.joinToString("\n")))
            }
            addAll(result)
            addAll(runtimeBehavior.controlMessages)
        }
    )
}
