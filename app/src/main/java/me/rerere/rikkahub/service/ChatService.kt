package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.TOOL_APPROVAL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.hasBlockingToolsForContinuation
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxDirectCommandParseResult
import me.rerere.rikkahub.data.ai.tools.termux.TermuxDirectCommandParser
import me.rerere.rikkahub.data.ai.tools.termux.TermuxOutputFormatter
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtyToolResponse
import me.rerere.rikkahub.data.ai.tools.termux.TermuxResult
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.TermuxUserShellCommandCodec
import me.rerere.rikkahub.data.ai.tools.termux.isSuccessful
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexPromptOnlyTransformer
import me.rerere.rikkahub.data.ai.transformers.SillyTavernCompatScriptTransformer
import me.rerere.rikkahub.data.ai.transformers.StMacroState
import me.rerere.rikkahub.data.ai.transformers.SillyTavernPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.SillyTavernMacroTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.readLatestAssistantStRuntimeSnapshot
import me.rerere.rikkahub.data.ai.transformers.readStRuntimeSnapshot
import me.rerere.rikkahub.data.ai.transformers.withStRuntimeSnapshot
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexApplyPhase
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.applyActiveStPresetSampling
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.resolveConversationStarterMessages
import me.rerere.rikkahub.data.model.resolveStSendIfEmptyContent
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

