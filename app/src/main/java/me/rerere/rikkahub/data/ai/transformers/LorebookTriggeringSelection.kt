package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.WorldInfoCharacterStrategy
import me.rerere.rikkahub.data.model.passesProbabilityCheck
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class LorebookCandidate(
    val entry: PromptInjection.RegexInjection,
    val isSticky: Boolean,
    val score: Int,
)

internal enum class LorebookScope {
    CHARACTER,
    GLOBAL,
}

internal data class ScopedLorebook(
    val lorebook: Lorebook,
    val scope: LorebookScope,
)

internal data class ActivatedLorebookEntry(
    val entry: PromptInjection.RegexInjection,
    val scope: LorebookScope,
)

internal fun resolveApplicableLorebooks(
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    settings: Settings?,
): List<ScopedLorebook> {
    val enabledLorebooks = lorebooks.filter { it.enabled }
    if (enabledLorebooks.isEmpty()) return emptyList()

    if (settings == null) {
        return enabledLorebooks
            .filter { assistant.lorebookIds.contains(it.id) }
            .map { lorebook ->
                ScopedLorebook(
                    lorebook = lorebook,
                    scope = LorebookScope.CHARACTER,
                )
            }
    }

    val restrictedLorebookIds = settings.assistants
        .flatMapTo(mutableSetOf()) { it.lorebookIds }

    return enabledLorebooks.mapNotNull { lorebook ->
        when {
            assistant.lorebookIds.contains(lorebook.id) -> ScopedLorebook(
                lorebook = lorebook,
                scope = LorebookScope.CHARACTER,
            )

            lorebook.id !in restrictedLorebookIds -> ScopedLorebook(
                lorebook = lorebook,
                scope = LorebookScope.GLOBAL,
            )

            else -> null
        }
    }
}

internal fun selectTriggeredLorebookEntries(
    entries: List<ActivatedLorebookEntry>,
    budget: Int?,
    strategy: WorldInfoCharacterStrategy,
): List<PromptInjection.RegexInjection> {
    if (entries.isEmpty()) return emptyList()

    val orderedEntries = when (strategy) {
        WorldInfoCharacterStrategy.EVENLY -> entries.sortedByDescending { it.entry.priority }
        WorldInfoCharacterStrategy.CHARACTER_FIRST -> entries.sortedWith(
            compareBy<ActivatedLorebookEntry> { if (it.scope == LorebookScope.CHARACTER) 0 else 1 }
                .thenByDescending { it.entry.priority },
        )

        WorldInfoCharacterStrategy.GLOBAL_FIRST -> entries.sortedWith(
            compareBy<ActivatedLorebookEntry> { if (it.scope == LorebookScope.GLOBAL) 0 else 1 }
                .thenByDescending { it.entry.priority },
        )
    }

    if (budget == null || budget <= 0) {
        return orderedEntries.map { it.entry }
    }

    val selected = mutableListOf<PromptInjection.RegexInjection>()
    var currentBudget = 0

    for (candidate in orderedEntries) {
        val contentTokens = estimateLorebookTokenCount(candidate.entry.content)
        if (!candidate.entry.ignoreBudget() && currentBudget + contentTokens > budget) {
            continue
        }

        currentBudget += contentTokens
        selected += candidate.entry
    }

    return selected
}

internal fun Lorebook.explicitBudgetOrNull(): Int? {
    return tokenBudget?.takeIf { it > 0 }
}

internal fun resolveSharedLorebookBudget(
    assistant: Assistant,
    globalSettings: LorebookGlobalSettings,
): Int? {
    val percentBudget = assistant.maxTokens
        ?.takeIf { it > 0 && globalSettings.budgetPercent > 0 }
        ?.let { maxTokens ->
            ((maxTokens * globalSettings.budgetPercent) / 100.0)
                .roundToInt()
                .coerceAtLeast(1)
        }
    val baseBudget = percentBudget ?: globalSettings.budgetCap.takeIf { it > 0 }
    return baseBudget?.let { budget ->
        if (globalSettings.budgetCap > 0) {
            min(budget, globalSettings.budgetCap)
        } else {
            budget
        }
    }
}

internal fun filterCandidatesByInclusionGroups(
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

internal fun selectBudgetedCandidates(
    candidates: List<LorebookCandidate>,
    budget: Int?,
    activatedEntries: List<PromptInjection.RegexInjection>,
): List<PromptInjection.RegexInjection> {
    if (candidates.isEmpty()) return emptyList()
    val orderedCandidates = candidates.sortedWith(
        compareByDescending<LorebookCandidate> { it.isSticky }
            .thenByDescending { it.entry.priority },
    )
    val selected = mutableListOf<PromptInjection.RegexInjection>()
    var currentBudget = activatedEntries.sumOf { estimateLorebookTokenCount(it.content) }

    for (candidate in orderedCandidates) {
        if (!candidate.entry.passesProbabilityCheck(forceSuccess = candidate.isSticky)) continue

        val contentTokens = estimateLorebookTokenCount(candidate.entry.content)
        if (budget != null && budget > 0 && !candidate.entry.ignoreBudget() && currentBudget + contentTokens > budget) {
            continue
        }

        currentBudget += contentTokens
        selected += candidate.entry
    }

    return selected
}

internal fun estimateLorebookTokenCount(text: String): Int {
    val content = text.trim()
    if (content.isEmpty()) return 0
    return Regex("""\p{L}[\p{L}\p{N}_'-]*|\p{N}+|[^\s]""")
        .findAll(content)
        .count()
}
