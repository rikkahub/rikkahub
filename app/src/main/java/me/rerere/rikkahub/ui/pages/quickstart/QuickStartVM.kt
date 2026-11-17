package me.rerere.rikkahub.ui.pages.quickstart

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportResult
import me.rerere.rikkahub.data.sync.importer.DeepSeekMigrator
import me.rerere.rikkahub.utils.UiState

private const val TAG = "QuickStartVM"

class QuickStartVM(
    private val settingsStore: SettingsStore,
    private val deepSeekMigrator: DeepSeekMigrator,
) : ViewModel() {
    // 和 SettingVM 一样直接取内存里的当前值，避免第一帧闪成默认设置
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = settingsStore.settingsFlow.value.takeUnless { it.init }
                ?: Settings(init = true, providers = emptyList()),
        )

    private val _deepSeekImport = MutableStateFlow<UiState<DeepSeekImportResult>>(UiState.Idle)
    val deepSeekImport: StateFlow<UiState<DeepSeekImportResult>> = _deepSeekImport.asStateFlow()

    /**
     * 设置默认聊天模型。快速模型和翻译模型还没有可用的模型时一并设成它，
     * 否则新用户的标题生成、聊天建议和翻译会因为没有模型而用不了。
     */
    fun setDefaultChatModel(model: Model) {
        viewModelScope.launch {
            settingsStore.update { latest ->
                latest.copy(
                    chatModelId = model.id,
                    fastModelId = latest.fastModelId.takeIf { latest.findModelById(it) != null } ?: model.id,
                    translateModeId = latest.translateModeId.takeIf { latest.findModelById(it) != null } ?: model.id,
                )
            }
        }
    }

    fun importDeepSeek(uri: Uri) {
        if (_deepSeekImport.value is UiState.Loading) return
        viewModelScope.launch {
            _deepSeekImport.value = UiState.Loading
            _deepSeekImport.value = runCatching { deepSeekMigrator.import(uri) }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "importDeepSeek failed", it)
                    UiState.Error(it)
                },
            )
        }
    }
}
