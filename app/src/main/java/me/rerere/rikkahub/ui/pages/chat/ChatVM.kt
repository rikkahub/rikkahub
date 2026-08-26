package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatCommandException
import me.rerere.rikkahub.service.ConversationCommands
import me.rerere.rikkahub.service.ConversationQueries
import me.rerere.rikkahub.service.ConversationRuntimeSnapshot
import me.rerere.rikkahub.service.GenerationProgress
import me.rerere.rikkahub.service.GenerationState
import me.rerere.rikkahub.service.NetworkFailureKind
import me.rerere.rikkahub.service.generationIdOrNull
import me.rerere.rikkahub.service.isBusy
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val commands: ConversationCommands,
    private val queries: ConversationQueries,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    val mcpManager: McpManager,
) : ViewModel() {
    private val conversationId = Uuid.parse(id)
    private val initialAssistant = settingsStore.settingsFlow.value.getCurrentAssistant()
    private val runtime = MutableStateFlow(
        ConversationRuntimeSnapshot(
            Conversation.ofId(
                id = conversationId,
                assistantId = initialAssistant.id,
                newConversation = true,
            ).updateCurrentMessages(initialAssistant.presetMessages)
        )
    )

    val conversation: StateFlow<Conversation> = runtime.map { it.conversation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, runtime.value.conversation)
    val generationState: StateFlow<GenerationState> = runtime.map { it.generation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GenerationState.Idle)
    val isGenerating: StateFlow<Boolean> = generationState.map { it.isBusy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val processingStatus: StateFlow<String?> = generationState.map(::progressText)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val conversationJobs = queries.observeActiveConversations()
        .map { snapshots -> snapshots.filterValues { it.generation.isBusy } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val generationDoneFlow: SharedFlow<Uuid> = generationState
        .mapNotNull { (it as? GenerationState.Completed)?.generationId }
        .distinctUntilChanged()
        .map { conversationId }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 0)

    private val errorPresenter = ChatErrorPresenter()
    val errors: StateFlow<List<ChatError>> = errorPresenter.errors

    var chatListInitialized by mutableStateOf(false)
    val inputState = ChatInputState()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())
    val enableWebSearch = settings.map { it.getCurrentAssistant().enableWebSearch }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val currentChatModel = settings.map { it.getCurrentChatModel() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val updateState = settingsStore.settingsFlow
        .map { value ->
            !value.init &&
                value.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled -> if (enabled) updateChecker.updateState else flowOf(UiState.Loading) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    init {
        viewModelScope.launch {
            try {
                runtime.value = commands.ensureConversation(conversationId, initialAssistant.id)
                launch {
                    queries.observeFailures(conversationId).collect(errorPresenter::present)
                }
                queries.observeConversation(conversationId).collect { runtime.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                present(error)
            }
        }
        context.writeStringPreference("lastConversationId", conversationId.toString())
    }

    fun dismissError(id: Uuid) = errorPresenter.dismiss(id)
    fun clearAllErrors() = errorPresenter.clear()

    fun updateSettings(newSettings: Settings): Job = viewModelScope.launch {
        val oldSettings = settings.value
        checkUserAvatarDelete(oldSettings, newSettings)
        settingsStore.update(newSettings)
    }

    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar
        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { value ->
                value.copy(assistants = value.assistants.map {
                    if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                })
            }
        }
    }

    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage() || generationState.value.isBusy) return
        analytics.logEvent("ai_send_message", null)
        launchCommand(context.getString(R.string.error_title_send_message)) {
            commands.send(conversationId, conversation.value.assistantId, content, answer)
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage() || generationState.value.isBusy) return
        analytics.logEvent("ai_edit_message", null)
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.editMessage(conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Job = launchCommand(context.getString(R.string.error_title_compress_conversation)) {
        commands.compress(conversationId, additionalPrompt, targetTokens, keepRecentMessages)
    }

    suspend fun forkMessage(message: UIMessage): Conversation =
        commands.forkConversation(conversationId, message.id)

    fun deleteMessage(message: UIMessage) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.deleteMessage(conversationId, message.id)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        errorPresenter.present(
            IllegalStateException("请先停止生成再删除消息"),
            conversationId,
            context.getString(R.string.error_title_operation),
        )
    }

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true) {
        if (generationState.value.isBusy) return
        analytics.logEvent("ai_regenerate_at_message", null)
        launchCommand(context.getString(R.string.error_title_regenerate_message)) {
            commands.regenerate(conversationId, message.id, regenerateAssistantMsg)
        }
    }

    fun handleToolApproval(toolCallId: String, approved: Boolean, reason: String = "") {
        analytics.logEvent("ai_tool_approval", null)
        approveTool(toolCallId, approved, reason, null)
    }

    fun handleToolAnswer(toolCallId: String, answer: String) {
        analytics.logEvent("ai_tool_answer", null)
        approveTool(toolCallId, true, "", answer)
    }

    private fun approveTool(toolCallId: String, approved: Boolean, reason: String, answer: String?) {
        val generationId = (generationState.value as? GenerationState.AwaitingApproval)?.generationId
            ?: return
        launchCommand(context.getString(R.string.error_title_tool_approval)) {
            commands.approveTool(
                conversationId,
                generationId,
                toolCallId,
                approved,
                reason,
                answer,
            )
        }
    }

    fun stopGeneration() {
        val generationId = generationState.value.generationIdOrNull ?: return
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.stop(conversationId, generationId)
        }
    }

    fun saveConversationAsync() {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.replaceConfiguration(conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.updateTitle(conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.deleteConversation(conversation.id)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.updatePinned(conversation.id, !conversation.isPinned)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.moveToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        launchCommand(context.getString(R.string.error_title_translate_message)) {
            commands.translateMessage(conversationId, message.id, targetLanguage)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        launchCommand(context.getString(R.string.error_title_generate_title)) {
            commands.generateTitle(conversation.id, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.generateSuggestions(conversation.id)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.clearTranslation(conversationId, messageId)
        }
    }

    fun updateConversation(newConversation: Conversation) {
        launchCommand(context.getString(R.string.error_title_operation)) {
            commands.replaceConfiguration(conversationId, newConversation)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node,
                    )
                )
            }
            commands.updateFavorite(conversationId, node.id, !currentlyFavorited)
        }
    }

    private fun launchCommand(title: String? = null, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                present(error, title)
            }
        }

    private fun present(error: Throwable, title: String? = null) {
        val commandError = error as? ChatCommandException
        if (commandError != null) {
            errorPresenter.present(commandError.failure, title)
        } else {
            errorPresenter.present(error, conversationId, title)
        }
    }

    private fun progressText(state: GenerationState): String? {
        val progress = (state as? GenerationState.Running)?.progress ?: return null
        return when (progress) {
            GenerationProgress.RecognizingImages ->
                context.getString(R.string.chat_generation_recognizing_images)
            is GenerationProgress.NetworkRetry -> {
                val reason = context.getString(
                    when (progress.kind) {
                        NetworkFailureKind.UnknownHost -> R.string.chat_generation_network_unknown_host
                        NetworkFailureKind.Timeout -> R.string.chat_generation_network_timeout
                        NetworkFailureKind.Unreachable -> R.string.chat_generation_network_unreachable
                        NetworkFailureKind.Disconnected -> R.string.chat_generation_network_disconnected
                    }
                )
                context.getString(
                    R.string.chat_generation_network_retrying,
                    reason,
                    progress.attempt,
                    progress.maxAttempts,
                )
            }
        }
    }
}
