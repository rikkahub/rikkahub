package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import kotlin.uuid.Uuid

internal fun parseRegexScripts(element: JsonElement?, sourceName: String): List<AssistantRegex> {
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
        )
    }
}

internal fun regexDedupKey(regex: AssistantRegex): String {
    return listOf(
        regex.name,
        regex.findRegex,
        regex.replaceString,
        regex.affectingScope.sortedBy { scope -> scope.name }.joinToString(","),
        regex.visualOnly.toString(),
        regex.promptOnly.toString(),
        regex.minDepth?.toString().orEmpty(),
        regex.maxDepth?.toString().orEmpty(),
        regex.trimStrings.joinToString("\u0000"),
        regex.runOnEdit.toString(),
        regex.substituteRegex.toString(),
        regex.stPlacements.sorted().joinToString(","),
    ).joinToString("|")
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
): AssistantRegex? {
    val normalizedPattern = normalizeImportedRegexPattern(findRegex) ?: return null
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

private fun normalizeImportedRegexPattern(findRegex: String): String? {
    if (findRegex.isBlank()) return null
    val match = Regex("""^/(.*?)(?<!\\)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        .matchEntire(findRegex)
        ?: return findRegex

    val pattern = match.groupValues[1]
    val flags = match.groupValues[2]
    val inlineFlags = buildString {
        if ('i' in flags) append('i')
        if ('m' in flags) append('m')
        if ('s' in flags) append('s')
    }
    return if (inlineFlags.isEmpty()) pattern else "(?$inlineFlags)$pattern"
}
