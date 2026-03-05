package me.rerere.rikkahub.ui.activity

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TextSelectionAction

private const val TAG = "TextSelectionVM"

sealed interface TextSelectionState {
    data object ActionSelection : TextSelectionState
    data object CustomPrompt : TextSelectionState
    data object Loading : TextSelectionState
    data class Result(
        val responseText: String,
        val isStreaming: Boolean = true,
        val isReasoning: Boolean = false,
    ) : TextSelectionState

    data class Error(val message: String) : TextSelectionState
}

class TextSelectionVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Settings.dummy())

    var selectedText by mutableStateOf("")
        private set

    var state by mutableStateOf<TextSelectionState>(TextSelectionState.ActionSelection)
        private set

    var customPrompt by mutableStateOf("")
        private set

    var lastActionId by mutableStateOf<String?>(null)
        private set

    var lastUserPrompt by mutableStateOf<String?>(null)
        private set

    private var selectedCustomAction: TextSelectionAction? = null
    private var currentJob: Job? = null
    private var messages = mutableListOf<UIMessage>()

    fun updateSelectedText(text: String) {
        selectedText = text
    }

    fun onActionSelected(action: TextSelectionAction) {
        if (action.isCustomPrompt) {
            selectedCustomAction = action
            customPrompt = ""
            state = TextSelectionState.CustomPrompt
            return
        }
        executeAction(action, customPromptText = "")
    }

    fun updateCustomPrompt(prompt: String) {
        customPrompt = prompt
    }

    fun submitCustomPrompt() {
        val action = selectedCustomAction ?: return
        if (customPrompt.isBlank()) return
        executeAction(action, customPromptText = customPrompt)
    }

    fun backToActionSelection() {
        currentJob?.cancel()
        state = TextSelectionState.ActionSelection
        customPrompt = ""
        selectedCustomAction = null
        messages.clear()
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        val currentState = state
        if (currentState is TextSelectionState.Result) {
            state = currentState.copy(isStreaming = false)
        }
    }

    fun isTranslateAction(): Boolean = lastActionId == "translate"

    private fun executeAction(action: TextSelectionAction, customPromptText: String) {
        currentJob?.cancel()
        state = TextSelectionState.Loading
        messages.clear()
        lastActionId = action.id
        lastUserPrompt = customPromptText.takeIf { it.isNotBlank() }

        currentJob = viewModelScope.launch {
            try {
                val currentSettings = settingsStore.settingsFlow.value
                val assistant = resolveAssistant(currentSettings)
                val model = resolveModel(currentSettings, assistant, action)
                val providerSetting = model?.findProvider(currentSettings.providers)

                if (model == null || providerSetting == null) {
                    state = TextSelectionState.Error(
                        if (model == null) {
                            "No chat model selected. Please configure a model in Settings."
                        } else {
                            "Provider not found for selected model."
                        }
                    )
                    return@launch
                }

                val rawPrompt = buildSystemPrompt(
                    action = action,
                    settings = currentSettings,
                    assistantPrompt = assistant.systemPrompt,
                    customPromptText = customPromptText,
                )
                val prompt = runCatching {
                    renderSystemPromptPlaceholders(
                        prompt = rawPrompt,
                        settings = currentSettings,
                        model = model,
                        assistant = assistant
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to render text selection prompt placeholders", error)
                    rawPrompt
                }

                messages.add(UIMessage.system(prompt))
                messages.add(UIMessage.user(selectedText))

                val provider = providerManager.getProviderByType(providerSetting)
                provider.streamText(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = assistant.temperature ?: 0.7f,
                    ),
                ).catch { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Stream error", error)
                    state = TextSelectionState.Error(error.message ?: "Unknown error")
                }.collect { chunk ->
                    handleChunk(chunk, model)
                }

                val currentState = state
                if (currentState is TextSelectionState.Result) {
                    state = currentState.copy(isStreaming = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to execute text selection action", error)
                state = TextSelectionState.Error(error.message ?: "Unknown error")
            }
        }
    }

    private fun resolveAssistant(settings: Settings) =
        settings.textSelectionConfig.assistantId?.let { settings.getAssistantById(it) }
            ?: settings.getCurrentAssistant()

    private fun resolveModel(settings: Settings, assistant: Assistant, action: TextSelectionAction): Model? {
        // Priority: action-level model > assistant model > global chat model
        action.modelId?.let { actionModelId ->
            settings.findModelById(actionModelId)?.let { return it }
        }
        val modelId = assistant.chatModelId ?: settings.chatModelId
        return settings.findModelById(modelId)
    }

    private suspend fun renderSystemPromptPlaceholders(
        prompt: String,
        settings: Settings,
        model: Model,
        assistant: Assistant,
    ): String {
        val transformed = PlaceholderTransformer.transform(
            ctx = TransformerContext(
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            ),
            messages = listOf(UIMessage.system(prompt))
        )
        return transformed.firstOrNull()?.toText().orEmpty()
    }

    private fun handleChunk(chunk: MessageChunk, model: Model) {
        messages = messages.handleMessageChunk(chunk, model).toMutableList()
        val lastMessage = messages.lastOrNull()
        val responseText = lastMessage?.toText().orEmpty()
        val isReasoning = lastMessage?.parts?.any {
            it is UIMessagePart.Reasoning && it.finishedAt == null
        } == true

        state = TextSelectionState.Result(
            responseText = responseText,
            isStreaming = true,
            isReasoning = isReasoning,
        )
    }

    private fun buildSystemPrompt(
        action: TextSelectionAction,
        settings: Settings,
        assistantPrompt: String,
        customPromptText: String,
    ): String {
        val actionPrompt = action.prompt
            .replace("{{language}}", settings.textSelectionConfig.translateLanguage)
            .replace("{{custom_prompt}}", customPromptText)

        if (action.id == "translate") {
            return actionPrompt
        }
        return if (assistantPrompt.isNotBlank()) {
            "$assistantPrompt\n\n$actionPrompt"
        } else {
            actionPrompt
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}
