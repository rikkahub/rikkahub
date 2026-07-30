package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.effectiveCapabilityProfile
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.PersistedAgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.canonicalJson
import me.rerere.rikkahub.data.ai.agent.digest
import me.rerere.rikkahub.data.ai.agent.compact.CompactPolicy
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflight
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflightAction
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflightRequest
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.prompt.ProjectDocsTransformer
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.agent.tools.providers.McpInvalidServerNameException
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.AgentStepSummary
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toSnapshotSummary
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.AgentRunRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 4_096

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
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
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val toolRegistry: ToolRegistry,
    private val projectDocsTransformer: ProjectDocsTransformer,
    private val compactPolicy: CompactPolicy,
    private val agentRunRepository: AgentRunRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val generationLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
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

    fun cleanup() = runCatching {
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

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
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
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val job = appScope.launch {
            try {
                agentRunRepository.awaitStartupRecovery()
                generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    val previousJob = session.getJob()?.takeIf { it != currentCoroutineContext()[Job] }
                    previousJob?.cancel()
                    runCatching { previousJob?.join() }
                    agentRunRepository.getActiveRun(conversationId.toString())?.id?.let { activeRunId ->
                        agentRunRepository.cancelRun(activeRunId)
                        finishInterruptedPendingTools(conversationId, activeRunId)
                    }

                    val currentConversation = session.state.value
                    val settings = settingsStore.settingsFlow.first()
                    val assistant = settings.getAssistantById(currentConversation.assistantId)
                        ?: settings.getCurrentAssistant()
                    val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                    val newConversation = currentConversation.copy(
                        messageNodes = currentConversation.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        ).toMessageNode(),
                    )
                    saveConversation(conversationId, newConversation)

                // 开始补全
                    if (answer) handleMessageComplete(conversationId)

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage failed errorType=${e.javaClass.simpleName}")
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
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
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)

        val job = appScope.launch {
            try {
                agentRunRepository.awaitStartupRecovery()
                generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    val previousJob = session.getJob()?.takeIf { it != currentCoroutineContext()[Job] }
                    previousJob?.cancel()
                    runCatching { previousJob?.join() }
                    agentRunRepository.getActiveRun(conversationId.toString())?.id?.let { activeRunId ->
                        agentRunRepository.cancelRun(activeRunId)
                        finishInterruptedPendingTools(conversationId, activeRunId)
                    }
                    val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        tool: UIMessagePart.Tool,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)

        val job = appScope.launch {
            try {
                agentRunRepository.awaitStartupRecovery()
                generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    val conversation = session.state.value
                    val activeRun = agentRunRepository.getActiveRun(conversationId.toString())
                    val target = activeRun?.takeIf { it.status == AgentRunStatus.WAITING_APPROVAL.name }
                        ?.let { resolveApprovalTarget(conversation, it.id, tool) }
                        ?: return@withLock
                    val hasTargetCard = conversation.messageNodes.any { node ->
                        node.messages.any { message ->
                            message.parts.any { part -> part is UIMessagePart.Tool && target.matches(part) }
                        }
                    }
                    if (!hasTargetCard) return@withLock
                    val resolution = PersistedAgentRunRuntime(agentRunRepository, target.runId)
                        .approvalResolution(target.approvalId, target.executionId, approved || answer != null)
                    if (!resolution.resolved && resolution.replacementApprovalId == null) return@withLock
                    if (resolution.replacementApprovalId != null) {
                        val updatedConversation = rebindExpiredApprovalCard(
                            conversation = conversation,
                            executionId = target.executionId,
                            expiredApprovalId = target.approvalId,
                            replacementApprovalId = resolution.replacementApprovalId,
                        ) ?: return@withLock
                        saveConversation(conversationId, updatedConversation)
                        _generationDoneFlow.emit(conversationId)
                        return@withLock
                    }
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // A card is updated once only. A stale UI event must never alter another turn's same call id.
                    var updatedCard = false
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        if (part is UIMessagePart.Tool && !updatedCard && target.matches(part)) {
                                            updatedCard = true
                                            part.copy(approvalState = newApprovalState)
                                        } else part
                                    }
                                )
                            }
                        )
                    }
                    if (!updatedCard) return@withLock
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = agentRunRepository.getApprovals(target.runId).any { it.status == "PENDING" }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        agentRunRepository.resumeRunAfterApproval(target.runId)
                        handleMessageComplete(conversationId, continuationRunId = target.runId)
                    }
                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    private data class ApprovalTarget(
        val runId: String,
        val executionId: String,
        val approvalId: String,
        val legacyTool: UIMessagePart.Tool? = null,
    ) {
        fun matches(tool: UIMessagePart.Tool): Boolean = if (legacyTool == null) {
            tool.toolExecutionId == executionId && tool.approvalId == approvalId
        } else {
            tool.toolExecutionId == null && tool.approvalId == null && tool == legacyTool
        }
    }

    /** Legacy cards lack persisted IDs, so ambiguity is rejected rather than approving a different turn. */
    private suspend fun resolveApprovalTarget(
        conversation: Conversation,
        runId: String,
        requestedTool: UIMessagePart.Tool,
    ): ApprovalTarget? {
        val executionId = requestedTool.toolExecutionId
        val approvalId = requestedTool.approvalId
        if (executionId != null && approvalId != null) {
            val approval = agentRunRepository.getApproval(approvalId) ?: return null
            val execution = agentRunRepository.getToolExecution(executionId) ?: return null
            if (
                approval.runId != runId || approval.toolExecutionId != executionId || approval.status != "PENDING" ||
                execution.runId != runId || execution.status != "WAITING_APPROVAL" ||
                execution.toolName != requestedTool.toolName ||
                execution.inputSha256 != requestedTool.input.canonicalJson().digest()
            ) return null
            return ApprovalTarget(runId, executionId, approvalId)
        }
        if (executionId != null || approvalId != null) return null

        val canonicalInput = requestedTool.input.canonicalJson().digest()
        val matchingCards = conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .toList()
        val approvals = agentRunRepository.getPendingApprovalsByToolIdentity(runId, requestedTool.toolName, canonicalInput)
        val matchingCard = selectLegacyApprovalCard(requestedTool, matchingCards, approvals.size) ?: return null
        return ApprovalTarget(runId, approvals.single().toolExecutionId, approvals.single().id, matchingCard)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        continuationRunId: String? = null,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val agentMode = initialConversation.agentMode
        val selectedModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val run = if (continuationRunId != null) {
            agentRunRepository.getRun(continuationRunId)?.takeIf {
                it.conversationId == conversationId.toString() && it.status == AgentRunStatus.RUNNING.name
            } ?: return
        } else {
            agentRunRepository.replaceActiveRun(
                id = Uuid.random().toString(),
                conversationId = conversationId.toString(),
                assistantId = assistant.id.toString(),
                configSnapshot = AgentRunConfigSnapshot(
                    runtimeVersion = "agent-loop-v2",
                    conversationId = conversationId.toString(),
                    assistantId = assistant.id.toString(),
                    modelId = (assistant.chatModelId ?: settings.chatModelId).toString(),
                    agentMode = agentMode.name,
                    maxSteps = 256,
                    toolPolicyVersion = "capability-policy-v2",
                    capabilitySummary = selectedModel?.effectiveCapabilityProfile()?.toSnapshotSummary(),
                ),
            ).also {
                agentRunRepository.transitionRun(it.id, setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
            }
        }
        val session = getOrCreateSession(conversationId)
        val generationJob = currentCoroutineContext()[Job] ?: return
        if (!session.bindRun(run.id, generationJob)) return
        val model = selectedModel ?: run {
            agentRunRepository.failRun(run.id, "MODEL_NOT_FOUND")
            return
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val agentMode = conversation.agentMode
            // Plan/Agent 始终注入权限说明（学 Codex developer permission）；CHAT 默认不注入
            val permissionPolicy = PermissionPolicy.compatibleDefault(
                injectPromptForWorkspace = agentMode != AgentMode.CHAT
            )
            val tools = try {
                toolRegistry.resolveWithDescriptors(
                    ToolResolveContext(
                        settings = settings,
                        assistant = assistant,
                        conversation = conversation,
                        mode = agentMode,
                        permissionPolicy = permissionPolicy,
                        agentRunId = run.id,
                        processingStatus = session.processingStatus,
                    )
                )
            } catch (e: McpInvalidServerNameException) {
                agentRunRepository.failRun(run.id, "MCP_INVALID_SERVER_NAME")
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            e.invalidNames.joinToString(", ")
                        )
                    ),
                    conversationId = conversationId,
                )
                return@runCatching
            }

            val workspace = assistant.workspaceId?.let { workspaceRepository.getById(it.toString())?.toWorkspace() }
            val provider = model.findProvider(settings.providers) ?: run {
                agentRunRepository.failRun(run.id, "PROVIDER_NOT_FOUND")
                return@runCatching
            }
            val generationMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            val preflight = ProviderPreflight.evaluate(
                ProviderPreflightRequest(
                    mode = agentMode,
                    capabilities = model.effectiveCapabilityProfile(),
                    resolvedFunctionToolCount = tools.size,
                    configuredNativeToolCount = model.tools.size,
                    requestedOutputTokens = assistant.maxTokens,
                    outputReserveTokens = assistant.maxTokens ?: DEFAULT_OUTPUT_RESERVE_TOKENS,
                    streamingRequested = assistant.streamOutput,
                    reasoningRequested = assistant.reasoningLevel.isEnabled,
                    multimodalInputRequested = generationMessages.any { message ->
                        message.parts.any { part ->
                            part is UIMessagePart.Image || part is UIMessagePart.Video ||
                                part is UIMessagePart.Audio || part is UIMessagePart.Document
                        }
                    },
                )
            )
            if (preflight.codes.isNotEmpty()) {
                agentRunRepository.recordStep(
                    id = Uuid.random().toString(),
                    runId = run.id,
                    kind = "provider_preflight",
                    status = if (preflight.action == ProviderPreflightAction.BLOCK) {
                        AgentStepStatus.FAILED
                    } else {
                        AgentStepStatus.SUCCEEDED
                    },
                    summary = AgentStepSummary(
                        kind = "provider_preflight",
                        detail = preflight.codes.joinToString(",") { it.name },
                    ),
                )
            }
            if (preflight.action == ProviderPreflightAction.BLOCK) {
                agentRunRepository.blockRun(
                    run.id,
                    preflight.codes.joinToString("_") { it.name },
                    category = "provider_capability",
                )
                addError(
                    IllegalStateException(preflight.userMessage),
                    conversationId,
                    title = context.getString(R.string.error_title_tool_unavailable),
                )
                return@runCatching
            }
            val modelForRequest = if (preflight.allowNativeTools) model else model.copy(tools = emptySet())
            val assistantForRequest = assistant.copy(
                maxTokens = preflight.outputTokens,
                streamOutput = preflight.streaming,
                reasoningLevel = if (preflight.reasoning) assistant.reasoningLevel else ReasoningLevel.OFF,
            )
            if (continuationRunId == null) {
                agentRunRepository.transitionRun(run.id, setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
            }
            val runRuntime = PersistedAgentRunRuntime(agentRunRepository, run.id)

            generationHandler.generateText(
                settings = settings,
                model = modelForRequest,
                processingStatus = session.processingStatus,
                messages = generationMessages,
                assistant = assistantForRequest,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                mode = agentMode,
                permissionPolicy = permissionPolicy,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(projectDocsTransformer)
                },
                outputTransformers = outputTransformers,
                tools = if (preflight.allowFunctionTools) tools.map { it.tool } else emptyList(),
                describedTools = if (preflight.allowFunctionTools) tools else emptyList(),
                workspace = workspace,
                runRuntime = runRuntime,
                artifactRunScope = me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope(
                    assistantId = assistant.id.toString(),
                    conversationId = conversation.id.toString(),
                    runId = run.id,
                ),
                allowParallelToolCalls = preflight.allowParallelToolCalls,
                useClientGeneratedToolExecutionIdentity = preflight.useClientGeneratedToolExecutionIdentity,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            agentRunRepository.failRun(run.id, it.javaClass.simpleName, "preflight_or_generation")
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Log.w(TAG, "generationFailed runId=${run.id} errorType=${it.javaClass.simpleName}")
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            if (agentRunRepository.getActiveRun(conversationId.toString())?.status == AgentRunStatus.WAITING_APPROVAL.name) {
                return@onSuccess
            }

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
        // Tool calls are conversation history. Never delete their input or output while preparing a request.
        val messageNodes = conversation.messageNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        if (messageNodes != conversation.messageNodes) {
            updateConversation(conversationId, conversation.copy(messageNodes = messageNodes))
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid, runId: String? = null) {
        val currentConversation = getConversationFlow(conversationId).value
        val executionIds = runId?.let { agentRunRepository.getToolExecutions(it).mapTo(hashSetOf()) { execution -> execution.id } }
        var changed = false
        val updatedConversation = currentConversation.copy(messageNodes = currentConversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                message.copy(parts = message.parts.map { part ->
                    if (
                        part is UIMessagePart.Tool && !part.isExecuted &&
                        (executionIds == null || part.toolExecutionId in executionIds)
                    ) {
                        changed = true
                        cancelToolByUser(part)
                    } else part
                })
            })
        })
        if (!changed) return
        saveConversation(conversationId, updatedConversation)
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
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            Log.w(TAG, "generateTitle failed errorType=${it.javaClass.simpleName}")
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
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
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
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
            Log.w(TAG, "generateSuggestion failed errorType=${it.javaClass.simpleName}")
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
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val additionalContext = if (additionalPrompt.isNotBlank()) {
                "Additional instructions from user: $additionalPrompt"
            } else {
                ""
            }
            // 走 CompactPolicy，模板仍用用户 settings.compressPrompt（默认 = DEFAULT_COMPRESS_PROMPT）
            val prompt = compactPolicy.buildCompressPrompt(
                content = contentToCompress,
                targetTokens = targetTokens,
                locale = Locale.getDefault().displayName,
                additionalContext = additionalContext,
                template = settings.compressPrompt,
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
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

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folder: Folder) {
        sessions.values
            .filter { session ->
                session.state.value.folderId == folder.id && session.state.value.assistantId == folder.assistantId
            }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folder)
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
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
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
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
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
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
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
    suspend fun stopGeneration(conversationId: Uuid, runId: String) {
        agentRunRepository.awaitStartupRecovery()
        // Cancel the job bound to this run before waiting for the generation lock. Generation itself
        // holds that lock while collecting, so waiting first would make Stop ineffective.
        val targetJob = sessions[conversationId]?.getJobForRun(runId)
        targetJob?.cancel()
        generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
            val run = agentRunRepository.getRun(runId) ?: return@withLock
            if (run.conversationId != conversationId.toString() || run.status !in AgentRunStatus.ACTIVE.map(AgentRunStatus::name)) {
                return@withLock
            }
            // A replacement may have started while this request was queued. Never touch its job.
            sessions[conversationId]?.getJobForRun(runId)?.takeIf { it === targetJob }?.cancel()
            if (agentRunRepository.cancelRun(runId)) {
                finishInterruptedPendingTools(conversationId, runId)
            }
        }
    }
}

