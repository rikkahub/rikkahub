package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.stExtension
import kotlin.uuid.Uuid

internal const val DEFAULT_LOREBOOK_SCAN_DEPTH = 4

internal fun PromptInjection.RegexInjection.effectiveScanDepth(
    globalSettings: LorebookGlobalSettings,
    depthSkew: Int,
): Int {
    val baseDepth = if (
        scanDepth > 0 &&
        (scanDepth != DEFAULT_LOREBOOK_SCAN_DEPTH || globalSettings.scanDepth == DEFAULT_LOREBOOK_SCAN_DEPTH)
    ) {
        scanDepth
    } else {
        globalSettings.scanDepth
    }
    return (baseDepth + depthSkew).coerceAtLeast(0)
}

internal fun buildTriggerRecentMessagesText(
    baseContext: String,
    recursiveContext: String,
): String {
    return buildList {
        baseContext.trim().takeIf { it.isNotBlank() }?.let(::add)
        recursiveContext.trim().takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("\n")
}

internal fun PromptInjection.RegexInjection.matchesRecursionPhase(recursionLevel: Int): Boolean {
    return matchesRecursionPhase(recursionLevel = recursionLevel, isSticky = false)
}

internal fun PromptInjection.RegexInjection.matchesRecursionPhase(
    recursionLevel: Int,
    isSticky: Boolean,
): Boolean {
    if (isSticky) return true
    val delayLevel = recursionDelayLevel()
    if (recursionLevel == 0) {
        return delayLevel == null
    }
    if (excludesRecursion()) {
        return false
    }
    return delayLevel == null || recursionLevel >= delayLevel
}

internal fun PromptInjection.RegexInjection.preventsRecursion(): Boolean {
    return stExtension().preventRecursion
}

internal fun PromptInjection.RegexInjection.excludesRecursion(): Boolean {
    return stExtension().excludeRecursion
}

internal fun PromptInjection.RegexInjection.recursionDelayLevel(): Int? {
    return stExtension().recursionDelayLevel()
}

internal fun PromptInjection.RegexInjection.delayMessages(): Int? {
    return stExtension().delay?.takeIf { it > 0 }
}

internal fun PromptInjection.RegexInjection.ignoreBudget(): Boolean {
    return stExtension().ignoreBudget
}

internal fun PromptInjection.RegexInjection.groupNames(): List<String> {
    return stExtension().groupNames()
}

internal fun PromptInjection.RegexInjection.groupOverride(): Boolean {
    return stExtension().groupOverride
}

internal fun PromptInjection.RegexInjection.useGroupScoring(globalSettings: LorebookGlobalSettings): Boolean {
    return globalSettings.useGroupScoring || stExtension().useGroupScoring
}

internal fun PromptInjection.RegexInjection.groupWeight(): Int {
    return stExtension().groupWeight?.takeIf { it > 0 } ?: 100
}

internal fun hasPendingDelayedRecursionLevel(
    entries: List<PromptInjection.RegexInjection>,
    activatedIds: Set<Uuid>,
    recursionLevel: Int,
): Boolean {
    return entries.any { entry ->
        entry.id !in activatedIds && (entry.recursionDelayLevel() ?: 0) > recursionLevel
    }
}

internal fun PromptInjection.RegexInjection.toAffectScope(): AssistantAffectScope {
    return when (role) {
        MessageRole.SYSTEM -> AssistantAffectScope.SYSTEM
        MessageRole.USER -> AssistantAffectScope.USER
        MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
        else -> AssistantAffectScope.SYSTEM
    }
}
