package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered

internal fun collectTriggeredLorebookEntries(
    historyMessages: List<UIMessage>,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    triggerContext: LorebookTriggerContext,
): List<PromptInjection.RegexInjection> {
    val enabledLorebooks = lorebooks.filter {
        it.enabled && assistant.lorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isEmpty()) return emptyList()

    val nonSystemMessages = historyMessages.filter { it.role != MessageRole.SYSTEM }
    return enabledLorebooks
        .flatMap { lorebook ->
            lorebook.collectTriggeredEntries(
                historyMessages = nonSystemMessages,
                triggerContext = triggerContext,
            )
        }
        .sortedByDescending { it.priority }
}

private fun Lorebook.collectTriggeredEntries(
    historyMessages: List<UIMessage>,
    triggerContext: LorebookTriggerContext,
): List<PromptInjection.RegexInjection> {
    if (entries.isEmpty()) return emptyList()

    val baseContexts = entries.associate { entry ->
        entry.id to extractContextForMatching(historyMessages, entry.scanDepth)
    }
    val activatedEntries = mutableListOf<PromptInjection.RegexInjection>()
    val activatedIds = mutableSetOf<kotlin.uuid.Uuid>()
    var recursiveContents = emptyList<String>()
    var recursionLevel = 0

    while (activatedIds.size < entries.size) {
        val recursiveContext = recursiveContents.joinToString("\n")
        val newlyActivated = entries.filter { entry ->
            if (entry.id in activatedIds || !entry.matchesRecursionPhase(recursionLevel)) {
                return@filter false
            }

            val recentMessagesText = buildTriggerRecentMessagesText(
                baseContext = baseContexts[entry.id].orEmpty(),
                recursiveContext = recursiveContext,
            )
            entry.isTriggered(
                context = recentMessagesText,
                triggerContext = triggerContext.copy(recentMessagesText = recentMessagesText),
            )
        }

        if (newlyActivated.isEmpty()) break

        activatedEntries += newlyActivated
        activatedIds += newlyActivated.map { it.id }

        if (!recursiveScanning) break

        val newRecursiveContents = newlyActivated
            .filterNot { it.preventsRecursion() }
            .mapNotNull { entry ->
                entry.content.trim().takeIf { it.isNotBlank() }
            }
        if (newRecursiveContents.isEmpty()) break

        recursiveContents = recursiveContents + newRecursiveContents
        recursionLevel++
    }

    return activatedEntries
}

private fun buildTriggerRecentMessagesText(
    baseContext: String,
    recursiveContext: String,
): String {
    return buildList {
        baseContext.trim().takeIf { it.isNotBlank() }?.let(::add)
        recursiveContext.trim().takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("\n")
}

private fun PromptInjection.RegexInjection.matchesRecursionPhase(recursionLevel: Int): Boolean {
    val delayLevel = recursionDelayLevel()
    if (recursionLevel == 0) {
        return delayLevel == null
    }
    if (excludesRecursion()) {
        return false
    }
    return delayLevel == null || recursionLevel >= delayLevel
}

private fun PromptInjection.RegexInjection.preventsRecursion(): Boolean {
    return stBoolean("prevent_recursion")
}

private fun PromptInjection.RegexInjection.excludesRecursion(): Boolean {
    return stBoolean("exclude_recursion")
}

private fun PromptInjection.RegexInjection.recursionDelayLevel(): Int? {
    val rawValue = stMetadata["delay_until_recursion"]?.trim() ?: return null
    if (rawValue.isEmpty() || rawValue.equals("false", ignoreCase = true) || rawValue == "0") {
        return null
    }
    if (rawValue.equals("true", ignoreCase = true)) {
        return 1
    }
    return rawValue.toIntOrNull()?.takeIf { it > 0 }
}

private fun PromptInjection.RegexInjection.stBoolean(key: String): Boolean {
    return stMetadata[key]?.trim()?.let { value ->
        value.equals("true", ignoreCase = true) || value == "1"
    } ?: false
}
