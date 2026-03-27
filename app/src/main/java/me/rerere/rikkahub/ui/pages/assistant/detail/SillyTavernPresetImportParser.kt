package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.transformers.stripInlineRegexBlocks
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.matchesGenerationType
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

internal fun parsePresetImport(
    json: JsonObject,
    sourceName: String,
): AssistantImportPayload {
    val preset = ImportJson.decodeFromJsonElement<StPresetImport>(json)
    val stopSequences = resolvePresetStopSequences(json, preset)
    val promptOrder = buildPresetPromptOrder(preset)
    val promptItems = buildPresetPromptItems(preset)
    val template = buildPresetTemplate(
        preset = preset,
        sourceName = sourceName,
        promptItems = promptItems,
        promptOrder = promptOrder,
    )
    val regexes = buildPresetRegexes(
        json = json,
        preset = preset,
        promptItems = promptItems,
        promptOrder = promptOrder,
    )

    return AssistantImportPayload(
        kind = AssistantImportKind.PRESET,
        sourceName = preset.name.ifBlank { sourceName },
        assistant = Assistant(
            name = preset.name.ifBlank { sourceName },
            temperature = preset.temperature?.toFloat(),
            topP = preset.topP?.toFloat(),
            maxTokens = preset.openAIMaxTokens,
            frequencyPenalty = preset.frequencyPenalty
                ?.takeUnless { it == 0.0 }
                ?.toFloat(),
            presencePenalty = preset.presencePenalty
                ?.takeUnless { it == 0.0 }
                ?.toFloat(),
            minP = preset.minP
                ?.takeUnless { it == 0.0 }
                ?.toFloat(),
            topK = preset.topK
                ?.takeUnless { it == 0 },
            topA = preset.topA
                ?.takeUnless { it == 0.0 }
                ?.toFloat(),
            repetitionPenalty = preset.repetitionPenalty
                ?.takeUnless { it == 1.0 }
                ?.toFloat(),
            seed = preset.seed
                ?.takeIf { it >= 0L },
            stopSequences = stopSequences,
            openAIReasoningEffort = preset.reasoningEffort.orEmpty(),
            openAIVerbosity = preset.verbosity
                ?.takeUnless { it.equals("auto", ignoreCase = true) }
                .orEmpty(),
        ),
        presetTemplate = template,
        regexes = regexes,
    )
}

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

private fun buildPresetPromptOrder(preset: StPresetImport): List<SillyTavernPromptOrderItem> {
    return selectPresetOrder(preset.promptOrder)
        .mapNotNull { item ->
            item.identifier
                ?.takeIf { it.isNotBlank() }
                ?.let { identifier ->
                    SillyTavernPromptOrderItem(
                        identifier = identifier,
                        enabled = item.enabled,
                    )
                }
        }
        .ifEmpty {
            preset.prompts
                .mapNotNull { prompt ->
                    prompt.identifier
                        .takeIf { it.isNotBlank() }
                        ?.let { identifier ->
                            SillyTavernPromptOrderItem(
                                identifier = identifier,
                                enabled = prompt.enabled ?: true,
                            )
                        }
                }
        }
}

private fun buildPresetPromptItems(preset: StPresetImport): List<SillyTavernPromptItem> {
    return preset.prompts.map { prompt ->
        SillyTavernPromptItem(
            identifier = prompt.identifier,
            name = prompt.name.orEmpty(),
            role = prompt.role.toMessageRole(),
            content = stripInlineRegexBlocks(prompt.content.orEmpty()),
            systemPrompt = prompt.systemPrompt ?: true,
            marker = prompt.marker ?: false,
            enabled = prompt.enabled ?: true,
            injectionPosition = if ((prompt.injectionPosition ?: 0) == 1) {
                StPromptInjectionPosition.ABSOLUTE
            } else {
                StPromptInjectionPosition.RELATIVE
            },
            injectionDepth = prompt.injectionDepth ?: 4,
            injectionOrder = prompt.injectionOrder ?: 100,
            injectionTriggers = prompt.injectionTriggers,
            forbidOverrides = prompt.forbidOverrides ?: false,
        )
    }
}

