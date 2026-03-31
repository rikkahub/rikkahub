package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal fun buildPresetPromptOrder(preset: StPresetImport): List<SillyTavernPromptOrderItem> {
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

internal fun buildPresetPromptItems(preset: StPresetImport): List<SillyTavernPromptItem> {
    return preset.prompts.map { prompt ->
        SillyTavernPromptItem(
            identifier = prompt.identifier,
            name = prompt.name.orEmpty(),
            role = prompt.role.toMessageRole(),
            content = prompt.content.orEmpty(),
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

internal fun buildPresetTemplate(
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

internal fun buildPresetRegexes(
    json: JsonObject,
    preset: StPresetImport,
): List<me.rerere.rikkahub.data.model.AssistantRegex> {
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
    }.distinctBy(::regexDedupKey)
}

private fun selectPresetOrder(promptOrders: List<StPresetOrderList>): List<StPresetOrderItem> {
    return listOf(100001L, 100000L)
        .mapNotNull { preferred -> promptOrders.find { it.characterId == preferred && it.order.isNotEmpty() } }
        .firstOrNull()
        ?.order
        ?: promptOrders.firstOrNull { it.order.isNotEmpty() }?.order
        ?: emptyList()
}

internal fun resolvePresetStopSequences(
    json: JsonObject,
    preset: StPresetImport,
): List<String> {
    val topLevelStopSequences = when {
        preset.enableStopString == false -> emptyList()
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
