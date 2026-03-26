package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexApplyPhase
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.matchScore
import me.rerere.rikkahub.data.model.matchesTriggerKeywords
import me.rerere.rikkahub.data.model.passesProbabilityCheck
import me.rerere.rikkahub.data.model.replaceRegexes
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.uuid.Uuid

private const val DEFAULT_LOREBOOK_SCAN_DEPTH = 4

internal fun collectTriggeredLorebookEntries(
    historyMessages: List<UIMessage>,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    triggerContext: LorebookTriggerContext,
    settings: Settings? = null,
    runtimeState: LorebookRuntimeState? = null,
): List<PromptInjection.RegexInjection> {
    val enabledLorebooks = lorebooks.filter {
        it.enabled && assistant.lorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isEmpty()) return emptyList()

    val nonSystemMessages = historyMessages.filter { it.role != MessageRole.SYSTEM }
    val globalSettings = settings?.lorebookGlobalSettings ?: LorebookGlobalSettings()
    val userName = settings?.effectiveUserName().orEmpty().ifBlank { "User" }
    val assistantName = assistant.stCharacterData?.name?.ifBlank { assistant.name } ?: assistant.name.ifBlank { "Assistant" }
    return enabledLorebooks
        .flatMap { lorebook ->
            lorebook.collectTriggeredEntries(
                historyMessages = nonSystemMessages,
                triggerContext = triggerContext,
                assistant = assistant,
                settings = settings,
                runtimeState = runtimeState,
                globalSettings = globalSettings,
                userName = userName,
                assistantName = assistantName,
            )
        }
        .sortedByDescending { it.priority }
}

private data class LorebookCandidate(
    val entry: PromptInjection.RegexInjection,
    val isSticky: Boolean,
    val score: Int,
)

private fun Lorebook.collectTriggeredEntries(
    historyMessages: List<UIMessage>,
    triggerContext: LorebookTriggerContext,
    assistant: Assistant,
    settings: Settings?,
    runtimeState: LorebookRuntimeState?,
    globalSettings: LorebookGlobalSettings,
    userName: String,
    assistantName: String,
): List<PromptInjection.RegexInjection> {
    if (entries.isEmpty()) return emptyList()

    val sortedEntries = entries.sortedByDescending { it.priority }
    val activatedEntries = mutableListOf<PromptInjection.RegexInjection>()
    val activatedIds = mutableSetOf<Uuid>()
    val historyMessageCount = historyMessages.size
    val timedEffects = runtimeState?.snapshot(
        entries = sortedEntries,
        messageCount = historyMessageCount,
    ) ?: LorebookTimedEffectsSnapshot(
        delayedEntryIds = sortedEntries.asSequence()
            .filter { it.delayMessages()?.let { delay -> historyMessageCount < delay } ?: false }
            .map { it.id }
            .toSet()
    )
    val recursiveEnabled = recursiveScanning || globalSettings.recursiveScanning
    val maxRecursionSteps = globalSettings.maxRecursionSteps.takeIf { it > 0 }
    val minActivations = globalSettings.minActivations.takeIf { it > 0 && maxRecursionSteps == null } ?: 0
    val budget = resolveLorebookBudget(assistant, globalSettings)
    var recursiveContents = emptyList<String>()
    var recursionLevel = 0
    var depthSkew = 0
    var passCount = 0

    while (activatedIds.size < sortedEntries.size) {
        if (maxRecursionSteps != null && passCount >= maxRecursionSteps) {
            break
        }
        passCount++

        val recursiveContext = recursiveContents.joinToString("\n")
        val candidates = sortedEntries.mapNotNull { entry ->
            if (entry.id in activatedIds) return@mapNotNull null
            val isSticky = entry.id in timedEffects.stickyEntryIds
            if (!entry.matchesRecursionPhase(recursionLevel, isSticky)) return@mapNotNull null
            if (entry.id in timedEffects.delayedEntryIds) return@mapNotNull null
            if (entry.id in timedEffects.cooldownEntryIds && !isSticky) return@mapNotNull null

            val recentMessagesText = buildTriggerRecentMessagesText(
                baseContext = extractContextForMatching(
                    messages = historyMessages,
                    scanDepth = entry.effectiveScanDepth(globalSettings, depthSkew),
                    includeNames = globalSettings.includeNames,
                    userName = userName,
                    assistantName = assistantName,
                ),
                recursiveContext = recursiveContext,
            )
            val currentTriggerContext = triggerContext.copy(recentMessagesText = recentMessagesText)
            val triggered = if (isSticky) {
                true
            } else {
                entry.matchesTriggerKeywords(
                    context = recentMessagesText,
                    triggerContext = currentTriggerContext,
                    globalSettings = globalSettings,
                )
            }
            if (!triggered) return@mapNotNull null

            LorebookCandidate(
                entry = entry,
                isSticky = isSticky,
                score = entry.matchScore(
                    context = recentMessagesText,
                    triggerContext = currentTriggerContext,
                    globalSettings = globalSettings,
                ),
            )
        }.toMutableList()

        if (candidates.isNotEmpty()) {
            filterCandidatesByInclusionGroups(
                candidates = candidates,
                alreadyActivatedEntries = activatedEntries,
                globalSettings = globalSettings,
            )
        }

        val newlyActivated = selectBudgetedCandidates(
            candidates = candidates,
            budget = budget,
            activatedEntries = activatedEntries,
        )
        if (newlyActivated.isNotEmpty()) {
            activatedEntries += newlyActivated
            activatedIds += newlyActivated.map { it.id }
            runtimeState?.recordActivatedEntries(
                entries = newlyActivated,
                messageCount = historyMessageCount,
            )
        }

        val newRecursiveContents = newlyActivated
            .filterNot { it.preventsRecursion() }
            .mapNotNull { entry ->
                entry.content.trim().takeIf { it.isNotBlank() }
            }
        if (newRecursiveContents.isNotEmpty()) {
            recursiveContents = recursiveContents + newRecursiveContents
        }

        val shouldContinueRecursion = recursiveEnabled && (
            newRecursiveContents.isNotEmpty() ||
                hasPendingDelayedRecursionLevel(sortedEntries, activatedIds, recursionLevel)
            )
        if (shouldContinueRecursion) {
            recursionLevel++
            continue
        }

        val currentDepth = sortedEntries.maxOfOrNull { entry ->
            entry.effectiveScanDepth(globalSettings, depthSkew)
        } ?: globalSettings.scanDepth
        val canExpandForMinActivations = minActivations > 0 &&
            activatedEntries.size < minActivations &&
            currentDepth < historyMessageCount &&
            (globalSettings.minActivationsDepthMax <= 0 || currentDepth < globalSettings.minActivationsDepthMax)
        if (canExpandForMinActivations) {
            depthSkew++
            continue
        }

        break
    }

    return activatedEntries.map { entry ->
        entry.copy(
            content = entry.content.replaceRegexes(
                assistant = assistant,
                settings = settings,
                scope = entry.toAffectScope(),
                phase = AssistantRegexApplyPhase.PROMPT_ONLY,
                messageDepthFromEnd = entry.injectDepth.takeIf { entry.position == me.rerere.rikkahub.data.model.InjectionPosition.AT_DEPTH },
                placement = AssistantRegexPlacement.WORLD_INFO,
            )
        )
    }
}

