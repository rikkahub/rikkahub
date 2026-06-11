package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * Binds the neutral [ModelProviderResolver] over the existing app lookups (issue #243 slice 3). The
 * overwrite/fallback semantics live in `Model.findProvider` / `List<ProviderSetting>.findModelById`
 * and are delegated to here — zero policy duplication.
 */
class AppModelProviderResolver(
    private val providerManager: ProviderManager,
) : ModelProviderResolver {

    override fun findModel(modelId: Uuid, turn: TurnConfig): Model? =
        turn.providers.findModelById(modelId)

    override fun findProvider(model: Model, turn: TurnConfig): ProviderSetting? =
        model.findProvider(turn.providers)

    override fun provider(setting: ProviderSetting): Provider<*> =
        providerManager.getProviderByType(setting)
}
