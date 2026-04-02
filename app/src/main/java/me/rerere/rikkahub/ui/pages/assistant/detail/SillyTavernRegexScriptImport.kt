package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.AssistantRegexSourceKind
import me.rerere.rikkahub.data.model.dedupKey
import me.rerere.rikkahub.data.model.normalizeAssistantRegexPattern
import kotlin.uuid.Uuid

internal fun parseRegexScripts(
    element: JsonElement?,
    sourceName: String,
    sourceKind: AssistantRegexSourceKind = AssistantRegexSourceKind.ST_SCRIPT,
): List<AssistantRegex> {
    val scripts = element?.jsonArrayOrNull()
        ?.mapNotNull { runCatching { ImportJson.decodeFromJsonElement<StRegexScriptImport>(it) }.getOrNull() }
        ?: return emptyList()
    return scripts.mapNotNull { script ->
        mapRegexScript(
            sourceName = sourceName,
            name = script.scriptName.ifBlank { sourceName },
            findRegex = script.findRegex,
            replaceString = script.replaceString,
            placement = script.placement,
            disabled = script.disabled,
            promptOnly = script.promptOnly,
            markdownOnly = script.markdownOnly,
            minDepth = script.minDepth,
            maxDepth = script.maxDepth,
            trimStrings = script.trimStrings,
            runOnEdit = script.runOnEdit,
            substituteRegex = script.substituteRegex,
            sourceKind = sourceKind,
        )
    }
}

internal fun regexDedupKey(regex: AssistantRegex): String {
    return regex.dedupKey()
}

internal fun mapRegexScript(
    sourceName: String,
    name: String,
    findRegex: String,
    replaceString: String,
    placement: List<Int>,
    disabled: Boolean,
    promptOnly: Boolean,
    markdownOnly: Boolean,
    minDepth: Int?,
    maxDepth: Int?,
    trimStrings: List<String> = emptyList(),
    runOnEdit: Boolean = true,
    substituteRegex: Int = 0,
    affectingScopeOverride: Set<AssistantAffectScope>? = null,
    stPlacementsOverride: Set<Int>? = null,
    sourceKind: AssistantRegexSourceKind = AssistantRegexSourceKind.ST_SCRIPT,
    sourceRef: String = "",
): AssistantRegex? {
    val normalizedPattern = normalizeAssistantRegexPattern(findRegex) ?: return null
    val normalizedPlacement = placement.ifEmpty { listOf(AssistantRegexPlacement.AI_OUTPUT) }
    val affectingScope = affectingScopeOverride ?: buildSet {
        if (normalizedPlacement.contains(AssistantRegexPlacement.USER_INPUT)) add(AssistantAffectScope.USER)
        if (
            normalizedPlacement.contains(AssistantRegexPlacement.AI_OUTPUT) ||
            normalizedPlacement.contains(AssistantRegexPlacement.REASONING)
        ) {
            add(AssistantAffectScope.ASSISTANT)
        }
    }.ifEmpty {
        setOf(AssistantAffectScope.ASSISTANT)
    }

    return AssistantRegex(
        id = Uuid.random(),
        name = name.ifBlank { sourceName },
        enabled = !disabled,
        findRegex = normalizedPattern,
        rawFindRegex = findRegex,
        replaceString = replaceString,
        affectingScope = affectingScope,
        visualOnly = markdownOnly,
        promptOnly = promptOnly,
        minDepth = minDepth,
        maxDepth = maxDepth,
        trimStrings = trimStrings,
        runOnEdit = runOnEdit,
        substituteRegex = substituteRegex,
        stPlacements = stPlacementsOverride ?: normalizedPlacement.toSet(),
        sourceKind = sourceKind,
        sourceRef = sourceRef,
    )
}

@Serializable
internal data class StRegexScriptImport(
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val trimStrings: List<String> = emptyList(),
    val runOnEdit: Boolean = true,
    val substituteRegex: Int = 0,
)
