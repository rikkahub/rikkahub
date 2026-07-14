package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import me.rerere.rikkahub.data.sync.cloud.PerryCatalog

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val cloudSyncRepository: CloudSyncRepository,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    private val _monelBusy = MutableStateFlow(false)
    val monelBusy: StateFlow<Boolean> = _monelBusy.asStateFlow()

    private val _monelStatus = MutableStateFlow<String?>(null)
    val monelStatus: StateFlow<String?> = _monelStatus.asStateFlow()

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun importMonelProviders(onDone: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            _monelBusy.value = true
            val result = cloudSyncRepository.importMonelProviders()
            _monelStatus.value = result.fold(
                onSuccess = { "Imported $it Monel provider(s). Open each → Models → add to show in chat." },
                onFailure = { it.message ?: "Import failed" },
            )
            _monelBusy.value = false
            onDone(result)
        }
    }

    suspend fun listBrowseModels(provider: ProviderSetting): List<Model> {
        return if (PerryCatalog.isPerryGateway(provider)) {
            cloudSyncRepository.listMonelCatalogModels(provider)
        } else {
            emptyList()
        }
    }
}
