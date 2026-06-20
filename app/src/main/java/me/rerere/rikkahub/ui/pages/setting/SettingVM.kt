package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    // Expose the already-hot, real-valued settings flow directly. Re-wrapping it
    // in a per-VM stateIn with a dummy initial caused a dummy->real recomposition
    // on every screen entry (the screen composed once with dummy settings, then
    // again with real ones) — wasted work right inside the nav transition.
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * Atomic read-modify-write: [transform] runs against the CURRENT persisted settings, not a
     * captured snapshot, so rapid independent edits (e.g. fast model toggles) can't clobber each
     * other's writes. Prefer this over the snapshot overload for per-item mutations.
     */
    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }
}