private fun buildPresetTemplate(
    preset: StPresetImport,
    sourceName: String,
    promptItems: List<SillyTavernPromptItem>,
    promptOrder: List<SillyTavernPromptOrderItem>,
): SillyTavernPromptTemplate {
    return SillyTavernPromptTemplate(
        sourceName = preset.name.ifBlank { sourceName },
        scenarioFormat = preset.scenarioFormat ?: "{{scenario}}",
        personalityFormat = preset.personalityFormat ?: "{{personality}}",
        wiFormat = preset.wiFormat ?: "{0}",
        mainPrompt = promptItems.find { it.identifier == "main" }?.content.orEmpty(),
        newChatPrompt = preset.newChatPrompt.orEmpty(),
        newGroupChatPrompt = preset.newGroupChatPrompt.orEmpty(),
        newExampleChatPrompt = preset.newExampleChatPrompt.orEmpty(),
        continueNudgePrompt = preset.continueNudgePrompt.orEmpty(),
        groupNudgePrompt = preset.groupNudgePrompt.orEmpty(),
        impersonationPrompt = preset.impersonationPrompt.orEmpty(),
        assistantPrefill = preset.assistantPrefill.orEmpty(),
        assistantImpersonation = preset.assistantImpersonation.orEmpty(),
        continuePrefill = preset.continuePrefill ?: false,
        continuePostfix = preset.continuePostfix.orEmpty(),
        sendIfEmpty = preset.sendIfEmpty.orEmpty(),
        namesBehavior = preset.namesBehavior,
        useSystemPrompt = preset.useSystemPrompt ?: false,
        squashSystemMessages = preset.squashSystemMessages ?: false,
        prompts = promptItems,
    ).withPromptOrder(promptOrder)
}

private fun buildPresetRegexes(
    json: JsonObject,
    preset: StPresetImport,
    promptItems: List<SillyTavernPromptItem>,
    promptOrder: List<SillyTavernPromptOrderItem>,
): List<AssistantRegex> {
    return buildList {
        addAll(parseRegexScripts(json["extensions"]?.jsonObject?.get("regex_scripts"), sourceName = preset.name))
        addAll(parseRegexScripts(
            json["extensions"]?.jsonObject
                ?.get("SPreset")
                ?.jsonObjectOrNull()
                ?.get("RegexBinding")
                ?.jsonObjectOrNull()
                ?.get("regexes"),
            sourceName = "${preset.name} (SPreset)",
        ))
        val promptOrderMap = promptOrder.associateBy { it.identifier }
        promptItems
            .filter { prompt ->
                val orderItem = promptOrderMap[prompt.identifier] ?: return@filter false
                orderItem.enabled && prompt.matchesGenerationType("normal")
            }
            .forEach { prompt ->
                val rawContent = preset.prompts
                    .firstOrNull { it.identifier == prompt.identifier }
                    ?.content
                    .orEmpty()
                addAll(parseInlinePromptRegexes(prompt.copy(content = rawContent)))
            }
    }.distinctBy(::regexDedupKey)
}

private fun parseInlinePromptRegexes(prompt: SillyTavernPromptItem): List<AssistantRegex> {
    val content = prompt.content
    if (content.isBlank()) return emptyList()

    val regex = Regex("""<regex(?:\s+order=(-?\d+))?>([\s\S]*?)</regex>""")
    return regex.findAll(content).mapIndexedNotNull { index, match ->
        val body = match.groupValues[2].trim()
        val jsonObject = runCatching {
            ImportJson.parseToJsonElement("{${body}}").jsonObject
        }.getOrNull() ?: return@mapIndexedNotNull null

        val entry = jsonObject.entries.firstOrNull() ?: return@mapIndexedNotNull null
        mapRegexScript(
            sourceName = prompt.name.ifBlank { prompt.identifier },
            name = "${prompt.name.ifBlank { prompt.identifier }} Regex ${index + 1}",
            findRegex = entry.key,
            replaceString = entry.value.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            placement = listOf(1),
            disabled = false,
            promptOnly = true,
            markdownOnly = false,
            minDepth = null,
            maxDepth = null,
            affectingScopeOverride = setOf(AssistantAffectScope.SYSTEM),
            stPlacementsOverride = emptySet(),
        )
    }.toList()
}

