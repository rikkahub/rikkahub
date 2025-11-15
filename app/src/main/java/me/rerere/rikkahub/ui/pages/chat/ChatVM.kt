package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 搜索关键词
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // 聊天列表 (使用 Paging 分页加载)
    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(
            settings.map { it.assistantId }.distinctUntilChanged(),
            _searchQuery
        ) { assistantId, query -> assistantId to query }
            .flatMapLatest { (assistantId, query) ->
                // 根据搜索关键词决定使用哪个数据源
                if (query.isBlank()) {
                    conversationRepo.getConversationsOfAssistantPaging(assistantId)
                } else {
                    conversationRepo.searchConversationsOfAssistantPaging(assistantId, query)
                }
            }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators { before, after ->
                        when {
                            // 列表开头：检查第一项是否置顶
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            // 中间项：检查置顶状态变化和日期变化
                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                // 从置顶切换到非置顶，显示日期头部
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                                // 对于非置顶项，检查日期变化
                                else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    // 更新搜索关键词
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误流 (从ChatService获取)
    val errorFlow: SharedFlow<Throwable> = chatService.errorFlow

    // 生成完成 (从ChatService获取)
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器 (从ChatService获取)
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            context.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedContent = if (assistant != null) {
            content.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(
                            text = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = false
                            )
                        )
                    }

                    else -> part
                }
            }
        } else {
            content
        }

        chatService.sendMessage(_conversationId, processedContent, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedParts = if (assistant != null) {
            parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(
                            text = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = false
                            )
                        )
                    }

                    else -> part
                }
            }
        } else {
            parts
        }

        val newConversation = conversation.value.copy(
            messageNodes = conversation.value.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node // 如果这个node没有这个消息，则不修改
                }
                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                    ), selectIndex = node.messages.size
                )
            },
        )
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {
            val lastTruncateIndex = conversation.value.messageNodes.lastIndex + 1
            // 如果截断在最后一个索引，则取消截断，否则更新 truncateIndex 到最后一个截断位置
            val newConversation = conversation.value.copy(
                truncateIndex = if (conversation.value.truncateIndex == lastTruncateIndex) -1 else lastTruncateIndex,
                title = "",
                chatSuggestions = emptyList(), // 清空建议
            )
            chatService.saveConversation(conversationId = _conversationId, conversation = newConversation)
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        val node = conversation.value.getMessageNodeByMessage(message)
        val nodes = conversation.value.messageNodes.subList(
            0, conversation.value.messageNodes.indexOf(node) + 1
        ).map { messageNode ->
            messageNode.copy(
                messages = messageNode.messages.map { msg ->
                    msg.copy(
                        parts = msg.parts.map { part ->
                            when (part) {
                                is UIMessagePart.Image -> {
                                    val url = part.url
                                    if (url.startsWith("file:")) {
                                        val copied = context.createChatFilesByContents(
                                            listOf(url.toUri())
                                        ).firstOrNull()
                                        if (copied != null) part.copy(url = copied.toString()) else part
                                    } else part
                                }

                                is UIMessagePart.Document -> {
                                    val url = part.url
                                    if (url.startsWith("file:")) {
                                        val copied = context.createChatFilesByContents(
                                            listOf(url.toUri())
                                        ).firstOrNull()
                                        if (copied != null) part.copy(url = copied.toString()) else part
                                    } else part
                                }

                                is UIMessagePart.Video -> {
                                    val url = part.url
                                    if (url.startsWith("file:")) {
                                        val copied = context.createChatFilesByContents(
                                            listOf(url.toUri())
                                        ).firstOrNull()
                                        if (copied != null) part.copy(url = copied.toString()) else part
                                    } else part
                                }

                                is UIMessagePart.Audio -> {
                                    val url = part.url
                                    if (url.startsWith("file:")) {
                                        val copied = context.createChatFilesByContents(
                                            listOf(url.toUri())
                                        ).firstOrNull()
                                        if (copied != null) part.copy(url = copied.toString()) else part
                                    } else part
                                }

                                else -> part
                            }
                        }
                    )
                }
            )
        }
        val newConversation = Conversation(
            id = Uuid.random(),
            assistantId = settings.value.assistantId,
            messageNodes = nodes
        )
        chatService.saveConversation(newConversation.id, newConversation)
        return newConversation
    }

    fun deleteMessage(message: UIMessage) {
        val relatedMessages = collectRelatedMessages(message)
        deleteMessageInternal(message)
        relatedMessages.forEach { deleteMessageInternal(it) }
        saveConversationAsync()
    }

    private fun deleteMessageInternal(message: UIMessage) {
        val conversation = conversation.value
        val node = conversation.getMessageNodeByMessage(message) ?: return
        val nodeIndex = conversation.messageNodes.indexOf(node)
        if (nodeIndex == -1) return
        val newConversation = if (node.messages.size == 1) {
            conversation.copy(
                messageNodes = conversation.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = conversation.messageNodes.mapNotNull { node ->
                val newMessages = node.messages.filter { it.id != message.id }
                if (newMessages.isEmpty()) {
                    null
                } else {
                    val newSelectIndex = if (node.selectIndex >= newMessages.size) {
                        newMessages.lastIndex
                    } else {
                        node.selectIndex
                    }
                    node.copy(
                        messages = newMessages,
                        selectIndex = newSelectIndex
                    )
                }
            }
            conversation.copy(messageNodes = updatedNodes)
        }
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    private fun collectRelatedMessages(message: UIMessage): List<UIMessage> {
        val currentMessages = conversation.value.currentMessages
        val index = currentMessages.indexOf(message)
        if (index == -1) return emptyList()

        val relatedMessages = hashSetOf<UIMessage>()
        for (i in index - 1 downTo 0) {
            if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) {
                relatedMessages.add(currentMessages[i])
            } else {
                break
            }
        }
        for (i in index + 1 until currentMessages.size) {
            if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) {
                relatedMessages.add(currentMessages[i])
            } else {
                break
            }
        }
        return relatedMessages.toList()
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            conversationRepo.deleteConversation(conversationFull)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
