package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.model.Assistant

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
            topK = preset.topK,
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