data class ChatError(
    val id: Uuid = Uuid.random(),
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ScheduledTaskExecutionResult(
    val replyPreview: String,
    val replyText: String,
    val modelId: Uuid?,
    val providerName: String,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        SillyTavernPromptTransformer,
        PromptInjectionTransformer,
        SillyTavernMacroTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    private val stCompatScriptTransformer: SillyTavernCompatScriptTransformer,
    private val termuxCommandManager: TermuxCommandManager,
    private val termuxPtySessionManager: TermuxPtySessionManager,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val stMacroGlobalVariables = ConcurrentHashMap<String, String>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(error = error, conversationId = conversationId) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    private fun getConversationStMacroState(conversationId: Uuid): StMacroState {
        return getOrCreateSession(conversationId).getStMacroState(stMacroGlobalVariables)
    }

    private fun resetConversationStMacroState(conversationId: Uuid) {
        sessions[conversationId]?.let { session ->
            session.resetStMacroLocalVariables()
            session.resetLorebookRuntimeState()
            session.stGenerationType = "normal"
        }
    }

    private fun setConversationStGenerationType(conversationId: Uuid, stGenerationType: String) {
        getOrCreateSession(conversationId).stGenerationType = stGenerationType.trim().lowercase().ifBlank { "normal" }
    }

    private fun getConversationStGenerationType(conversationId: Uuid): String {
        return sessions[conversationId]?.stGenerationType ?: "normal"
    }

    private fun resolveConversationStGenerationType(
        conversationId: Uuid,
        conversation: Conversation,
    ): String {
        val persisted = conversation.currentMessages
            .readLatestAssistantStRuntimeSnapshot()
            ?.generationType
            .orEmpty()
            .trim()
            .lowercase()
        if (persisted.isNotBlank()) {
            return persisted
        }
        return getConversationStGenerationType(conversationId)
    }

    private suspend fun restoreConversationStRuntimeState(
        conversationId: Uuid,
        visibleMessages: List<UIMessage>,
    ) {
        val session = getOrCreateSession(conversationId)
        // Unsnapshotted assistant turns may come from imported history, greeting presets,
        // or legacy branches, so treating them as prior generations corrupts ST state.
        session.restoreStRuntimeState(visibleMessages.readLatestAssistantStRuntimeSnapshot())
    }

    private fun selectMessagesForGeneration(
        messages: List<UIMessage>,
        messageRange: ClosedRange<Int>?,
    ): List<UIMessage> {
        if (messageRange == null) return messages

        val start = messageRange.start.coerceIn(0, messages.size)
        val endExclusive = (messageRange.endInclusive + 1).coerceIn(start, messages.size)
        return messages.subList(start, endExclusive)
    }

    private fun selectMessagesForStateRestore(
        messages: List<UIMessage>,
        messageRange: ClosedRange<Int>?,
    ): List<UIMessage> {
        if (messageRange == null) return messages

        val endExclusive = (messageRange.endInclusive + 1).coerceIn(0, messages.size)
        return messages.take(endExclusive)
    }

    private fun persistAssistantRuntimeSnapshot(
        conversation: Conversation,
        messageId: Uuid?,
        session: ConversationSession,
    ): Conversation {
        if (messageId == null) return conversation

        val snapshot = session.snapshotStRuntimeState()
        val updatedNodes = conversation.messageNodes.map { node ->
            val messageIndex = node.messages.indexOfFirst { it.id == messageId }
            if (messageIndex == -1) {
                node
            } else {
                node.copy(
                    messages = node.messages.mapIndexed { index, message ->
                        if (index == messageIndex) {
                            message.withStRuntimeSnapshot(snapshot)
                        } else {
                            message
                        }
                    }
                )
            }
        }
        return conversation.copy(messageNodes = updatedNodes)
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            resetConversationStMacroState(conversationId)
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.resolveConversationStarterMessages())
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        forceTermuxCommandMode: Boolean = false,
        stGenerationType: String = "normal",
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val currentSettings = settingsStore.settingsFlow.value
        val normalizedGenerationType = stGenerationType.trim().lowercase().ifBlank { "normal" }
        val resolvedContent = when {
            forceTermuxCommandMode && content.isEmptyInputMessage() -> return
            else -> currentSettings.resolveStSendIfEmptyContent(
                content = content,
                answer = answer,
                stGenerationType = normalizedGenerationType,
            ) ?: return
        }
        val commandModeEnabled = forceTermuxCommandMode || currentSettings.termuxCommandModeEnabled
        val directCommand = TermuxDirectCommandParser.parse(
            parts = resolvedContent,
            commandModeEnabled = commandModeEnabled
        )
        val processedContent = if (directCommand.isDirect) {
            preprocessUserInputParts(
                parts = listOf(UIMessagePart.Text(TermuxDirectCommandParser.toSlashCommandText(directCommand.command))),
                messageDepthFromEnd = 1,
                placement = AssistantRegexPlacement.SLASH_COMMAND,
            )
        } else {
            preprocessUserInputParts(resolvedContent, messageDepthFromEnd = 1)
        }

        val job = appScope.launch {
            try {
                val currentConversation = session.state.value

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                if (directCommand.isDirect) {
                    handleDirectTermuxCommand(
                        conversationId = conversationId,
                        parseResult = directCommand
                    )
                } else if (answer) {
                    // 开始补全
                    handleMessageComplete(
                        conversationId = conversationId,
                        stGenerationType = normalizedGenerationType,
                    )
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId)
            }
        }
        session.setJob(job)
    }

    private suspend fun handleDirectTermuxCommand(
        conversationId: Uuid,
        parseResult: TermuxDirectCommandParseResult,
    ) {
        val command = parseResult.command.trim()
        if (command.isBlank()) {
            appendDirectTermuxResultMessage(
                conversationId = conversationId,
                payload = "命令不能为空，请输入 /termux <command>"
            )
            return
        }

        val settings = settingsStore.settingsFlow.value
        val result = runCatching {
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", command),
                    workdir = settings.termuxWorkdir,
                    background = true,
                    timeoutMs = settings.termuxTimeoutMs,
                    label = "RikkaHub /termux",
                    description = "Direct command mode"
                )
            )
        }.getOrElse { e ->
            if (e is CancellationException) {
                withContext(NonCancellable) {
                    appendDirectTermuxResultMessage(
                        conversationId = conversationId,
                        payload = "命令执行已取消。"
                    )
                }
                throw e
            }
            TermuxResult(
                errMsg = buildString {
                    append(e.message ?: e.javaClass.name)
                    append("\n")
                    append(
                        "如果仍然是配置问题，请确认已安装 Termux，并在 Termux 中开启 allow-external-apps，" +
                            "同时授予本应用 com.termux.permission.RUN_COMMAND 权限。"
                    )
                }
            )
        }

        appendDirectTermuxResultMessage(
            conversationId = conversationId,
            payload = formatDirectTermuxOutput(result)
        )
    }

    private suspend fun appendDirectTermuxResultMessage(
        conversationId: Uuid,
        payload: String,
    ) {
        updateConversationState(conversationId) { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(TermuxUserShellCommandCodec.createTextPart(payload))
                ).toMessageNode()
            )
        }
        saveConversation(conversationId, getConversationFlow(conversationId).value)
    }

    private fun formatDirectTermuxOutput(result: TermuxResult): String {
        val output = TermuxOutputFormatter.merge(
            stdout = result.stdout,
            stderr = result.stderr,
        )
        val status = TermuxOutputFormatter.statusSummary(result)
        if (output.isNotBlank()) {
            return if (result.isSuccessful() || status.isBlank()) {
                output
            } else {
                "$output\n$status"
            }
        }
        if (status.isNotBlank()) return status
        return "命令执行完成，但没有输出。"
    }

    private fun extractPtySessionIds(messages: List<UIMessage>): Set<String> {
        val currentMessage = messages.lastOrNull() ?: return emptySet()
        return currentMessage.getTools().asSequence()
            .flatMap { tool -> tool.output.asSequence() }
            .mapNotNull { part ->
                val text = (part as? UIMessagePart.Text)?.text ?: return@mapNotNull null
                runCatching {
                    JsonInstant.decodeFromString<TermuxPtyToolResponse>(text)
                }.getOrNull()?.sessionId
            }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        messageDepthFromEnd: Int = 1,
        placement: Int = AssistantRegexPlacement.USER_INPUT,
        isEdit: Boolean = false,
    ): List<UIMessagePart> {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return preprocessUserInputParts(
            parts = parts,
            assistant = assistant,
            messageDepthFromEnd = messageDepthFromEnd,
            placement = placement,
            isEdit = isEdit,
        )
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        messageDepthFromEnd: Int = 1,
        placement: Int = AssistantRegexPlacement.USER_INPUT,
        isEdit: Boolean = false,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            settings = settingsStore.settingsFlow.value,
                            scope = AssistantAffectScope.USER,
                            phase = AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                            messageDepthFromEnd = messageDepthFromEnd,
                            placement = placement,
                            isEdit = isEdit,
                        )
                    )
                }

                else -> part
            }
        }
    }

    suspend fun executeScheduledTask(task: ScheduledPromptTask): Result<ScheduledTaskExecutionResult> = runCatching {
        if (task.prompt.isBlank()) {
            throw BadRequestException("Scheduled prompt cannot be blank")
        }

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(task.assistantId)
            ?: throw NotFoundException("Assistant not found: ${task.assistantId}")
        val maxSearchIndex = (settings.searchServices.size - 1).coerceAtLeast(0)
        val effectiveSearchServiceIndex = task.overrideSearchServiceIndex?.let { index ->
            if (settings.searchServices.isEmpty()) {
                settings.searchServiceSelected
            } else {
                index.coerceIn(0, maxSearchIndex)
            }
        } ?: settings.searchServiceSelected.coerceIn(0, maxSearchIndex)
        val effectiveSettings = settings.copy(
            enableWebSearch = task.overrideEnableWebSearch ?: settings.enableWebSearch,
            searchServiceSelected = effectiveSearchServiceIndex
        )
        val effectiveAssistant = effectiveSettings.applyActiveStPresetSampling(
            assistant.copy(
                chatModelId = task.overrideModelId ?: assistant.chatModelId,
                localTools = task.overrideLocalTools ?: assistant.localTools,
                mcpServers = task.overrideMcpServers ?: assistant.mcpServers
            )
        )
        val model = effectiveAssistant.chatModelId?.let { effectiveSettings.findModelById(it) }
            ?: effectiveSettings.getCurrentChatModel()
            ?: throw IllegalStateException("No model configured for scheduled task")
        val provider = model.findProvider(effectiveSettings.providers)
            ?: throw IllegalStateException("Provider not found for model: ${model.id}")
        val mcpServerScope = effectiveAssistant.mcpServers

        val messages = buildList {
            addAll(effectiveAssistant.presetMessages)
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = preprocessUserInputParts(
                        parts = listOf(UIMessagePart.Text(task.prompt)),
                        assistant = effectiveAssistant
                    )
                )
            )
        }

        var generatedMessages = messages
        generationHandler.generateText(
            settings = effectiveSettings,
            model = model,
            messages = messages,
            assistant = effectiveAssistant,
            memories = if (effectiveAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(effectiveAssistant.id.toString())
            },
            stMacroState = StMacroState(globalVariables = stMacroGlobalVariables),
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(RegexPromptOnlyTransformer)
                add(stCompatScriptTransformer)
            },
            outputTransformers = outputTransformers,
            tools = buildList {
                if (effectiveSettings.enableWebSearch) {
                    addAll(createSearchTools(effectiveSettings))
                }
                addAll(
                    localTools.getTools(
                        options = effectiveAssistant.localTools,
                        overrideTermuxNeedsApproval = task.overrideTermuxNeedsApproval
                    )
                )
                mcpManager.getAvailableToolsForServers(mcpServerScope).forEach { tool ->
                    add(
                        Tool(
                            name = "mcp__${tool.name}",
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = false,
                            execute = {
                                mcpManager.callToolFromServers(
                                    serverIds = mcpServerScope,
                                    toolName = tool.name,
                                    args = it.jsonObject
                                )
                            },
                        )
                    )
                }
            },
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    generatedMessages = chunk.messages
                }
            }
        }

        val finalMessage = generatedMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw IllegalStateException("Scheduled task did not generate an assistant reply")
        val replyText = finalMessage.toText().trim()
        if (replyText.isBlank()) {
            throw IllegalStateException("Scheduled task generated an empty reply")
        }

        ScheduledTaskExecutionResult(
            replyPreview = replyText.take(200),
            replyText = replyText.take(20_000),
            modelId = model.id,
            providerName = provider.name
        )
    }.onFailure {
        addError(it)
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        stGenerationType: String = "normal",
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    resetConversationStMacroState(conversationId)
                    handleMessageComplete(
                        conversationId = conversationId,
                        stGenerationType = stGenerationType,
                    )
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        resetConversationStMacroState(conversationId)
                        handleMessageComplete(
                            conversationId = conversationId,
                            messageRange = 0..<nodeIndex,
                            stGenerationType = stGenerationType,
                        )
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId)
            }
        }

        session.setJob(job)
    }

    fun continueAssistantMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        if (message.role != MessageRole.ASSISTANT) return
        if (message.hasBlockingToolsForContinuation()) {
            message.getTools().lastOrNull { it.isPending }?.let { pendingTool ->
                sendToolApprovalNotification(conversationId, pendingTool)
            }
            addError(
                IllegalStateException("Continue is unavailable until this message's tool calls are resolved."),
                conversationId
            )
            return
        }

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val node = conversation.getMessageNodeByMessage(message)
                    ?: error("Message node not found")
                val nodeIndex = conversation.messageNodes.indexOf(node)
                val truncatedConversation = conversation.copy(
                    messageNodes = conversation.messageNodes.subList(0, nodeIndex + 1)
                )
                saveConversation(conversationId, truncatedConversation)
                // Continue should inherit ST runtime state from the reply being extended.
                handleMessageComplete(
                    conversationId = conversationId,
                    stGenerationType = "continue",
                )
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId)
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        cancelToolApprovalNotification(conversationId)

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(
                        conversationId = conversationId,
                        stGenerationType = resolveConversationStGenerationType(conversationId, updatedConversation),
                    )
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId)
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        notifyOnCompletion: Boolean = true,
        stGenerationType: String = "normal",
    ) {
        val session = getOrCreateSession(conversationId)
        setConversationStGenerationType(conversationId, stGenerationType)
        val settings = settingsStore.settingsFlow.first()
        val conversation = getConversationFlow(conversationId).value
        val assistant = settings.applyActiveStPresetSampling(
            settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        )
        val model = assistant.chatModelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel() ?: return
        val mcpTools = mcpManager.getAvailableToolsForServers(assistant.mcpServers)

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val ptySessionsOpenedThisRun = linkedSetOf<String>()
        var latestGeneratedAssistantId: Uuid? = null
        runCatching {
            // reset suggestions
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpTools.isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)

            val effectiveConversation = getConversationFlow(conversationId).value
            val messagesForGeneration = selectMessagesForGeneration(
                messages = effectiveConversation.currentMessages,
                messageRange = messageRange,
            )
            val messagesForStateRestore = selectMessagesForStateRestore(
                messages = effectiveConversation.currentMessages,
                messageRange = messageRange,
            )
            restoreConversationStRuntimeState(
                conversationId = conversationId,
                visibleMessages = messagesForStateRestore,
            )
            setConversationStGenerationType(conversationId, stGenerationType)

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messagesForGeneration,
                assistant = assistant,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                stGenerationType = stGenerationType,
                stMacroState = getConversationStMacroState(conversationId),
                lorebookRuntimeState = session.getLorebookRuntimeState(),
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(RegexPromptOnlyTransformer)
                    add(stCompatScriptTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    mcpTools.forEach { tool ->
                        add(
                            Tool(
                                name = "mcp__" + tool.name,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = tool.needsApproval,
                                execute = {
                                    mcpManager.callToolFromServers(
                                        serverIds = assistant.mcpServers,
                                        toolName = tool.name,
                                        args = it.jsonObject
                                    )
                                },
                            )
                        )
                    }
                },
            ).onCompletion { cause ->
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                if (cause is CancellationException && ptySessionsOpenedThisRun.isNotEmpty()) {
                    withContext(NonCancellable) {
                        termuxPtySessionManager.closeSessions(ptySessionsOpenedThisRun)
                    }
                }

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)
                val hasPendingToolApproval =
                    findPendingApprovalTool(updatedConversation.currentMessages) != null

                // Show notification if app is not in foreground
                if (
                    notifyOnCompletion &&
                    !hasPendingToolApproval &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        ptySessionsOpenedThisRun += extractPtySessionIds(chunk.messages)
                        latestGeneratedAssistantId = chunk.messages
                            .lastOrNull { it.role == MessageRole.ASSISTANT }
                            ?.id
                            ?: latestGeneratedAssistantId
                        val previousPendingToolId =
                            findPendingApprovalTool(getConversationFlow(conversationId).value.currentMessages)?.toolCallId
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)
                        val pendingTool = findPendingApprovalTool(updatedConversation.currentMessages)
                        when {
                            pendingTool == null -> cancelToolApprovalNotification(conversationId)
                            pendingTool.toolCallId != previousPendingToolId ->
                                sendToolApprovalNotification(conversationId, pendingTool)
                        }

                        // 如果应用不在前台，发送 Live Update 通知
                        if (notifyOnCompletion && !isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            it.printStackTrace()
            addError(it, conversationId)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = persistAssistantRuntimeSnapshot(
                conversation = getConversationFlow(conversationId).value,
                messageId = latestGeneratedAssistantId,
                session = session,
            )
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { index, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Skip removal if any tool is Approved (waiting to be executed)
                val hasApprovedTool = node.currentMessage.getTools().any {
                    it.approvalState is ToolApprovalState.Approved
                }
                if (hasApprovedTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove message with pending non-approved tools
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(it, conversationId)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        require(targetTokens > 0) { "targetTokens must be greater than 0" }
        require(keepRecentMessages >= 0) { "keepRecentMessages must be at least 0" }

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                ),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun getToolApprovalNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 20000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun sendToolApprovalNotification(
        conversationId: Uuid,
        tool: UIMessagePart.Tool,
    ) {
        if (!settingsStore.settingsFlow.value.displaySetting.enableToolApprovalNotification) {
            cancelToolApprovalNotification(conversationId)
            return
        }
        context.sendNotification(
            channelId = TOOL_APPROVAL_NOTIFICATION_CHANNEL_ID,
            notificationId = getToolApprovalNotificationId(conversationId)
        ) {
            title = context.getString(R.string.notification_tool_approval_title)
            content = buildToolApprovalNotificationText(tool)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun buildToolApprovalNotificationText(tool: UIMessagePart.Tool): String {
        val toolLabel = tool.toolName.removePrefix("mcp__").ifBlank { tool.toolName }
        val arguments = tool.inputAsJson().jsonObject
        val preview = when (tool.toolName) {
            "termux_exec" -> arguments["command"]?.jsonPrimitive?.contentOrNull
            "termux_python" -> arguments["code"]?.jsonPrimitive?.contentOrNull
            "write_stdin" -> arguments["chars"]?.jsonPrimitive?.contentOrNull
            else -> arguments["query"]?.jsonPrimitive?.contentOrNull
                ?: arguments["url"]?.jsonPrimitive?.contentOrNull
                ?: arguments["text"]?.jsonPrimitive?.contentOrNull
        }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return if (preview != null) {
            "$toolLabel: ${preview.take(160)}"
        } else {
            context.getString(R.string.notification_tool_approval_content, toolLabel)
        }
    }

    private fun findPendingApprovalTool(messages: List<UIMessage>): UIMessagePart.Tool? {
        return messages.lastOrNull()
            ?.getTools()
            ?.lastOrNull { it.isPending }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun cancelToolApprovalNotification(conversationId: Uuid) {
        context.cancelNotification(getToolApprovalNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId)
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) return

        val processedParts = preprocessUserInputParts(
            parts = parts,
            messageDepthFromEnd = currentConversation.messageNodes.size - targetNodeIndex,
            isEdit = true,
        )

        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }
        if (!edited) return

        resetConversationStMacroState(conversationId)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        resetConversationStMacroState(conversationId)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        resetConversationStMacroState(conversationId)
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    fun stopGeneration(conversationId: Uuid) {
        sessions[conversationId]?.getJob()?.cancel()
    }
}
