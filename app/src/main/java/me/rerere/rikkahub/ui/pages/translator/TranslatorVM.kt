package me.rerere.rikkahub.ui.pages.translator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.shouldRethrowVmError
import java.util.Locale

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, settingsStore.settingsFlow.value)

    // 翻译状态
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    // 输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    // 翻译结果
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    // 翻译目标语言
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage: StateFlow<Locale> = _targetLanguage

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 当前任务
    private var currentJob: Job? = null

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun updateTargetLanguage(language: Locale) {
        _targetLanguage.value = language
    }

    fun translate() {
        val inputText = _inputText.value
        if (inputText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        currentJob = viewModelScope.launch {
            runCatching {
                generationHandler.translateText(
                    settings = settings.value,
                    sourceText = inputText,
                    targetLanguage = targetLanguage.value
                ) { translatedText ->
                    // Update translation in real-time
                    _translatedText.value = translatedText
                }.collect { /* Final translation already handled in onStreamUpdate */ }
            }.onFailure {
                // cancelTranslation() cancels currentJob; that CancellationException must propagate so
                // structured-concurrency teardown holds and is never shown to the user as an error.
                if (shouldRethrowVmError(it)) throw it
                Log.e(TAG, "translation failed", it)
                errorFlow.emit(it)
            }

            _translating.value = false
        }
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        _translating.value = false
    }
}
