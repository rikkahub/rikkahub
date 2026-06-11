package me.rerere.ai.runtime.contract

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * Neutral port resolving a model id + its provider for a turn (issue #243 §B). All referenced types
 * (`Model`, `ProviderSetting`, `Provider`) live in `:ai`, so this contract carries no app dependency.
 *
 * The app adapter delegates to the existing `findModelById` / `Model.findProvider` /
 * `ProviderManager.getProviderByType` lookups, so the overwrite/fallback semantics are NOT
 * duplicated here — the runtime depends only on the abstraction.
 */
interface ModelProviderResolver {
    /** Resolve a model by id within [turn], or null if absent. */
    fun findModel(modelId: Uuid, turn: TurnConfig): Model?

    /** Resolve the provider setting backing [model] within [turn] (honoring per-model overwrite). */
    fun findProvider(model: Model, turn: TurnConfig): ProviderSetting?

    /** The provider handler for a resolved [setting]. */
    fun provider(setting: ProviderSetting): Provider<*>
}
