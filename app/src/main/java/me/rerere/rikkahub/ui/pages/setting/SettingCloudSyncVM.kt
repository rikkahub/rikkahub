package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.cloud.CloudSyncOutcome
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import me.rerere.rikkahub.data.sync.cloud.CloudSyncWorker
import me.rerere.rikkahub.data.sync.cloud.ConnectionProbeResult
import me.rerere.rikkahub.data.sync.cloud.PerryServerConfig
import me.rerere.rikkahub.data.sync.cloud.SyncMode

class SettingCloudSyncVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val cloudSyncRepository: CloudSyncRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val syncState = cloudSyncRepository.observeState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val outboxCount = cloudSyncRepository.observeOutboxCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val connectionStatus = cloudSyncRepository.connectionStatus

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    fun updateConfig(transform: (PerryServerConfig) -> PerryServerConfig) {
        viewModelScope.launch {
            // awaitReady inside update(): first open used Settings.dummy() and dropped host/token.
            settingsStore.update { it.copy(perryConfig = transform(it.perryConfig)) }
        }
    }

    fun registerDevice() {
        viewModelScope.launch {
            _busy.value = true
            try {
                // Flush any in-flight TextField updates before reading bootstrap token.
                kotlinx.coroutines.delay(50)
                settingsStore.awaitReady()
                val result = cloudSyncRepository.registerThisDevice()
                _statusText.value = result.fold(
                    onSuccess = { "Registered device ${it.deviceId}" },
                    onFailure = { it.message ?: "Register failed" },
                )
                if (result.isSuccess) {
                    cloudSyncRepository.testConnection()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun setSyncMode(mode: SyncMode) {
        viewModelScope.launch {
            cloudSyncRepository.setSyncMode(mode)
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _busy.value = true
            try {
                settingsStore.awaitReady()
                val result = cloudSyncRepository.testConnection()
                _statusText.value = formatProbe(result)
            } finally {
                _busy.value = false
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val outcome = cloudSyncRepository.runSyncCycle()) {
                    CloudSyncOutcome.Success -> _statusText.value = "Sync completed"
                    CloudSyncOutcome.Skipped -> {
                        CloudSyncWorker.enqueue(context)
                        _statusText.value = "Sync skipped or enqueued"
                    }
                    is CloudSyncOutcome.Retryable -> {
                        CloudSyncWorker.enqueue(context)
                        _statusText.value =
                            "Temporary sync issue (will retry): ${outcome.message}"
                    }
                    is CloudSyncOutcome.Failed -> _statusText.value = "Failed: ${outcome.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun importMonelProviders() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = cloudSyncRepository.importMonelProviders()
                _statusText.value = result.fold(
                    onSuccess = {
                        "Imported $it Monel provider shell(s). Open Settings → Providers → Models to add models for chat."
                    },
                    onFailure = { it.message ?: "Import Monel providers failed" },
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearCredentials() {
        viewModelScope.launch {
            cloudSyncRepository.clearDeviceCredentials()
            _statusText.value = "Device credentials cleared"
        }
    }

    fun resetCursor() {
        viewModelScope.launch {
            cloudSyncRepository.resetCursor()
            _statusText.value = "Sync cursor reset"
        }
    }

    private fun formatProbe(result: ConnectionProbeResult): String {
        val latency = result.latencyMs?.let { " (${it}ms)" }.orEmpty()
        val extra = result.serverInfo?.let {
            " api=${it.apiVersion} minClient=${it.minClientVersion}"
        }.orEmpty()
        return "${result.status}: ${result.message}$latency$extra"
    }
}
