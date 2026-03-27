package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

fun Settings.selectedStPreset(): SillyTavernPreset? {
    return stPresets
        .firstOrNull { it.id == selectedStPresetId }
        ?: stPresets.firstOrNull()
        ?: stPresetTemplate?.let { template ->
            SillyTavernPreset(
                id = selectedStPresetId ?: Uuid.random(),
                template = template,
                regexes = regexes,
            )
        }
}

fun Settings.activeStPreset(): SillyTavernPreset? = selectedStPreset()

fun Settings.activeStPresetTemplate(): SillyTavernPromptTemplate? {
    return selectedStPreset()?.template ?: stPresetTemplate
}

fun Settings.activeStPresetRegexes(): List<AssistantRegex> {
    return selectedStPreset()?.regexes ?: regexes
}

fun Settings.applyActiveStPresetSampling(assistant: Assistant): Assistant {
    if (!stPresetEnabled) return assistant
    val sampling = selectedStPreset()?.sampling ?: return assistant
    if (!sampling.hasConfiguredValues()) return assistant
    return assistant.copy(
        temperature = sampling.temperature,
        topP = sampling.topP,
        maxTokens = sampling.maxTokens,
        frequencyPenalty = sampling.frequencyPenalty,
        presencePenalty = sampling.presencePenalty,
        minP = sampling.minP,
        topK = sampling.topK,
        topA = sampling.topA,
        repetitionPenalty = sampling.repetitionPenalty,
        seed = sampling.seed,
        stopSequences = sampling.stopSequences,
        openAIReasoningEffort = sampling.openAIReasoningEffort,
        openAIVerbosity = sampling.openAIVerbosity,
    )
}

fun Settings.ensureStPresetLibrary(): Settings {
    if (stPresets.isNotEmpty()) return this
    val template = stPresetTemplate ?: defaultSillyTavernPromptTemplate()
    val preset = SillyTavernPreset(template = template, regexes = regexes)
    return copy(
        stPresets = listOf(preset),
        selectedStPresetId = preset.id,
        stPresetTemplate = preset.template,
        regexes = preset.regexes,
    )
}

fun Settings.upsertStPreset(
    preset: SillyTavernPreset,
    select: Boolean = false,
): Settings {
    val updated = stPresets.toMutableList()
    val index = updated.indexOfFirst { it.id == preset.id }
    if (index >= 0) {
        updated[index] = preset
    } else {
        updated += preset
    }
    val selectedId = when {
        select -> preset.id
        selectedStPresetId in updated.map { it.id }.toSet() -> selectedStPresetId
        else -> updated.firstOrNull()?.id
    }
    return copy(
        stPresets = updated,
        selectedStPresetId = selectedId,
        stPresetTemplate = updated.firstOrNull { it.id == selectedId }?.template ?: stPresetTemplate,
        regexes = updated.firstOrNull { it.id == selectedId }?.regexes ?: regexes,
    )
}

fun Settings.removeStPreset(presetId: Uuid): Settings {
    val updated = stPresets.filterNot { it.id == presetId }
    val selectedId = when {
        selectedStPresetId == presetId -> updated.firstOrNull()?.id
        selectedStPresetId in updated.map { it.id }.toSet() -> selectedStPresetId
        else -> updated.firstOrNull()?.id
    }
    return copy(
        stPresets = updated,
        selectedStPresetId = selectedId,
        stPresetTemplate = updated.firstOrNull { it.id == selectedId }?.template,
        regexes = updated.firstOrNull { it.id == selectedId }?.regexes ?: emptyList(),
    )
}

fun Settings.selectStPreset(presetId: Uuid): Settings {
    val activePreset = stPresets.firstOrNull { it.id == presetId } ?: return this
    return copy(
        selectedStPresetId = activePreset.id,
        stPresetTemplate = activePreset.template,
        regexes = activePreset.regexes,
    )
}
