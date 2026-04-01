package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

data class ResolvedStPresetState(
    val presets: List<SillyTavernPreset>,
    val selectedPresetId: Uuid?,
) {
    val activePreset: SillyTavernPreset?
        get() = presets.firstOrNull { it.id == selectedPresetId } ?: presets.firstOrNull()
}

private val LEGACY_ST_PRESET_ID: Uuid = Uuid.parse("2b9799e9-7ec5-4d9d-9c1b-5fd4f2c8138e")

fun Settings.resolveStPresetState(): ResolvedStPresetState {
    val distinctPresets = stPresets.distinctBy { it.id }
    val migratedPresets = when {
        distinctPresets.isNotEmpty() -> {
            if (regexes.isNotEmpty() && distinctPresets.none { it.regexes.isNotEmpty() }) {
                val fallbackPresetId = selectedStPresetId ?: distinctPresets.firstOrNull()?.id
                distinctPresets.map { preset ->
                    if (preset.id == fallbackPresetId) {
                        preset.copy(regexes = regexes)
                    } else {
                        preset
                    }
                }
            } else {
                distinctPresets
            }
        }

        stPresetTemplate != null -> listOf(
            SillyTavernPreset(
                id = selectedStPresetId ?: LEGACY_ST_PRESET_ID,
                template = stPresetTemplate,
                regexes = regexes,
            )
        )

        else -> emptyList()
    }
    val normalizedSelectedPresetId = migratedPresets
        .firstOrNull { it.id == selectedStPresetId }
        ?.id
        ?: migratedPresets.firstOrNull()?.id

    return ResolvedStPresetState(
        presets = migratedPresets,
        selectedPresetId = normalizedSelectedPresetId,
    )
}

fun Settings.resolvedStPresets(): List<SillyTavernPreset> = resolveStPresetState().presets

fun Settings.normalizeStPresetState(): Settings {
    val resolvedState = resolveStPresetState()
    val activePreset = resolvedState.activePreset
    return copy(
        stPresets = resolvedState.presets,
        selectedStPresetId = resolvedState.selectedPresetId,
        stPresetTemplate = activePreset?.template ?: stPresetTemplate,
        regexes = activePreset?.regexes ?: regexes,
    )
}

fun Settings.selectedStPreset(): SillyTavernPreset? {
    return resolveStPresetState().activePreset
}

fun Settings.activeStPreset(): SillyTavernPreset? = selectedStPreset()

fun Settings.activeStPresetTemplate(): SillyTavernPromptTemplate? {
    return resolveStPresetState().activePreset?.template ?: stPresetTemplate
}

fun Settings.activeStPresetRegexes(): List<AssistantRegex> {
    val activePreset = resolveStPresetState().activePreset ?: return emptyList()
    return if (activePreset.regexEnabled) {
        activePreset.regexes
    } else {
        emptyList()
    }
}

fun Settings.activeGlobalRegexes(): List<AssistantRegex> {
    return if (globalRegexEnabled) globalRegexes else emptyList()
}

private fun Settings.legacyRuntimeRegexes(): List<AssistantRegex> {
    if (regexes.isEmpty()) return emptyList()
    return if (resolveStPresetState().activePreset == null) regexes else emptyList()
}

fun Settings.runtimeRegexes(): List<AssistantRegex> = activeGlobalRegexes() + activeStPresetRegexes() + legacyRuntimeRegexes()

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
    val resolvedState = resolveStPresetState()
    if (resolvedState.presets.isNotEmpty()) return normalizeStPresetState()
    val template = stPresetTemplate ?: defaultSillyTavernPromptTemplate()
    val preset = SillyTavernPreset(template = template, regexes = regexes)
    return copy(
        stPresets = listOf(preset),
        selectedStPresetId = preset.id,
        stPresetTemplate = preset.template,
        regexes = preset.regexes,
    ).normalizeStPresetState()
}

fun Settings.upsertStPreset(
    preset: SillyTavernPreset,
    select: Boolean = false,
): Settings {
    val resolvedState = resolveStPresetState()
    val updated = resolvedState.presets.toMutableList()
    val index = updated.indexOfFirst { it.id == preset.id }
    if (index >= 0) {
        updated[index] = preset
    } else {
        updated += preset
    }
    val selectedId = when {
        select -> preset.id
        resolvedState.selectedPresetId in updated.map { it.id }.toSet() -> resolvedState.selectedPresetId
        else -> updated.firstOrNull()?.id
    }
    return copy(
        stPresets = updated,
        selectedStPresetId = selectedId,
        stPresetTemplate = updated.firstOrNull { it.id == selectedId }?.template ?: stPresetTemplate,
        regexes = updated.firstOrNull { it.id == selectedId }?.regexes ?: regexes,
    ).normalizeStPresetState()
}

fun Settings.removeStPreset(presetId: Uuid): Settings {
    val resolvedState = resolveStPresetState()
    val updated = resolvedState.presets.filterNot { it.id == presetId }
    val selectedId = when {
        resolvedState.selectedPresetId == presetId -> updated.firstOrNull()?.id
        resolvedState.selectedPresetId in updated.map { it.id }.toSet() -> resolvedState.selectedPresetId
        else -> updated.firstOrNull()?.id
    }
    return copy(
        stPresets = updated,
        selectedStPresetId = selectedId,
        stPresetTemplate = updated.firstOrNull { it.id == selectedId }?.template,
        regexes = updated.firstOrNull { it.id == selectedId }?.regexes ?: emptyList(),
    ).normalizeStPresetState()
}

fun Settings.selectStPreset(presetId: Uuid): Settings {
    val activePreset = resolveStPresetState().presets.firstOrNull { it.id == presetId } ?: return normalizeStPresetState()
    return copy(
        selectedStPresetId = activePreset.id,
        stPresetTemplate = activePreset.template,
        regexes = activePreset.regexes,
    ).normalizeStPresetState()
}
