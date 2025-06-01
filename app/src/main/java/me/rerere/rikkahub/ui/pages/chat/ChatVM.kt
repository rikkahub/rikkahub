package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.transformers.MessageTimeTransformer
import me.rerere.ai.ui.transformers.PlaceholderTransformer
import me.rerere.ai.ui.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.mcp.McpManager
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.hooks.getCurrentAssistant
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.search.SearchService
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
    )
}

class ChatVM(
    savedStateHandle: SavedStateHandle,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    val mcpManager: McpManager,
    val updateChecker: UpdateChecker,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(checkNotNull(savedStateHandle["id"]))
    private val _conversation = MutableStateFlow(Conversation.ofId(_conversationId))
    val conversation: StateFlow<Conversation>
        get() = _conversation
    var useWebSearch by mutableStateOf(false)

    // 异步任务
    val conversationJob = MutableStateFlow<Job?>(null)

    init {
        // Load the conversation from the repository (database)
        viewModelScope.launch {
            val conversation = conversationRepo.getConversationById(_conversationId)
            if (conversation != null) {
                this@ChatVM._conversation.value = conversation

                // 更新当前助手到 conversation 所属的 assistant
                // 这里不能用 updateSettings，因为 settings 可能还没加载
                settingsStore.updateAssistant(conversation.assistantId)
            }
        }
    }

    // 用户设置
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    // 聊天列表
    val conversations = settings
        .map { it.assistantId }
        .distinctUntilChanged()
        .flatMapLatest { assistantId ->
            conversationRepo.getConversationsOfAssistant(assistantId)
                .catch {
                    Log.e(TAG, "conversationRepo.getAllConversations: ", it)
                    errorFlow.emit(it)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 当前模型
    val currentChatModel = settings
        .map {
            it.providers.findModelById(it.chatModelId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 生成完成
    val generationDoneFlow = MutableSharedFlow<Uuid>()

    // 更新设置
    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            settingsStore.update(
                settings.value.copy(
                    chatModelId = model.id
                )
            )
        }
    }

    // Update checker
    val updateState = updateChecker.checkUpdate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    // Search Tool
    private val searchTool = Tool(
        name = "search_web",
        description = "search web for information",
        parameters = InputSchema.Obj(
            buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        ),
        execute = {
            val query = it.jsonObject["query"]!!.jsonPrimitive.content
            val service = SearchService.getService(settings.value.searchServiceOptions)
            val result = service.search(
                query = query,
                commonOptions = settings.value.searchCommonOptions,
                serviceOptions = settings.value.searchServiceOptions,
            )
            val results = result.getOrThrow()
            JsonInstant.encodeToJsonElement(results)
        }
    )

    fun handleMessageSend(content: List<UIMessagePart>) {
        if (content.isEmptyInputMessage()) return

        this.conversationJob.value?.cancel()
        val job = viewModelScope.launch {
            // 添加消息到列表
            val newConversation = conversation.value.copy(
                messages = conversation.value.messages + UIMessage(
                    role = MessageRole.USER,
                    parts = content,
                ),
            )
            saveConversation(newConversation)

            // 开始补全
            handleMessageComplete()

            generationDoneFlow.emit(Uuid.random())
        }
        this.conversationJob.value = job
        job.invokeOnCompletion {
            this.conversationJob.value = null
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, uuid: Uuid?) {
        if (parts.isEmptyInputMessage()) return
        val newConversation = conversation.value.copy(
            messages = conversation.value.messages.map {
                if (it.id == uuid) {
                    it.copy(
                        parts = parts,
                    )
                } else {
                    it
                }
            },
        )
        this.updateConversation(newConversation)
        val message = newConversation.messages.find { it.id == uuid } ?: return
        this.regenerateAtMessage(message, false)
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {
            val lastTruncateIndex = conversation.value.messages.lastIndex + 1
            // 如果截断在最后一个索引，则取消截断，否则更新 truncateIndex 到最后一个截断位置
            val newConversation = conversation.value.copy(
                truncateIndex = if (conversation.value.truncateIndex == lastTruncateIndex) -1 else lastTruncateIndex,
            )
            saveConversation(newConversation)
        }
    }

    private suspend fun handleMessageComplete() {
        val model = settings.value.providers.findModelById(settings.value.chatModelId) ?: return
        runCatching {
//            ChatService.startGeneration(
//                context = context,
//                settings = settings.value,
//                model = model,
//                assistant = settings.value.getCurrentAssistant(),
//                conversation = conversation.value
//            )
            generationHandler.generateText(
                settings = settings.value,
                model = model,
                messages = conversation.value.messages,
                assistant = settings.value.getCurrentAssistant(),
                memories = { memoryRepository.getMemoriesOfAssistant(settings.value.assistantId.toString()) },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    if (settings.value.getCurrentAssistant().enableMessageTime) {
                        add(MessageTimeTransformer)
                    }
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useWebSearch) {
                        add(searchTool)
                    }
                    mcpManager.getAllAvailableTools().forEach { tool ->
                        add(
                            Tool(
                                name = tool.name,
                                description = tool.description ?: "",
                                parameters = tool.inputSchema,
                                execute = {
                                    mcpManager.callTool(tool.name, it.jsonObject)
                                }
                            ))
                    }
                },
                truncateIndex = conversation.value.truncateIndex,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                updateConversation(
                    conversation = conversation.value.copy(
                        messages = conversation.value.messages.map { message ->
                            message.finishReasoning() // 结束思考
                        }
                    ))
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        updateConversation(conversation.value.copy(messages = chunk.messages))
                    }

                    is GenerationChunk.TokenUsage -> {
                        updateConversation(conversation.value.copy(tokenUsage = chunk.usage))
                        Log.i(TAG, "handleMessageComplete: usage = ${chunk.usage}")
                    }
                }
            }
        }.onFailure {
            it.printStackTrace()
            errorFlow.emit(it)
        }.onSuccess {
            saveConversation(conversation.value)
            generateTitle(conversation.value)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        if (conversation.title.isNotBlank() && !force) return

        val model = settings.value.providers.findModelById(settings.value.titleModelId) ?: let {
            // 如果没有标题模型，则使用聊天模型
            settings.value.providers.findModelById(settings.value.chatModelId)
        } ?: return
        val provider = model.findProvider(settings.value.providers) ?: return

        viewModelScope.launch {
            runCatching {
                val providerHandler = ProviderManager.getProviderByType(provider)
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            """
                                你是一名擅长会话的助理，我会给你一些对话内容在content内，你需要将用户的会话总结为 10 个字以内的标题
                                1. 标题语言与用户的首要语言一致
                                2. 不要使用标点符号和其他特殊符号
                                3. 直接回复标题即可
                                4. 使用 ${Locale.getDefault().displayName} 语言总结
                                
                                <content>
                                ${conversation.messages.joinToString("\n\n") { it.summaryAsText() }}
                                </content>
                                """.trimIndent()
                        )
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                    ),
                )
                Log.i(TAG, "generateTitle: ${result.choices[0]}")
                // 生成完，conversation可能不是最新了，因此需要重新获取
                conversationRepo.getConversationById(conversation.id)?.let {
                    saveConversation(
                        it.copy(
                            title = result.choices[0].message?.toText()?.trim() ?: "",
                        )
                    )
                }
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    suspend fun forkMessage(
        message: UIMessage
    ): Conversation {
        val messages =
            conversation.value.messages.subList(0, conversation.value.messages.indexOf(message) + 1)
        val newConversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = settings.value.assistantId,
            messages = messages
        )
        saveConversation(newConversation)
        return newConversation
    }

    fun deleteMessage(
        message: UIMessage
    ) {
        val indexAt = conversation.value.messages.indexOf(message)
        if (indexAt == -1) return
        val newConversation = conversation.value.copy(
            messages = conversation.value.messages.filterIndexed { index, _ -> index != indexAt }
        )
        updateConversation(newConversation)
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        viewModelScope.launch {
            if (message.role == MessageRole.USER) {
                // 如果是用户消息，则截止到当前消息
                val indexAt = conversation.value.messages.indexOf(message)
                val newConversation = conversation.value.copy(
                    messages = conversation.value.messages.subList(0, indexAt + 1)
                )
                saveConversation(newConversation)
            } else {
                if (!regenerateAssistantMsg) {
                    // 如果不需要重新生成助手消息，则直接返回
                    saveConversation(conversation.value)
                    return@launch
                }
                // 如果是助手消息，则需要向上查找第一个用户消息
                var indexAt = conversation.value.messages.indexOf(message)
                for (i in indexAt downTo 0) {
                    if (conversation.value.messages[i].role == MessageRole.USER) {
                        indexAt = i
                        break
                    }
                }
                val newConversation = conversation.value.copy(
                    messages = conversation.value.messages.subList(0, indexAt + 1)
                )
                saveConversation(newConversation)
            }
            conversationJob.value?.cancel()
            val job = viewModelScope.launch {
                handleMessageComplete()
                generationDoneFlow.emit(Uuid.random())
            }
            conversationJob.value = job
            job.invokeOnCompletion {
                conversationJob.value = null
            }
        }
    }

    fun updateConversation(conversation: Conversation) {
        if (conversation.id != this._conversationId) return
        checkFilesDelete(conversation, this._conversation.value)
        this._conversation.value = conversation
    }

    // 变更消息，检查文件删除
    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            context.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversation: Conversation) {
        val conversation = conversation.copy(
            assistantId = settings.value.assistantId,
            updateAt = Instant.now()
        )
        this.updateConversation(conversation)
        try {
            if (conversationRepo.getConversationById(conversation.id) == null) {
                conversationRepo.insertConversation(conversation)
            } else {
                conversationRepo.updateConversation(conversation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            saveConversation(conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            saveConversation(conversation.value.copy(title = title))
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }
    }

    suspend fun addMemory(content: String): AssistantMemory {
        return memoryRepository.addMemory(
            assistantId = settings.value.assistantId.toString(),
            content = content,
        )
    }

    suspend fun updateMemory(id: Int, content: String): AssistantMemory {
        return memoryRepository.updateContent(id, content)
    }

    suspend fun deleteMemory(id: Int) {
        memoryRepository.deleteMemory(id)
    }
}