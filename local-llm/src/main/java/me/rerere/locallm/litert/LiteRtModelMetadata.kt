package me.rerere.locallm.litert

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

/**
 * Derives `Model.inputModalities` + `Model.abilities` for a LiteRT model file from the
 * curated [LiteRtModelDefaults] config. Mirrors how cloud models populate the same fields
 * via `ModelRegistry.MODEL_INPUT_MODALITIES` / `MODEL_ABILITIES` — but driven by the
 * LiteRT catalog instead of the cloud model definitions.
 *
 * For unknown files (HF-URL-pasted models that aren't in our catalog), falls back to the
 * safest assumption: TEXT-only input, TOOL ability (LiteRT-supported models are typically
 * tool-tuned; DeepSeek-R1-Distill was excluded for this reason).
 */
object LiteRtModelMetadata {

    data class Capabilities(
        val inputModalities: List<Modality>,
        val abilities: List<ModelAbility>,
    )

    /**
     * Pure mapper: catalog file -> `(inputModalities, abilities)`. JVM-unit-testable.
     *
     * Mapping rules (matches [LiteRtModelConfig]):
     *   - `supportsImage = true`  -> add Modality.IMAGE.
     *   - `supportsThinking = true` -> add ModelAbility.REASONING.
     *   - ModelAbility.TOOL is always added (every model we ship through the LiteRT
     *     catalog is tool-tuned; the agent loop depends on it; DeepSeek-R1-Distill was
     *     deliberately removed from the catalog because it isn't tool-tuned).
     */
    fun deriveCapabilities(modelFile: String): Capabilities {
        val config = LiteRtModelDefaults.forModelFile(modelFile)
        val modalities = buildList {
            add(Modality.TEXT)
            if (config.supportsImage) add(Modality.IMAGE)
        }
        val abilities = buildList {
            add(ModelAbility.TOOL)
            if (config.supportsThinking) add(ModelAbility.REASONING)
        }
        return Capabilities(modalities, abilities)
    }

    /**
     * Additive merge for the startup migration: take the persisted model's current
     * capabilities and UNION them with the catalog's. Never removes anything the user
     * had — so a user who manually checked REASONING on a model the catalog says is
     * text-only keeps their override.
     *
     * Stable order: TEXT before IMAGE in modalities; TOOL before REASONING in abilities.
     */
    fun mergeAdditive(current: Capabilities, target: Capabilities): Capabilities {
        val modalitySet = current.inputModalities.toMutableSet().apply {
            addAll(target.inputModalities)
        }
        val abilitySet = current.abilities.toMutableSet().apply {
            addAll(target.abilities)
        }
        return Capabilities(
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE).filter { it in modalitySet },
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING).filter { it in abilitySet },
        )
    }
}
