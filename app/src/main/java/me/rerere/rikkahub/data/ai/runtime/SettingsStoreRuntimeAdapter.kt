package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.contract.TurnConfigSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * Binds the neutral [TurnConfigSource] over the app [SettingsStore] (issue #243 slice 3). Pure
 * mapping — derives a [TurnConfig] snapshot from `settingsFlow`; no behavior change.
 */
class SettingsStoreRuntimeAdapter(
    private val settingsStore: SettingsStore,
    scope: CoroutineScope,
) : TurnConfigSource {

    override val snapshot: StateFlow<TurnConfig> = settingsStore.settingsFlow
        .map { it.toTurnConfig() }
        .stateIn(scope, SharingStarted.Eagerly, settingsStore.settingsFlow.value.toTurnConfig())

    override fun assistant(id: Uuid): AssistantConfig? =
        settingsStore.settingsFlow.value.assistants.find { it.id == id }?.toAssistantConfig()
}

private fun Settings.toTurnConfig(): TurnConfig = TurnConfig(
    defaultModelId = chatModelId,
    providers = providers,
    assistants = assistants.map { it.toAssistantConfig() },
)
