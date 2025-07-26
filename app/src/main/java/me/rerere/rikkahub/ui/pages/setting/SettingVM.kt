package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.mcp.McpManager

data class TempApiConfig(
    val name: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true,
    val useResponseApi: Boolean = false,
    val vertexAI: Boolean = false,
    val location: String = "",
    val projectId: String = ""
)

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings(providers = emptyList()))

    private val _tempConfigs = MutableStateFlow<Map<ProviderType, TempApiConfig>>(emptyMap())
    val tempConfigs: StateFlow<Map<ProviderType, TempApiConfig>> = _tempConfigs

    fun initTempConfigs(providerTypes: List<ProviderType>) {
        val initialConfigs = providerTypes.associateWith { providerType ->
            val provider = settings.value.providers.find { it.type == providerType }
            TempApiConfig(
                name = provider?.name ?: providerType.name,
                apiKey = provider?.apiKey ?: "",
                baseUrl = provider?.baseUrl ?: "",
                enabled = provider?.enabled ?: true,
                useResponseApi = (provider as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.useResponseApi ?: false,
                vertexAI = (provider as? me.rerere.ai.provider.ProviderSetting.Google)?.vertexAI ?: false,
                location = (provider as? me.rerere.ai.provider.ProviderSetting.Google)?.location ?: "",
                projectId = (provider as? me.rerere.ai.provider.ProviderSetting.Google)?.projectId ?: ""
            )
        }
        _tempConfigs.value = initialConfigs
    }

    fun updateTempConfig(providerType: ProviderType, config: TempApiConfig) {
        _tempConfigs.update { it + (providerType to config) }
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }
}
