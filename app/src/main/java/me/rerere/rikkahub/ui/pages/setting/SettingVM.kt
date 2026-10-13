package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    // 设置在应用启动时就读进了内存，初始值直接取当前值；
    // 用占位值的话，每次进设置页第一帧都是默认设置，随后才跳成真实数据
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = settingsStore.settingsFlow.value.takeUnless { it.init }
                ?: Settings(init = true, providers = emptyList()),
        )

    val launchCount: StateFlow<Int> = settingsStore.launchCountFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }
}