private fun mapRegexScript(
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

private fun selectPresetOrder(promptOrders: List<StPresetOrderList>): List<StPresetOrderItem> {
    return listOf(100001L, 100000L)
        .mapNotNull { preferred -> promptOrders.find { it.characterId == preferred && it.order.isNotEmpty() } }
        .firstOrNull()
        ?.order
        ?: promptOrders.firstOrNull { it.order.isNotEmpty() }?.order
        ?: emptyList()
}

private fun resolvePresetStopSequences(
    json: JsonObject,
    preset: StPresetImport,
): List<String> {
    val topLevelStopSequences = when {
        preset.stopStrings.isNotEmpty() -> preset.stopStrings
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        preset.enableStopString == true -> listOfNotNull(
            preset.stopString?.trim()?.takeIf { it.isNotEmpty() },
        )
        else -> emptyList()
    }
    if (topLevelStopSequences.isNotEmpty()) return topLevelStopSequences

    val chatSquash = json["extensions"]
        ?.jsonObjectOrNull()
        ?.get("SPreset")
        ?.jsonObjectOrNull()
        ?.get("ChatSquash")
        ?.jsonObjectOrNull()

    val chatSquashStopEnabled = chatSquash
        ?.get("enable_stop_string")
        ?.jsonPrimitiveOrNull
        ?.booleanOrNull
        ?: false

    val chatSquashStopString = chatSquash
        ?.get("stop_string")
        ?.jsonPrimitiveOrNull
        ?.contentOrNull
        ?.trim()
        .orEmpty()

    return if (chatSquashStopEnabled && chatSquashStopString.isNotEmpty()) {
        listOf(chatSquashStopString)
    } else {
        emptyList()
    }
}

@Serializable
private data class StPresetImport(
    val name: String = "",
    val temperature: Double? = null,
    val top_p: Double? = null,
    val openai_max_tokens: Int? = null,
    val frequency_penalty: Double? = null,
    val presence_penalty: Double? = null,
    val min_p: Double? = null,
    val top_k: Int? = null,
    val top_a: Double? = null,
    val repetition_penalty: Double? = null,
    val seed: Long? = null,
    val verbosity: String? = null,
    val enable_stop_string: Boolean? = null,
    val stop_string: String? = null,
    val stop_strings: List<String> = emptyList(),
    val names_behavior: Int? = null,
    val send_if_empty: String? = null,
    val assistant_prefill: String? = null,
    val assistant_impersonation: String? = null,
    val continue_prefill: Boolean? = null,
    val continue_postfix: String? = null,
    val new_chat_prompt: String? = null,
    val new_group_chat_prompt: String? = null,
    val new_example_chat_prompt: String? = null,
    val use_sysprompt: Boolean? = null,
    val squash_system_messages: Boolean? = null,
    val reasoning_effort: String? = null,
    val scenario_format: String? = null,
    val personality_format: String? = null,
    val wi_format: String? = null,
    val continue_nudge_prompt: String? = null,
    val group_nudge_prompt: String? = null,
    val impersonation_prompt: String? = null,
    val prompts: List<StPresetPromptImport> = emptyList(),
    val prompt_order: List<StPresetOrderList> = emptyList(),
) {
    val topP: Double?
        get() = top_p

    val openAIMaxTokens: Int?
        get() = openai_max_tokens

    val frequencyPenalty: Double?
        get() = frequency_penalty

    val presencePenalty: Double?
        get() = presence_penalty

    val minP: Double?
        get() = min_p

    val topK: Int?
        get() = top_k

    val topA: Double?
        get() = top_a

    val repetitionPenalty: Double?
        get() = repetition_penalty

    val enableStopString: Boolean?
        get() = enable_stop_string

    val stopString: String?
        get() = stop_string

    val stopStrings: List<String>
        get() = stop_strings

    val namesBehavior: Int?
        get() = names_behavior

    val sendIfEmpty: String?
        get() = send_if_empty

    val assistantPrefill: String?
        get() = assistant_prefill

    val assistantImpersonation: String?
        get() = assistant_impersonation

    val continuePrefill: Boolean?
        get() = continue_prefill

    val continuePostfix: String?
        get() = continue_postfix

    val newChatPrompt: String?
        get() = new_chat_prompt

    val newGroupChatPrompt: String?
        get() = new_group_chat_prompt

    val newExampleChatPrompt: String?
        get() = new_example_chat_prompt

    val useSystemPrompt: Boolean?
        get() = use_sysprompt

    val squashSystemMessages: Boolean?
        get() = squash_system_messages

    val reasoningEffort: String?
        get() = reasoning_effort

    val scenarioFormat: String?
        get() = scenario_format

    val personalityFormat: String?
        get() = personality_format

    val wiFormat: String?
        get() = wi_format

    val continueNudgePrompt: String?
        get() = continue_nudge_prompt

    val groupNudgePrompt: String?
        get() = group_nudge_prompt

    val impersonationPrompt: String?
        get() = impersonation_prompt

    val promptOrder: List<StPresetOrderList>
        get() = prompt_order
}

@Serializable
private data class StPresetPromptImport(
    val identifier: String = "",
    val name: String? = null,
    val role: String? = null,
    val content: String? = null,
    val system_prompt: Boolean? = null,
    val marker: Boolean? = null,
    val enabled: Boolean? = null,
    val injection_position: Int? = null,
    val injection_depth: Int? = null,
    val injection_order: Int? = null,
    val injection_trigger: List<String>? = null,
    val forbid_overrides: Boolean? = null,
) {
    val systemPrompt: Boolean?
        get() = system_prompt

    val injectionPosition: Int?
        get() = injection_position

    val injectionDepth: Int?
        get() = injection_depth

    val injectionOrder: Int?
        get() = injection_order

    val injectionTriggers: List<String>
        get() = injection_trigger
            ?.mapNotNull { trigger ->
                trigger
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotBlank() }
            }
            ?.distinct()
            ?: emptyList()

    val forbidOverrides: Boolean?
        get() = forbid_overrides
}

@Serializable
private data class StPresetOrderList(
    val character_id: Long? = null,
    val order: List<StPresetOrderItem> = emptyList(),
) {
    val characterId: Long?
        get() = character_id
}

@Serializable
private data class StPresetOrderItem(
    val identifier: String? = null,
    val enabled: Boolean = true,
)

@Serializable
private data class StRegexScriptImport(
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