/** Old cards have no persisted identity; more than one compatible card is never safe to approve. */
internal fun selectLegacyApprovalCard(
    requestedTool: UIMessagePart.Tool,
    cards: List<UIMessagePart.Tool>,
    pendingApprovalCount: Int,
): UIMessagePart.Tool? {
    if (pendingApprovalCount != 1) return null
    val canonicalInput = requestedTool.input.canonicalJson().digest()
    return cards.singleOrNull {
        it.toolExecutionId == null && it.approvalId == null && it.isPending &&
            it.toolName == requestedTool.toolName && it.input.canonicalJson().digest() == canonicalInput
    }
}

/** Rebind exactly one persisted card when an expired approval is renewed in place. */
internal fun rebindExpiredApprovalCard(
    conversation: Conversation,
    executionId: String,
    expiredApprovalId: String,
    replacementApprovalId: String,
): Conversation? {
    var updated = false
    val nodes = conversation.messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            message.copy(parts = message.parts.map { part ->
                if (
                    part is UIMessagePart.Tool && !updated &&
                    part.toolExecutionId == executionId && part.approvalId == expiredApprovalId
                ) {
                    updated = true
                    part.copy(
                        approvalState = ToolApprovalState.Pending,
                        approvalId = replacementApprovalId,
                        approvalStatusMessage = "授权已过期，请重新确认",
                    )
                } else {
                    part
                }
            })
        })
    }
    return conversation.copy(messageNodes = nodes).takeIf { updated }
}
