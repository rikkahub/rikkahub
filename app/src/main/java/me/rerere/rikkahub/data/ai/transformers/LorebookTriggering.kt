package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegexApplyPhase
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.WorldInfoCharacterStrategy
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.matchScore
import me.rerere.rikkahub.data.model.matchesTriggerKeywords
import me.rerere.rikkahub.data.model.replaceRegexes
import kotlin.uuid.Uuid

internal fun collectTriggeredLorebookEntries(
    historyMessages: List<UIMessage>,
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    triggerContext: LorebookTriggerContext,
    settings: Settings? = null,
    runtimeState: LorebookRuntimeState? = null,
): List<PromptInjection.RegexInjection> {
    val globalSettings = settings?.lorebookGlobalSettings ?: LorebookGlobalSettings()
    val applicableLorebooks = resolveApplicableLorebooks(
        assistant = assistant,
        lorebooks = lorebooks,
        settings = settings,
    )
    if (applicableLorebooks.isEmpty()) return emptyList()

    val nonSystemMessages = historyMessages.filter { it.role != MessageRole.SYSTEM }
    val userName = settings?.effectiveUserName().orEmpty().ifBlank { "User" }
    val assistantName = assistant.stCharacterData?.name?.ifBlank { assistant.name } ?: assistant.name.ifBlank { "Assistant" }
    val sharedBudget = resolveSharedLorebookBudget(assistant, globalSettings)
    return selectTriggeredLorebookEntries(
        entries = applicableLorebooks.flatMap { scopedLorebook ->
            scopedLorebook.lorebook.collectTriggeredEntries(
                historyMessages = nonSystemMessages,
                triggerContext = triggerContext,
                assistant = assistant,
                settings = settings,
                runtimeState = runtimeState,
                globalSettings = globalSettings,
                userName = userName,
                assistantName = assistantName,
                budget = scopedLorebook.lorebook.explicitBudgetOrNull(),
            ).map { entry ->
                ActivatedLorebookEntry(
                    entry = entry,
                    scope = scopedLorebook.scope,
                )
            }
        },
        budget = sharedBudget,
        strategy = globalSettings.characterStrategy,
    )
}

private fun Lorebook.collectTriggeredEntries(
    historyMessages: List<UIMessage>,
    triggerContext: LorebookTriggerContext,
    assistant: Assistant,
    settings: Settings?,
    runtimeState: LorebookRuntimeState?,
    globalSettings: LorebookGlobalSettings,
    userName: String,
    assistantName: String,
    budget: Int?,
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