private fun Lorebook.resolveLorebookBudget(
    assistant: Assistant,
    globalSettings: LorebookGlobalSettings,
): Int? {
    val explicitBudget = tokenBudget?.takeIf { it > 0 }
    val percentBudget = assistant.maxTokens
        ?.takeIf { it > 0 && globalSettings.budgetPercent > 0 }
        ?.let { maxTokens ->
            ((maxTokens * globalSettings.budgetPercent) / 100.0)
                .roundToInt()
                .coerceAtLeast(1)
        }
    val baseBudget = explicitBudget ?: percentBudget ?: globalSettings.budgetCap.takeIf { it > 0 }
    return baseBudget?.let { budget ->
        if (globalSettings.budgetCap > 0) {
            min(budget, globalSettings.budgetCap)
        } else {
            budget
        }
    }
}

private fun PromptInjection.RegexInjection.effectiveScanDepth(
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
    return matchesRecursionPhase(recursionLevel = recursionLevel, isSticky = false)
}

private fun PromptInjection.RegexInjection.matchesRecursionPhase(
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

private fun PromptInjection.RegexInjection.delayMessages(): Int? {
    return stMetadata["delay"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

private fun PromptInjection.RegexInjection.ignoreBudget(): Boolean {
    return stBoolean("ignore_budget")
}

private fun PromptInjection.RegexInjection.groupNames(): List<String> {
    return stMetadata["group"]
        ?.split(',')
        ?.mapNotNull { value -> value.trim().takeIf { it.isNotBlank() } }
        ?: emptyList()
}

private fun PromptInjection.RegexInjection.groupOverride(): Boolean {
    return stBoolean("group_override")
}

private fun PromptInjection.RegexInjection.useGroupScoring(globalSettings: LorebookGlobalSettings): Boolean {
    return globalSettings.useGroupScoring || stBoolean("use_group_scoring")
}

private fun PromptInjection.RegexInjection.groupWeight(): Int {
    return stMetadata["group_weight"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 100
}

private fun hasPendingDelayedRecursionLevel(
    entries: List<PromptInjection.RegexInjection>,
    activatedIds: Set<Uuid>,
    recursionLevel: Int,
): Boolean {
    return entries.any { entry ->
        entry.id !in activatedIds && (entry.recursionDelayLevel() ?: 0) > recursionLevel
    }
}

private fun filterCandidatesByInclusionGroups(
    candidates: MutableList<LorebookCandidate>,
    alreadyActivatedEntries: List<PromptInjection.RegexInjection>,
    globalSettings: LorebookGlobalSettings,
) {
    if (candidates.isEmpty()) return
    val grouped = linkedMapOf<String, MutableList<LorebookCandidate>>()
    candidates.forEach { candidate ->
        candidate.entry.groupNames().forEach { groupName ->
            grouped.getOrPut(groupName) { mutableListOf() }.add(candidate)
        }
    }
    if (grouped.isEmpty()) return

    fun removeCandidate(candidate: LorebookCandidate) {
        candidates.remove(candidate)
    }

    grouped.forEach { (_, originalGroup) ->
        val group = originalGroup.filter { it in candidates }
        val stickyEntries = group.filter { it.isSticky }
        if (stickyEntries.isNotEmpty()) {
            group.filterNot { it.isSticky }.forEach(::removeCandidate)
        }
    }

    grouped.forEach { (_, originalGroup) ->
        val group = originalGroup.filter { it in candidates }
        if (group.size <= 1 || group.any { it.isSticky }) return@forEach
        if (!globalSettings.useGroupScoring && !group.any { it.entry.useGroupScoring(globalSettings) }) return@forEach
        val maxScore = group.maxOf { it.score }
        group.forEach { candidate ->
            if (candidate.entry.useGroupScoring(globalSettings) && candidate.score < maxScore) {
                removeCandidate(candidate)
            }
        }
    }

    grouped.forEach { (groupName, originalGroup) ->
        val group = originalGroup.filter { it in candidates }
        if (group.size <= 1 || group.any { it.isSticky }) return@forEach
        if (alreadyActivatedEntries.any { groupName in it.groupNames() }) {
            group.forEach(::removeCandidate)
            return@forEach
        }

        val overrideWinner = group
            .filter { it.entry.groupOverride() }
            .maxByOrNull { it.entry.priority }
        if (overrideWinner != null) {
            group.filter { it != overrideWinner }.forEach(::removeCandidate)
            return@forEach
        }

        val totalWeight = group.sumOf { it.entry.groupWeight() }.coerceAtLeast(1)
        val roll = Random.nextInt(totalWeight)
        var currentWeight = 0
        var winner = group.last()
        for (candidate in group) {
            currentWeight += candidate.entry.groupWeight()
            if (roll < currentWeight) {
                winner = candidate
                break
            }
        }
        group.filter { it != winner }.forEach(::removeCandidate)
    }
}

private fun selectBudgetedCandidates(
    candidates: List<LorebookCandidate>,
    budget: Int?,
    activatedEntries: List<PromptInjection.RegexInjection>,
): List<PromptInjection.RegexInjection> {
    if (candidates.isEmpty()) return emptyList()
    val orderedCandidates = candidates.sortedWith(
        compareByDescending<LorebookCandidate> { it.isSticky }
            .thenByDescending { it.entry.priority }
    )
    val selected = mutableListOf<PromptInjection.RegexInjection>()
    var currentBudget = activatedEntries.sumOf { estimateLorebookTokenCount(it.content) }
    var overflowed = false
    var remainingIgnoreBudget = orderedCandidates.count { it.entry.ignoreBudget() }

    for (candidate in orderedCandidates) {
        remainingIgnoreBudget -= if (candidate.entry.ignoreBudget()) 1 else 0
        if (overflowed && !candidate.entry.ignoreBudget()) {
            if (remainingIgnoreBudget > 0) continue
            break
        }
        if (!candidate.entry.passesProbabilityCheck(forceSuccess = candidate.isSticky)) continue

        val contentTokens = estimateLorebookTokenCount(candidate.entry.content)
        if (budget != null && budget > 0 && !candidate.entry.ignoreBudget() && currentBudget + contentTokens >= budget) {
            overflowed = true
            continue
        }

        currentBudget += contentTokens
        selected += candidate.entry
    }

    return selected
}

private fun estimateLorebookTokenCount(text: String): Int {
    val content = text.trim()
    if (content.isEmpty()) return 0
    return Regex("""\p{L}[\p{L}\p{N}_'-]*|\p{N}+|[^\s]""")
        .findAll(content)
        .count()
}

private fun PromptInjection.RegexInjection.toAffectScope(): AssistantAffectScope {
    return when (role) {
        MessageRole.SYSTEM -> AssistantAffectScope.SYSTEM
        MessageRole.USER -> AssistantAffectScope.USER
        MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
        else -> AssistantAffectScope.SYSTEM
    }
}
