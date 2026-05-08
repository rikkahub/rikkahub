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
import me.rerere.ai.provider.ModelAbility
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

/**
 * Drives the LiteRT / llama.cpp provider configure pane inside the standard
 * SettingProviderDetailPage pipeline (tab 0).
 *
 * State is split into individual flows so ProviderConfigureLiteRT can observe
 * exactly what it needs:
 *  - [downloadProgress]: non-null while a download is running
 *  - [errorMessage]: non-null when the last action failed
 *  - [accelerator]: the cached accelerator string (null = never probed)
 */
class SettingLocalLlmViewModel(
    val runtime: LocalRuntime,
    private val context: Context,
    private val prefs: LocalRuntimePreferences,
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class Progress(val percent: Int, val bytesRead: Long, val totalBytes: Long?)

    private val _downloadProgress = MutableStateFlow<Progress?>(null)
    val downloadProgress: StateFlow<Progress?> = _downloadProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _accelerator = MutableStateFlow<String?>(null)
    val accelerator: StateFlow<String?> = _accelerator.asStateFlow()

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
     * The default model URL per runtime.
     *
     * LiteRT default: paulsp94/Qwen3.5-2B-LiteRT-LM — Qwen3.5 2B Q4 (~1.4 GB on disk),
     * public and ungated (Apache-2.0). Extension .litertlm (LiteRT-LM format).
     *
     * llama.cpp default: Qwen 2.5 1.5B Instruct GGUF Q4_K_M.
     */
    private val defaultModelUrl: String
        get() = when (runtime) {
            LocalRuntime.LiteRT ->
                "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b_q4.litertlm"
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

        // Scan for stale HTML files or files with invalid magic bytes masquerading as model
        // binaries. HTML files land when a previous download received an HTML error page
        // (e.g. HF viewer URL). All-zero prefixes land from the old sparse-fill resume bug.
        val brokenFiles = installed.entries.filter { (fileName, path) ->
            runCatching {
                val f = java.io.File(path)
                if (!f.exists()) return@runCatching false
                f.inputStream().use { stream ->
                    val buf = ByteArray(64)
                    val n = stream.read(buf)
                    if (n <= 0) return@runCatching false
                    val sample = String(buf, 0, n, Charsets.UTF_8).trimStart().lowercase()
                    val isHtml = sample.startsWith("<!doctype") ||
                        sample.startsWith("<html") ||
                        sample.startsWith("<head") ||
                        sample.startsWith("<?xml")
                    if (isHtml) return@runCatching true
                    // Magic-byte check: file must look like a valid model for its extension.
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    !ModelInstall.isValidMagicForExtension(ext, buf.copyOf(n))
                }
            }.getOrDefault(false)
        }
        if (brokenFiles.isNotEmpty()) {
            for ((fileName, path) in brokenFiles) {
                runCatching { java.io.File(path).delete() }
                prefs.removeInstalledModel(runtime, fileName)
                updateMyProvider { p ->
                    val modelToRemove = p.models.firstOrNull { it.modelId == fileName }
                    if (modelToRemove != null) p.delModel(modelToRemove) else p
                }
            }
            _errorMessage.value =
                "Removed ${brokenFiles.size} broken model file(s) (HTML response or invalid magic bytes). Re-download to retry."
            return
        }

        // Re-read installed after potential removals above (brokenFiles was empty here, so safe).
        val finalInstalled = if (brokenFiles.isNotEmpty()) prefs.installedModels(runtime) else installed

        // Reconcile: any disk-side model that isn't in provider.models gets added.
        // Backfills downloads that landed before the persistence fix at commit 75ea6443.
        val targetId = when (runtime) {
            LocalRuntime.LiteRT -> LITERT_PROVIDER_ID
            LocalRuntime.LlamaCpp -> LLAMACPP_PROVIDER_ID
        }
        val currentProvider = settingsStore.settingsFlow.value.providers.firstOrNull { it.id == targetId }
        if (currentProvider != null) {
            val knownModelIds = currentProvider.models.map { it.modelId }.toSet()
            val missing = finalInstalled.keys.filter { it !in knownModelIds }
            for (fileName in missing) {
                val model = Model(
                    modelId = fileName,
                    displayName = fileName,
                    abilities = listOf(ModelAbility.TOOL),
                )
                updateMyProvider { provider -> provider.addModel(model) }
            }
        }

        // Restore cached accelerator so the UI can display it without re-probing.
        _accelerator.value = prefs.acceleratorFlow(runtime).first()
    }

    private suspend fun probeAndCache(): String {
        val accel = when (runtime) {
            LocalRuntime.LiteRT -> AcceleratorProbe.probeLiteRt(context)
            LocalRuntime.LlamaCpp -> AcceleratorProbe.probeLlamaCpp(
                context,
                jniReportsVulkan = false,
            )
        }
        prefs.setAccelerator(runtime, accel)
        _accelerator.value = accel
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

    fun reDetectAccelerator() {
        viewModelScope.launch {
            prefs.clearAccelerator(runtime)
            _accelerator.value = null
            probeAndCache()
        }
    }

    fun startDefaultDownload() {
        if (runtime == LocalRuntime.LlamaCpp) {
            _errorMessage.value = "llama.cpp runtime is not yet implemented."
            return
        }
        _errorMessage.value = null
        viewModelScope.launch {
            val url = defaultModelUrl
            val fileName = ModelInstall.extractFileNameFromUrl(url)
            val baseDir = ModelInstall.localModelsDir(context)
            val target = ModelInstall.targetFile(baseDir, runtime, fileName)

            val mem = MemoryGuard.canLoad(context, modelFileBytes = estimatedSize(runtime))
            if (mem is MemoryGuard.Decision.TooLarge) {
                _errorMessage.value =
                    "Model needs ${mem.modelFileBytes / 1_000_000} MB; only ${mem.availMemBytes / 1_000_000} MB available."
                return@launch
            }

            ModelInstall.download(httpClient, url, target).collect { p ->
                when (p) {
                    is ModelInstall.Progress.Started ->
                        _downloadProgress.value = Progress(0, 0L, p.totalBytes)
                    is ModelInstall.Progress.Tick -> {
                        val total = p.totalBytes
                        val pct = if (total != null && total > 0)
                            ((p.bytesRead * 100) / total).toInt() else 0
                        _downloadProgress.value = Progress(pct, p.bytesRead, total)
                    }
                    is ModelInstall.Progress.Done -> {
                        _downloadProgress.value = null
                        prefs.addInstalledModel(runtime, fileName, p.file.absolutePath)
                        val model = Model(
                            modelId = fileName,
                            displayName = fileName,
                            abilities = listOf(ModelAbility.TOOL),
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
                        _downloadProgress.value = null
                        _errorMessage.value = p.cause.message.orEmpty()
                    }
                }
            }
        }
    }

    fun startManualDownload(url: String) {
        if (runtime == LocalRuntime.LlamaCpp) {
            _errorMessage.value = "llama.cpp runtime is not yet implemented."
            return
        }
        // Normalise HuggingFace blob URLs → resolve URLs before validation and download.
        // A user pasting the HF viewer URL (/blob/main/<file>) gets a 200 OK that returns
        // HTML, not the model binary. Normalising first makes both URL forms work.
        val normalizedUrl = ModelInstall.normalizeHuggingFaceUrl(url)
        if (!ModelInstall.isValidDownloadUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL: must be https and well-formed"
            return
        }
        _errorMessage.value = null
        viewModelScope.launch {
            val fileName = ModelInstall.extractFileNameFromUrl(normalizedUrl)
            val baseDir = ModelInstall.localModelsDir(context)
            val target = ModelInstall.targetFile(baseDir, runtime, fileName)
            ModelInstall.download(httpClient, normalizedUrl, target).collect { p ->
                when (p) {
                    is ModelInstall.Progress.Started ->
                        _downloadProgress.value = Progress(0, 0L, p.totalBytes)
                    is ModelInstall.Progress.Tick -> {
                        val total = p.totalBytes
                        val pct = if (total != null && total > 0)
                            ((p.bytesRead * 100) / total).toInt() else 0
                        _downloadProgress.value = Progress(pct, p.bytesRead, total)
                    }
                    is ModelInstall.Progress.Done -> {
                        _downloadProgress.value = null
                        prefs.addInstalledModel(runtime, fileName, p.file.absolutePath)
                        val model = Model(
                            modelId = fileName,
                            displayName = fileName,
                            abilities = listOf(ModelAbility.TOOL),
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
                        _downloadProgress.value = null
                        _errorMessage.value = p.cause.message.orEmpty()
                    }
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun estimatedSize(rt: LocalRuntime): Long = when (rt) {
        LocalRuntime.LiteRT -> 1_500_000_000L   // Qwen3.5-2B Q4.litertlm ~1.4 GB + 100 MB safety pad
        LocalRuntime.LlamaCpp -> 1_000_000_000L // Qwen 2.5 1.5B Q4 ~1 GB
    }
}
