package me.rerere.rikkahub.ui.pages.setting.locallm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.LITERT_PROVIDER_ID
import me.rerere.ai.provider.LLAMACPP_PROVIDER_ID
import me.rerere.locallm.AcceleratorProbe
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import me.rerere.locallm.MemoryGuard
import me.rerere.locallm.ModelInstall
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

/**
 * Drives one provider tile (the runtime is supplied per VM instance).
 *
 * State is a sealed class so the tile can render off a single observable:
 *  - Idle (toggled off, no models installed)
 *  - Downloading (showing progress)
 *  - Ready (model installed, accelerator known)
 *  - Error (download failed; user sees retry button)
 */
class SettingLocalLlmViewModel(
    private val runtime: LocalRuntime,
    private val context: Context,
    private val prefs: LocalRuntimePreferences,
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data class Downloading(val percent: Int, val bytesRead: Long, val totalBytes: Long?) : UiState()
        data class Ready(val installedModelName: String, val accelerator: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Whether the provider is currently enabled in persisted settings. */
    val providerEnabled: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { settings ->
            val targetId = when (runtime) {
                LocalRuntime.LiteRT -> LITERT_PROVIDER_ID
                LocalRuntime.LlamaCpp -> LLAMACPP_PROVIDER_ID
            }
            settings.providers.firstOrNull { it.id == targetId }?.enabled ?: false
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The default model URL per runtime. The implementer pins these at build time per
     * the Phase 22A spec; they may shift if upstream HF paths change between releases.
     *
     * LiteRT default: litert-community/Qwen3-0.6B — Qwen3 generation (current as of 2026-05),
     * ~614 MB on disk, public and ungated. File extension is .litertlm (LiteRT-LM format;
     * not compatible with MediaPipe tasks-genai). Previously Gemma3-1B-IT which became
     * auth-gated in the litert-community org.
     *
     * llama.cpp default: Qwen 2.5 1.5B Instruct GGUF Q4_K_M.
     */
    private val defaultModelUrl: String
        get() = when (runtime) {
            LocalRuntime.LiteRT ->
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
            LocalRuntime.LlamaCpp ->
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        }

    init {
        viewModelScope.launch {
            refreshFromDisk()
        }
    }

    private suspend fun refreshFromDisk() {
        val installed = prefs.installedModels(runtime)
        if (installed.isEmpty()) {
            _state.value = UiState.Idle
        } else {
            val first = installed.entries.first()
            val accel = prefs.acceleratorFlow(runtime).first() ?: probeAndCache()
            _state.value = UiState.Ready(first.key, accel)
        }
    }

    private suspend fun probeAndCache(): String {
        val accel = when (runtime) {
            LocalRuntime.LiteRT -> AcceleratorProbe.probeLiteRt(context)
            LocalRuntime.LlamaCpp -> AcceleratorProbe.probeLlamaCpp(
                context,
                jniReportsVulkan = false, // Wired up properly in Task 18 once the JNI binding lands.
            )
        }
        prefs.setAccelerator(runtime, accel)
        return accel
    }

    /**
     * Mutates the LiteRtLocal / LlamaCppLocal ProviderSetting in the persisted settings store.
     * Identity is established by the stable provider ID constant, not by position.
     */
    private suspend fun updateMyProvider(transform: (ProviderSetting) -> ProviderSetting) {
        val targetId = when (runtime) {
            LocalRuntime.LiteRT -> LITERT_PROVIDER_ID
            LocalRuntime.LlamaCpp -> LLAMACPP_PROVIDER_ID
        }
        settingsStore.update { old ->
            old.copy(providers = old.providers.map { p ->
                if (p.id == targetId) transform(p) else p
            })
        }
    }

    fun setProviderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            updateMyProvider { p ->
                when (p) {
                    is ProviderSetting.LiteRtLocal -> p.copy(enabled = enabled)
                    is ProviderSetting.LlamaCppLocal -> p.copy(enabled = enabled)
                    else -> p
                }
            }
        }
    }

    fun startDefaultDownload() {
        viewModelScope.launch {
            val url = defaultModelUrl
            val fileName = ModelInstall.extractFileNameFromUrl(url)
            val baseDir = ModelInstall.localModelsDir(context)
            val target = ModelInstall.targetFile(baseDir, runtime, fileName)

            val mem = MemoryGuard.canLoad(context, modelFileBytes = estimatedSize(runtime))
            if (mem is MemoryGuard.Decision.TooLarge) {
                _state.value = UiState.Error(
                    "Model needs ${mem.modelFileBytes / 1_000_000} MB; only ${mem.availMemBytes / 1_000_000} MB available."
                )
                return@launch
            }

            ModelInstall.download(httpClient, url, target).collect { p ->
                when (p) {
                    is ModelInstall.Progress.Started -> _state.value = UiState.Downloading(0, 0L, p.totalBytes)
                    is ModelInstall.Progress.Tick -> {
                        val total = p.totalBytes
                        val pct = if (total != null && total > 0)
                            ((p.bytesRead * 100) / total).toInt() else 0
                        _state.value = UiState.Downloading(pct, p.bytesRead, total)
                    }
                    is ModelInstall.Progress.Done -> {
                        prefs.addInstalledModel(runtime, fileName, p.file.absolutePath)
                        val model = Model(
                            modelId = fileName,
                            displayName = fileName,
                        )
                        updateMyProvider { provider -> provider.addModel(model) }
                        // Enable the provider automatically after first successful download.
                        updateMyProvider { provider ->
                            when (provider) {
                                is ProviderSetting.LiteRtLocal -> provider.copy(enabled = true)
                                is ProviderSetting.LlamaCppLocal -> provider.copy(enabled = true)
                                else -> provider
                            }
                        }
                        refreshFromDisk()
                    }
                    is ModelInstall.Progress.Failed -> {
                        _state.value = UiState.Error(p.cause.message.orEmpty())
                    }
                }
            }
        }
    }

    fun reDetectAccelerator() {
        viewModelScope.launch {
            prefs.clearAccelerator(runtime)
            refreshFromDisk()
        }
    }

    fun deleteInstalledModel(fileName: String) {
        viewModelScope.launch {
            val installed = prefs.installedModels(runtime)
            installed[fileName]?.let { path -> java.io.File(path).delete() }
            prefs.removeInstalledModel(runtime, fileName)
            updateMyProvider { p ->
                val modelToRemove = p.models.firstOrNull { it.modelId == fileName }
                if (modelToRemove != null) p.delModel(modelToRemove) else p
            }
            refreshFromDisk()
        }
    }

    fun startManualDownload(url: String) {
        if (!ModelInstall.isValidDownloadUrl(url)) {
            _state.value = UiState.Error("Invalid URL: must be https and well-formed")
            return
        }
        viewModelScope.launch {
            val fileName = ModelInstall.extractFileNameFromUrl(url)
            val baseDir = ModelInstall.localModelsDir(context)
            val target = ModelInstall.targetFile(baseDir, runtime, fileName)
            ModelInstall.download(httpClient, url, target).collect { p ->
                when (p) {
                    is ModelInstall.Progress.Started ->
                        _state.value = UiState.Downloading(0, 0L, p.totalBytes)
                    is ModelInstall.Progress.Tick -> {
                        val total = p.totalBytes
                        val pct = if (total != null && total > 0)
                            ((p.bytesRead * 100) / total).toInt() else 0
                        _state.value = UiState.Downloading(pct, p.bytesRead, total)
                    }
                    is ModelInstall.Progress.Done -> {
                        prefs.addInstalledModel(runtime, fileName, p.file.absolutePath)
                        val model = Model(
                            modelId = fileName,
                            displayName = fileName,
                        )
                        updateMyProvider { provider -> provider.addModel(model) }
                        // Enable the provider automatically after first successful download.
                        updateMyProvider { provider ->
                            when (provider) {
                                is ProviderSetting.LiteRtLocal -> provider.copy(enabled = true)
                                is ProviderSetting.LlamaCppLocal -> provider.copy(enabled = true)
                                else -> provider
                            }
                        }
                        refreshFromDisk()
                    }
                    is ModelInstall.Progress.Failed -> {
                        _state.value = UiState.Error(p.cause.message.orEmpty())
                    }
                }
            }
        }
    }

    private fun estimatedSize(rt: LocalRuntime): Long = when (rt) {
        LocalRuntime.LiteRT -> 750_000_000L    // Qwen3-0.6B.litertlm ~614 MB + 136 MB safety pad
        LocalRuntime.LlamaCpp -> 1_000_000_000L // Qwen 2.5 1.5B Q4 ~1 GB
    }
}
