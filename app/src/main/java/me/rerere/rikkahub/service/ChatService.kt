package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
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
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.prompt.ProjectDocsTransformer
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflightResult
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.AgentRunContinuationRequest
import me.rerere.rikkahub.data.ai.agent.routing.AgentRunContinuationResult
import me.rerere.rikkahub.data.ai.agent.routing.AgentRunPlan
import me.rerere.rikkahub.data.ai.agent.routing.AgentRunPlanner
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.ai.agent.routing.IntentDecision
import me.rerere.rikkahub.data.ai.agent.routing.IntentRouter
import me.rerere.rikkahub.data.ai.agent.routing.IntentRoutingInput
import me.rerere.rikkahub.data.ai.agent.routing.NewAgentRunPlanRequest
import me.rerere.rikkahub.data.ai.agent.routing.RuleBasedIntentRouter
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.agent.tools.WorkspaceToolPolicy
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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Folder
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
import java.util.concurrent.atomic.AtomicLong
import me.rerere.workspace.Workspace
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 4_096
private const val DEFAULT_AGENT_MAX_STEPS = 256
private const val DEFAULT_PROVIDER_IDLE_TIMEOUT_MILLIS = 45_000L
private const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L
private const val DEFAULT_RUN_TIMEOUT_MILLIS = 30 * 60_000L
private const val DEFAULT_CANCELLATION_FINALIZE_TIMEOUT_MILLIS = 10_000L
private const val DEFAULT_BACKGROUND_POSTPROCESS_TIMEOUT_MILLIS = 45_000L
private const val MAX_RETAINED_WAITING_APPROVAL_CONTEXTS = 16
private const val WAITING_APPROVAL_CONTEXT_RETENTION_MILLIS = 24 * 60 * 60_000L

internal data class FrozenRunExecutionIdentity(
    val runId: String,
    val conversationId: String,
    val assistantId: String,
    val modelId: String,
    val providerId: String,
    val workspaceId: String?,
    val useGlobalMemory: Boolean,
    val streamOutput: Boolean,
    val reasoningEnabled: Boolean,
    val hasConversationPrompt: Boolean,
    val hasWorkspaceCwd: Boolean,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
)

/** A content-free digest. Prompt text, credentials, headers, bodies, messages and tool arguments never enter it. */
internal fun executionContextDigest(identity: FrozenRunExecutionIdentity): String {
    val canonical = listOf(
        "frozen-run-context-v1",
        identity.runId,
        identity.conversationId,
        identity.assistantId,
        identity.modelId,
        identity.providerId,
        identity.workspaceId.orEmpty(),
        identity.useGlobalMemory.toString(),
        identity.streamOutput.toString(),
        identity.reasoningEnabled.toString(),
        identity.hasConversationPrompt.toString(),
        identity.hasWorkspaceCwd.toString(),
        identity.modeInjectionIds.sorted().joinToString(","),
        identity.lorebookIds.sorted().joinToString(","),
    ).joinToString("\u001f")
    return "sha256:${canonical.digest()}"
}

internal fun routeChatIntent(
    router: IntentRouter,
    parts: List<UIMessagePart>,
    trust: InputTrust,
    hasWorkspace: Boolean,
): IntentDecision = router.route(IntentRoutingInput.fromUserParts(parts, trust, hasWorkspace))

internal fun shouldPrepareAgentRun(answer: Boolean): Boolean = answer

internal fun canInstallGenerationBoundary(
    isDeleting: Boolean,
    isDeleted: Boolean,
    isCurrentSession: Boolean,
): Boolean = !isDeleting && !isDeleted && isCurrentSession

internal fun canPublishRestoredConversation(
    rowIsDurable: Boolean,
    oldSessionIsDetached: Boolean,
): Boolean = rowIsDurable && oldSessionIsDetached

/** Idempotently closes UI-only state that cannot survive a process restart. */
internal fun reconcileInterruptedConversationPresentation(conversation: Conversation): Conversation {
    var changed = false
    val messageNodes = conversation.messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            val closedParts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) {
                    changed = true
                    part.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"cancelled","error":"Generation was interrupted by process restart."}"""
                            )
                        ),
                        approvalState = ToolApprovalState.Denied("Generation interrupted by process restart"),
                    )
                } else {
                    part
                }
            }
            val closedMessage = message.copy(parts = closedParts).finishReasoning()
            if (closedMessage != message) changed = true
            closedMessage
        })
    }
    return if (changed) {
        conversation.copy(messageNodes = messageNodes, updateAt = Instant.now())
    } else {
        conversation
    }
}

internal suspend inline fun continueApprovedRunIfResumed(
    hasPendingApprovals: Boolean,
    crossinline resumeRun: suspend () -> Boolean,
    crossinline continuation: suspend () -> Unit,
): Boolean {
    if (hasPendingApprovals || !resumeRun()) return false
    continuation()
    return true
}

internal fun canPublishRunSideEffect(
    isCurrentLease: Boolean,
    boundRunId: String?,
    runId: String,
): Boolean = isCurrentLease && boundRunId == runId

internal fun <T> frozenContextOrNull(cache: ConcurrentHashMap<String, T>, runId: String): T? =
    runId.takeIf(String::isNotBlank)?.let(cache::get)

/** Private, non-serializable execution material. Its string form is deliberately redacted. */
private class FrozenRunExecutionContext(
    val identity: FrozenRunExecutionIdentity,
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val provider: ProviderSetting,
    val workspace: Workspace?,
    val permissionPolicy: PermissionPolicy,
    val describedTools: List<DescribedTool>,
    val conversationSystemPrompt: String?,
    val conversationModeInjectionIds: Set<Uuid>,
    val conversationLorebookIds: Set<Uuid>,
    val workspaceCwd: String?,
    val projectDocsPrompt: String?,
    val memories: List<AssistantMemory>?,
    val processingStatus: MutableStateFlow<String?>,
    val plan: AgentRunPlan,
    val preflight: ProviderPreflightResult,
    val senderName: String,
) {
    val runId: String get() = identity.runId
    val digest: String get() = executionContextDigest(identity)
    private val phaseSequence = AtomicLong(0L)
    val activePhaseEpoch = AtomicLong(-1L)
    val scheduledEndedPhaseEpochs: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val presentedMessageIds: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
    val createdAtMillis: Long = System.currentTimeMillis()

    @Volatile
    private var approvalRetentionJob: Job? = null

    fun beginPhase(): Long {
        val phase = phaseSequence.incrementAndGet()
        check(phase > 0L) { "Generation phase sequence exhausted for Run $runId" }
        activePhaseEpoch.set(phase)
        return phase
    }

    fun installApprovalRetention(job: Job): Boolean = synchronized(this) {
        if (approvalRetentionJob?.isActive == true) {
            false
        } else {
            approvalRetentionJob = job
            true
        }
    }

    fun cancelApprovalRetention() = synchronized(this) {
        approvalRetentionJob?.cancel()
        approvalRetentionJob = null
    }

    override fun toString(): String =
        "FrozenRunExecutionContext(runId=${identity.runId}, conversationId=${identity.conversationId}, " +
            "assistantId=${identity.assistantId}, modelId=${identity.modelId}, providerId=${identity.providerId}, redacted=true)"
}

private data class PreparedGeneration(
    val runId: String,
    val context: FrozenRunExecutionContext,
    val messages: List<UIMessage>,
)

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
    private val intentRouter: IntentRouter = RuleBasedIntentRouter(),
    private val agentRunPlanner: AgentRunPlanner = AgentRunPlanner(),
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer()

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val generationLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val frozenRunExecutionContexts = ConcurrentHashMap<String, FrozenRunExecutionContext>()
    private val deletingConversations: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
    private val deletedConversations: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
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
    private val _generationDoneFlow = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        frozenRunExecutionContexts.values.forEach(FrozenRunExecutionContext::cancelApprovalRetention)
        frozenRunExecutionContexts.clear()
    }

    private fun removeFrozenRunExecutionContext(
        runId: String,
        expected: FrozenRunExecutionContext? = null,
    ): FrozenRunExecutionContext? {
        val removed = if (expected == null) {
            frozenRunExecutionContexts.remove(runId)
        } else {
            expected.takeIf { frozenRunExecutionContexts.remove(runId, expected) }
        }
        removed?.cancelApprovalRetention()
        return removed
    }

    /**
     * Reconciles persisted message presentation while the repository's startup generation gate is closed.
     * No tool execution is resumed: unfinished cards and reasoning are terminalized fail-closed.
     */
    suspend fun reconcileInterruptedRunsOnStartup(interruptedRuns: List<AgentRunEntity>): Set<Uuid> {
        val conversationIds = interruptedRuns.mapNotNullTo(linkedSetOf()) { run ->
            runCatching { Uuid.parse(run.conversationId) }.getOrNull()
        }
        conversationIds.forEach { conversationId ->
            generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                val conversation = conversationRepo.getConversationById(conversationId) ?: return@withLock
                val reconciled = reconcileInterruptedConversationPresentation(conversation)
                if (reconciled != conversation) saveConversation(conversationId, reconciled)
                sessions[conversationId]?.processingStatus?.value = null
            }
        }
        return conversationIds
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
            "Conversation is being or was deleted: $conversationId"
        }
        var created = false
        val session = sessions.computeIfAbsent(conversationId) { id ->
            created = true
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
        if (conversationId in deletingConversations || conversationId in deletedConversations) {
            if (created && sessions.remove(conversationId, session)) {
                session.cleanup()
                _sessionsVersion.value++
            }
            error("Conversation is being or was deleted: $conversationId")
        }
        return session
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (!session.tryCloseIfIdle { sessions.remove(conversationId, session) }) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        session.cleanup()
        _sessionsVersion.value++
        Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
    }

    // ---- 引用管理 ----

    private fun acquireSessionReference(conversationId: Uuid): ConversationSession {
        check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
            "Conversation is being or was deleted: $conversationId"
        }
        while (true) {
            val session = getOrCreateSession(conversationId)
            if (session.tryAcquire() != null) {
                if (
                    conversationId !in deletingConversations &&
                    conversationId !in deletedConversations &&
                    sessions[conversationId] === session
                ) {
                    return session
                }
                session.release()
                check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
                    "Conversation is being or was deleted: $conversationId"
                }
            }
        }
    }

    fun addConversationReference(conversationId: Uuid) {
        acquireSessionReference(conversationId)
    }

    fun acquireConversationSessionHandle(conversationId: Uuid): ConversationSessionHandle {
        val session = acquireSessionReference(conversationId)
        return ConversationSessionHandle(
            conversation = session.state,
            generationJob = session.generationJob,
            processingStatus = session.processingStatus,
            release = session::release,
        )
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
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
        check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
            "Conversation is being or was deleted: $conversationId"
        }
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

    /** Caller holds the conversation generation mutex. Used for the approval CAS hand-off. */
    private fun launchInstalledGenerationLocked(
        conversationId: Uuid,
        expectedEpoch: Long? = null,
        boundRunId: String? = null,
        block: suspend (ConversationSession, GenerationLease) -> Unit,
    ): Boolean {
        val session = acquireSessionReference(conversationId)
        lateinit var lease: GenerationLease
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            block(session, lease)
        }
        job.invokeOnCompletion { session.release() }
        val installed = if (
            canInstallGenerationBoundary(
                isDeleting = conversationId in deletingConversations,
                isDeleted = conversationId in deletedConversations,
                isCurrentSession = sessions[conversationId] === session,
            )
        ) {
            if (expectedEpoch == null) session.install(job) else session.installIfEpoch(job, expectedEpoch)
        } else null
        if (installed == null || boundRunId != null && !session.bindRun(installed, boundRunId)) {
            job.cancel()
            return false
        }
        lease = installed
        job.start()
        return true
    }

    /**
     * Requests cancellation immediately, then advances the epoch under the write transaction lock.
     * A stuck old preparation therefore receives cancellation promptly without opening a stale-save window.
     */
    private fun launchReplacementGeneration(
        conversationId: Uuid,
        block: suspend (ConversationSession, GenerationLease) -> Unit,
    ) {
        val session = acquireSessionReference(conversationId)
        lateinit var lease: GenerationLease
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            block(session, lease)
        }
        job.invokeOnCompletion { session.release() }
        session.cancelCurrentJob()
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var started = false
            try {
                val installed = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    if (
                        canInstallGenerationBoundary(
                            isDeleting = conversationId in deletingConversations,
                            isDeleted = conversationId in deletedConversations,
                            isCurrentSession = sessions[conversationId] === session,
                        )
                    ) {
                        session.install(job)
                    } else null
                } ?: return@launch
                lease = installed
                started = job.start()
            } finally {
                if (!started) job.cancel()
            }
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        launchReplacementGeneration(conversationId) { session, lease ->
            var runId: String? = null
            try {
                agentRunRepository.awaitStartupRecovery()
                val prepared = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    prepareSendGeneration(
                        conversationId = conversationId,
                        session = session,
                        lease = lease,
                        rawContent = content,
                        answer = answer,
                    )
                }
                runId = prepared?.runId
                val completionPublished = prepared?.let {
                    executePreparedGeneration(conversationId, session, lease, it)
                } ?: false
                if (!completionPublished) publishGenerationDone(conversationId, session, lease, runId)
                runId?.let { cleanupFrozenContextIfTerminal(it) }
            } catch (error: CancellationException) {
                runId?.let { ownedRunId ->
                    cancelGenerationIfOwned(conversationId, session, lease, ownedRunId)
                }
                throw error
            } catch (e: Throwable) {
                Log.w(TAG, "sendMessage failed errorType=${e.javaClass.simpleName}")
                if (isCurrentGeneration(session, lease, runId)) {
                    runId?.let { ownedRunId ->
                        agentRunRepository.failRun(ownedRunId, e.javaClass.simpleName, "preflight_or_generation")
                    }
                    addErrorForGeneration(
                        conversationId,
                        session,
                        lease,
                        runId,
                        e,
                        context.getString(R.string.error_title_send_message),
                    )
                    runId?.let(::removeFrozenRunExecutionContext)
                }
            } finally {
                runId?.let { ownedRunId ->
                    withContext(NonCancellable) {
                        cleanupFrozenContextIfTerminal(ownedRunId)
                    }
                }
            }
        }
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
        launchReplacementGeneration(conversationId) { session, lease ->
            var runId: String? = null
            try {
                agentRunRepository.awaitStartupRecovery()
                val prepared = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    prepareRegeneration(
                        conversationId = conversationId,
                        session = session,
                        lease = lease,
                        message = message,
                        regenerateAssistantMessage = regenerateAssistantMsg,
                    )
                }
                runId = prepared?.runId
                val completionPublished = prepared?.let {
                    executePreparedGeneration(conversationId, session, lease, it)
                } ?: false
                if (!completionPublished) publishGenerationDone(conversationId, session, lease, runId)
                runId?.let { cleanupFrozenContextIfTerminal(it) }
            } catch (error: CancellationException) {
                runId?.let { ownedRunId ->
                    cancelGenerationIfOwned(conversationId, session, lease, ownedRunId)
                }
                throw error
            } catch (e: Throwable) {
                if (isCurrentGeneration(session, lease, runId)) {
                    runId?.let { ownedRunId ->
                        agentRunRepository.failRun(ownedRunId, e.javaClass.simpleName, "preflight_or_generation")
                    }
                    addErrorForGeneration(
                        conversationId,
                        session,
                        lease,
                        runId,
                        e,
                        context.getString(R.string.error_title_regenerate_message),
                    )
                    runId?.let(::removeFrozenRunExecutionContext)
                }
            } finally {
                runId?.let { ownedRunId ->
                    withContext(NonCancellable) {
                        cleanupFrozenContextIfTerminal(ownedRunId)
                    }
                }
            }
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        tool: UIMessagePart.Tool,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ): Job {
        val session = acquireSessionReference(conversationId)
        val expectedEpoch = session.epochToken()

        return appScope.launch {
            try {
                agentRunRepository.awaitStartupRecovery()
                generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    if (sessions[conversationId] !== session || session.epochToken() != expectedEpoch) return@withLock
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

                    val frozenContext = frozenContextOrNull(frozenRunExecutionContexts, target.runId)
                    if (frozenContext == null) {
                        agentRunRepository.blockRun(target.runId, "EXECUTION_CONTEXT_MISSING", "continuation")
                        return@withLock
                    }
                    val restoredPlan = when (val restored = agentRunPlanner.restoreContinuation(
                        AgentRunContinuationRequest(
                            configSnapshotJson = activeRun.configSnapshotJson,
                            runId = activeRun.id,
                            runConversationId = activeRun.conversationId,
                            runAssistantId = activeRun.assistantId,
                            parentRunId = activeRun.parentRunId,
                            runStatus = AgentRunStatus.valueOf(activeRun.status),
                            conversationId = conversationId.toString(),
                            assistantId = frozenContext.assistant.id.toString(),
                            modelId = frozenContext.model.id.toString(),
                            providerId = frozenContext.provider.id.toString(),
                            workspaceId = frozenContext.identity.workspaceId,
                            capabilitySummary = frozenContext.plan.configSnapshot.capabilitySummary,
                            availableToolNames = frozenContext.describedTools.map { it.tool.name },
                            permissionDigest = frozenContext.plan.routing.permissionDigest,
                            executionContextDigest = frozenContext.digest,
                        )
                    )) {
                        is AgentRunContinuationResult.AutoReady -> restored.plan
                        is AgentRunContinuationResult.Blocked -> {
                            agentRunRepository.blockRun(
                                target.runId,
                                "CONTINUATION_${restored.reason.name}",
                                "continuation",
                            )
                            removeFrozenRunExecutionContext(target.runId)
                            return@withLock
                        }

                        is AgentRunContinuationResult.LegacyReady -> {
                            agentRunRepository.blockRun(target.runId, "LEGACY_CONTEXT_NOT_FROZEN", "continuation")
                            removeFrozenRunExecutionContext(target.runId)
                            return@withLock
                        }
                    }
                    if (restoredPlan.configSnapshot != frozenContext.plan.configSnapshot) {
                        agentRunRepository.blockRun(target.runId, "FROZEN_PLAN_DRIFT", "continuation")
                        removeFrozenRunExecutionContext(target.runId)
                        return@withLock
                    }

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
                        persistApprovalConversationOrBlock(
                            conversationId,
                            session,
                            expectedEpoch,
                            target.runId,
                            updatedConversation,
                        )
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
                    if (!persistApprovalConversationOrBlock(
                            conversationId,
                            session,
                            expectedEpoch,
                            target.runId,
                            updatedConversation,
                        )
                    ) {
                        return@withLock
                    }

                    // Check if there are still pending tools
                    val hasPendingTools = agentRunRepository.getApprovals(target.runId).any { it.status == "PENDING" }

                    // Only continue generation when all pending tools are handled
                    val continued = continueApprovedRunIfResumed(
                        hasPendingApprovals = hasPendingTools,
                        resumeRun = { agentRunRepository.resumeRunAfterApproval(target.runId) },
                        continuation = {
                            frozenContext.cancelApprovalRetention()
                            val prepared = PreparedGeneration(
                                runId = target.runId,
                                context = frozenContext,
                                messages = updatedConversation.currentMessages,
                            )
                            val installed = launchInstalledGenerationLocked(
                                conversationId = conversationId,
                                expectedEpoch = expectedEpoch,
                                boundRunId = target.runId,
                            ) { installedSession, lease ->
                                try {
                                    val completionPublished = executePreparedGeneration(
                                        conversationId,
                                        installedSession,
                                        lease,
                                        prepared,
                                    )
                                    if (!completionPublished) {
                                        publishGenerationDone(
                                            conversationId,
                                            installedSession,
                                            lease,
                                            target.runId,
                                        )
                                    }
                                    cleanupFrozenContextIfTerminal(target.runId)
                                } catch (error: CancellationException) {
                                    cancelGenerationIfOwned(
                                        conversationId,
                                        installedSession,
                                        lease,
                                        target.runId,
                                    )
                                    throw error
                                } catch (error: Throwable) {
                                    if (isCurrentGeneration(installedSession, lease, target.runId)) {
                                        agentRunRepository.failRun(
                                            target.runId,
                                            error.javaClass.simpleName,
                                            "continuation",
                                        )
                                        addErrorForGeneration(
                                            conversationId,
                                            installedSession,
                                            lease,
                                            target.runId,
                                            error,
                                            context.getString(R.string.error_title_generation),
                                        )
                                        removeFrozenRunExecutionContext(target.runId)
                                    }
                                } finally {
                                    withContext(NonCancellable) {
                                        cleanupFrozenContextIfTerminal(target.runId)
                                    }
                                }
                            }
                            if (!installed) {
                                agentRunRepository.cancelRun(target.runId)
                                removeFrozenRunExecutionContext(target.runId)
                            }
                        },
                    )
                    if (!continued) cleanupFrozenContextIfTerminal(target.runId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Throwable) {
                generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                    if (session.epochToken() == expectedEpoch) {
                        addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
                    }
                }
            } finally {
                session.release()
            }
        }
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
        val approvals =
            agentRunRepository.getPendingApprovalsByToolIdentity(runId, requestedTool.toolName, canonicalInput)
        val matchingCard = selectLegacyApprovalCard(requestedTool, matchingCards, approvals.size) ?: return null
        return ApprovalTarget(runId, approvals.single().toolExecutionId, approvals.single().id, matchingCard)
    }

    // ---- 处理消息补全 ----

    private suspend fun prepareSendGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        rawContent: List<UIMessagePart>,
        answer: Boolean,
    ): PreparedGeneration? {
        currentCoroutineContext().ensureActive()
        if (!session.isCurrent(lease)) return null
        cancelActiveRunForReplacement(conversationId, session, lease)
        if (!session.isCurrent(lease)) return null

        val currentConversation = session.state.value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        // Authorization is derived from the untouched input surface, never from regex/OCR/document transforms.
        val decision = if (shouldPrepareAgentRun(answer)) {
            routeChatIntent(intentRouter, rawContent, InputTrust.USER_DIRECT, assistant.workspaceId != null)
        } else {
            null
        }
        val processedContent = preprocessUserInputParts(rawContent, assistant)
        val nextConversation = normalizeConversation(
            currentConversation.copy(
                messageNodes = currentConversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                ).toMessageNode(),
                chatSuggestions = if (answer) emptyList() else currentConversation.chatSuggestions,
            )
        )
        if (!saveConversationForGenerationLocked(conversationId, session, lease, null, nextConversation)) return null
        if (!shouldPrepareAgentRun(answer)) return null

        return prepareNewGeneration(
            conversationId = conversationId,
            session = session,
            lease = lease,
            settings = settings,
            assistant = assistant,
            conversation = nextConversation,
            generationMessages = nextConversation.currentMessages,
            decision = checkNotNull(decision),
            inputTrust = InputTrust.USER_DIRECT,
        )
    }

    private suspend fun prepareRegeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        message: UIMessage,
        regenerateAssistantMessage: Boolean,
    ): PreparedGeneration? {
        currentCoroutineContext().ensureActive()
        if (!session.isCurrent(lease)) return null
        cancelActiveRunForReplacement(conversationId, session, lease)
        if (!session.isCurrent(lease)) return null

        val originalConversation = normalizeConversation(session.state.value)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(originalConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val preparedConversation: Conversation
        val generationMessages: List<UIMessage>
        val routingParts: List<UIMessagePart>

        if (message.role == MessageRole.USER) {
            val node = originalConversation.getMessageNodeByMessage(message)
            val nodeIndex = originalConversation.messageNodes.indexOf(node)
            if (nodeIndex < 0) return null
            preparedConversation = originalConversation.copy(
                messageNodes = originalConversation.messageNodes.take(nodeIndex + 1),
                chatSuggestions = emptyList(),
            )
            generationMessages = preparedConversation.currentMessages
            routingParts = message.parts
        } else {
            if (!regenerateAssistantMessage) {
                saveConversationForGenerationLocked(conversationId, session, lease, null, originalConversation)
                return null
            }
            val node = originalConversation.getMessageNodeByMessage(message)
            val nodeIndex = originalConversation.messageNodes.indexOf(node)
            if (nodeIndex < 0) return null
            preparedConversation = originalConversation.copy(chatSuggestions = emptyList())
            generationMessages = preparedConversation.currentMessages.take(nodeIndex)
            routingParts = generationMessages.lastOrNull { it.role == MessageRole.USER }?.parts.orEmpty()
        }
        if (!saveConversationForGenerationLocked(
                conversationId,
                session,
                lease,
                null,
                preparedConversation,
            )
        ) return null

        val decision = routeChatIntent(
            router = intentRouter,
            parts = routingParts,
            trust = InputTrust.DERIVED_UNTRUSTED,
            hasWorkspace = assistant.workspaceId != null,
        )
        return prepareNewGeneration(
            conversationId = conversationId,
            session = session,
            lease = lease,
            settings = settings,
            assistant = assistant,
            conversation = preparedConversation,
            generationMessages = generationMessages,
            decision = decision,
            inputTrust = InputTrust.DERIVED_UNTRUSTED,
        )
    }

    private suspend fun cancelActiveRunForReplacement(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
    ) {
        val replacedRun = lease.replacedRunId
            ?.let { agentRunRepository.getRun(it) }
            ?.takeIf { it.conversationId == conversationId.toString() }
        val activeRun = agentRunRepository.getActiveRun(conversationId.toString())
        val run = replacedRun ?: activeRun ?: return
        val replacedContext = frozenContextOrNull(frozenRunExecutionContexts, run.id)
        if (
            run.status !in AgentRunStatus.ACTIVE.map(AgentRunStatus::name) &&
            replacedContext == null
        ) {
            return
        }
        val cleaned = withContext(NonCancellable) {
            withTimeoutOrNull(DEFAULT_CANCELLATION_FINALIZE_TIMEOUT_MILLIS) {
                if (!session.isCurrent(lease)) return@withTimeoutOrNull false
                // Close cards first. If this times out, leave the Run active so the next replacement
                // can discover and retry it instead of orphaning a terminal Run with pending cards.
                finishInterruptedPendingTools(conversationId, run.id) { session.isCurrent(lease) }
                val finishedConversation = finishReplacementPresentationLocked(
                    conversationId,
                    session,
                    lease,
                ) ?: return@withTimeoutOrNull false
                if (run.status in AgentRunStatus.ACTIVE.map(AgentRunStatus::name)) {
                    agentRunRepository.cancelRun(run.id)
                }
                if (replacedContext != null) {
                    scheduleRunEndedIfCurrent(
                        session,
                        lease,
                        replacedContext,
                        finishedConversation,
                        ownerRunId = null,
                        includeContentPreview = false,
                    )
                }
                removeFrozenRunExecutionContext(run.id)
                true
            } == true
        }
        if (!cleaned) throw CancellationException("Timed out while replacing active Run ${run.id}")
        currentCoroutineContext().ensureActive()
    }

    private suspend fun prepareNewGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        generationMessages: List<UIMessage>,
        decision: IntentDecision,
        inputTrust: InputTrust,
    ): PreparedGeneration {
        currentCoroutineContext().ensureActive()
        check(session.isCurrent(lease)) { "Generation lease was replaced during preparation" }
        val runId = Uuid.random().toString()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("Selected chat model is unavailable")
        val provider = model.findProvider(settings.providers)
            ?: error("Selected model provider is unavailable")
        val workspaceEntity = assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }
        val workspace = workspaceEntity?.toWorkspace()
        val workspaceToolPolicy = workspaceEntity?.let { entity ->
            WorkspaceToolPolicy.Frozen(
                workspace = checkNotNull(workspace),
                approvalOverrides = entity.toolApprovalOverrides().toMap(),
            )
        } ?: WorkspaceToolPolicy.FrozenAbsent
        val projectDocsPrompt = projectDocsTransformer.snapshot(workspace, conversation.workspaceCwd)
        val normalizedDecision = if (
            inputTrust == InputTrust.DERIVED_UNTRUSTED && decision.intent == AgentIntent.EXECUTE
        ) {
            IntentDecision(AgentIntent.EXPLORE, "untrusted_execution_downgraded")
        } else {
            decision
        }
        val requestedCompatibilityMode = when (normalizedDecision.intent) {
            AgentIntent.ANSWER -> AgentMode.CHAT
            AgentIntent.EXPLORE, AgentIntent.CLARIFY -> AgentMode.PLAN
            AgentIntent.EXECUTE -> AgentMode.AGENT
        }
        val permissionPolicy = PermissionPolicy.compatibleDefault(
            injectPromptForWorkspace = requestedCompatibilityMode != AgentMode.CHAT,
            permissionMode = assistant.agentPermissionMode,
        )
        val privateProcessingStatus = MutableStateFlow<String?>(null)
        val profile = try {
            toolRegistry.resolveProfile(
                ctx = ToolResolveContext(
                    settings = settings,
                    assistant = assistant,
                    conversation = conversation,
                    workspace = workspace,
                    workspaceToolPolicy = workspaceToolPolicy,
                    mode = AgentMode.AGENT,
                    permissionPolicy = permissionPolicy,
                    agentRunId = runId,
                    processingStatus = privateProcessingStatus,
                ),
                intent = normalizedDecision.intent,
                inputTrust = inputTrust,
                defaultToolTimeoutMillis = DEFAULT_TOOL_TIMEOUT_MILLIS,
            )
        } catch (error: McpInvalidServerNameException) {
            throw IllegalStateException(
                context.getString(R.string.error_mcp_invalid_server_name, error.invalidNames.joinToString(", ")),
                error,
            )
        }
        val effectiveDecision = normalizedDecision.copy(intent = profile.effectiveIntent)
        val compatibilityMode = when (effectiveDecision.intent) {
            AgentIntent.ANSWER -> AgentMode.CHAT
            AgentIntent.EXPLORE, AgentIntent.CLARIFY -> AgentMode.PLAN
            AgentIntent.EXECUTE -> AgentMode.AGENT
        }
        val preflight = ProviderPreflight.evaluate(
            ProviderPreflightRequest(
                mode = compatibilityMode,
                capabilities = model.effectiveCapabilityProfile(),
                resolvedFunctionToolCount = profile.tools.size,
                configuredNativeToolCount = model.tools.size,
                requestedOutputTokens = assistant.maxTokens,
                outputReserveTokens = assistant.maxTokens ?: DEFAULT_OUTPUT_RESERVE_TOKENS,
                streamingRequested = assistant.streamOutput,
                reasoningRequested = assistant.reasoningLevel.isEnabled,
                multimodalInputRequested = generationMessages.any { candidate ->
                    candidate.parts.any { part ->
                        part is UIMessagePart.Image || part is UIMessagePart.Video ||
                            part is UIMessagePart.Audio || part is UIMessagePart.Document
                    }
                },
            )
        )
        val requestModel = if (preflight.allowNativeTools) model else model.copy(tools = emptySet())
        val requestAssistant = assistant.copy(
            maxTokens = preflight.outputTokens,
            streamOutput = preflight.streaming,
            reasoningLevel = if (preflight.reasoning) assistant.reasoningLevel else ReasoningLevel.OFF,
        )
        val identity = FrozenRunExecutionIdentity(
            runId = runId,
            conversationId = conversationId.toString(),
            assistantId = assistant.id.toString(),
            modelId = model.id.toString(),
            providerId = provider.id.toString(),
            workspaceId = assistant.workspaceId?.toString(),
            useGlobalMemory = assistant.useGlobalMemory,
            streamOutput = requestAssistant.streamOutput,
            reasoningEnabled = requestAssistant.reasoningLevel.isEnabled,
            hasConversationPrompt = conversation.customSystemPrompt != null,
            hasWorkspaceCwd = conversation.workspaceCwd != null,
            modeInjectionIds = conversation.modeInjectionIds.map { it.toString() },
            lorebookIds = conversation.lorebookIds.map { it.toString() },
        )
        val plan = agentRunPlanner.planNewAuto(
            NewAgentRunPlanRequest(
                conversationId = conversationId.toString(),
                assistantId = assistant.id.toString(),
                modelId = model.id.toString(),
                providerId = provider.id.toString(),
                workspaceId = assistant.workspaceId?.toString(),
                maxSteps = DEFAULT_AGENT_MAX_STEPS,
                capabilitySummary = model.effectiveCapabilityProfile().toSnapshotSummary(),
                decision = effectiveDecision,
                inputTrust = inputTrust,
                resolvedToolNames = profile.resolvedToolNames,
                permissionDigest = profile.permissionDigest,
                executionContextDigest = executionContextDigest(identity),
                providerIdleTimeoutMillis = DEFAULT_PROVIDER_IDLE_TIMEOUT_MILLIS,
                toolTimeoutMillis = DEFAULT_TOOL_TIMEOUT_MILLIS,
                runTimeoutMillis = DEFAULT_RUN_TIMEOUT_MILLIS,
            )
        )
        check(plan.mode == compatibilityMode) {
            "Planner mode does not match routed intent"
        }
        val memories = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val frozenContext = FrozenRunExecutionContext(
            identity = identity,
            settings = settings,
            assistant = requestAssistant,
            model = requestModel,
            provider = provider,
            workspace = workspace,
            permissionPolicy = permissionPolicy,
            describedTools = profile.tools,
            conversationSystemPrompt = conversation.customSystemPrompt,
            conversationModeInjectionIds = conversation.modeInjectionIds,
            conversationLorebookIds = conversation.lorebookIds,
            workspaceCwd = conversation.workspaceCwd,
            projectDocsPrompt = projectDocsPrompt,
            memories = memories,
            processingStatus = privateProcessingStatus,
            plan = plan,
            preflight = preflight,
            senderName = senderName,
        )

        currentCoroutineContext().ensureActive()
        check(session.isCurrent(lease)) { "Generation lease was replaced before run persistence" }
        try {
            agentRunRepository.replaceActiveRun(
                id = runId,
                conversationId = conversationId.toString(),
                assistantId = assistant.id.toString(),
                configSnapshot = plan.configSnapshot,
            )
            currentCoroutineContext().ensureActive()
            if (!session.isCurrent(lease)) {
                throw CancellationException("Generation lease replaced after run persistence")
            }
            if (!session.bindRun(lease, runId)) {
                throw CancellationException("Generation lease replaced before run binding")
            }
            frozenRunExecutionContexts[runId] = frozenContext
            if (!agentRunRepository.transitionRun(runId, setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)) {
                error("Unable to enter provider preflight")
            }
            if (!session.isCurrent(lease, runId)) {
                throw CancellationException("Generation lease replaced after provider preflight")
            }
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            removeFrozenRunExecutionContext(runId)
            withContext(NonCancellable) {
                runCatching { agentRunRepository.cancelRun(runId) }
            }
            throw error
        } catch (error: Throwable) {
            removeFrozenRunExecutionContext(runId)
            runCatching { agentRunRepository.failRun(runId, error.javaClass.simpleName, "preflight") }
            throw error
        }
        return PreparedGeneration(runId, frozenContext, generationMessages.toList())
    }

    private suspend fun executePreparedGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        prepared: PreparedGeneration,
    ): Boolean {
        val runId = prepared.runId
        val frozenContext = frozenContextOrNull(frozenRunExecutionContexts, runId)
        if (frozenContext == null || frozenContext !== prepared.context || frozenContext.runId != runId) {
            agentRunRepository.blockRun(runId, "EXECUTION_CONTEXT_MISSING", "execution")
            return false
        }
        if (!isCurrentGeneration(session, lease, runId)) return false
        val phaseEpoch = frozenContext.beginPhase()
        val phaseBaselineMessageIds = prepared.messages.mapTo(hashSetOf()) { it.id }
        appEventBus.emit(
            AppEvent.ChatGenerationStarted(
                conversationId = conversationId,
                runId = runId,
                phaseEpoch = phaseEpoch,
            ),
        )
        if (!isCurrentGeneration(session, lease, runId)) return false

        try {
            val preflight = frozenContext.preflight
            if (preflight.codes.isNotEmpty()) {
                agentRunRepository.recordStep(
                    id = Uuid.random().toString(),
                    runId = runId,
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
                    runId,
                    preflight.codes.joinToString("_") { it.name },
                    category = "provider_capability",
                )
                if (isCurrentGeneration(session, lease, runId)) {
                    finishRunPresentation(conversationId, session, lease, frozenContext)
                    addErrorForGeneration(
                        conversationId,
                        session,
                        lease,
                        runId,
                        IllegalStateException(preflight.userMessage),
                        context.getString(R.string.error_title_tool_unavailable),
                    )
                }
                return false
            }

            val persistedRun = agentRunRepository.getRun(runId)
                ?: error("Persisted Run disappeared before execution")
            val ready = when (persistedRun.status) {
                AgentRunStatus.PREFLIGHT.name -> agentRunRepository.transitionRun(
                    runId,
                    setOf(AgentRunStatus.PREFLIGHT),
                    AgentRunStatus.RUNNING,
                )

                AgentRunStatus.RUNNING.name -> true
                else -> false
            }
            if (!ready) {
                if (isCurrentGeneration(session, lease, runId)) {
                    finishRunPresentation(conversationId, session, lease, frozenContext)
                }
                return false
            }
            if (!isCurrentGeneration(session, lease, runId)) return false

            coroutineScope {
                val projection = launch {
                    frozenContext.processingStatus.collect { status ->
                        projectProcessingStatus(conversationId, session, lease, runId, status)
                    }
                }
                try {
                    withTimeout(frozenContext.plan.routing.runTimeoutMillis) {
                        generationHandler.generateText(
                            settings = frozenContext.settings,
                            model = frozenContext.model,
                            processingStatus = frozenContext.processingStatus,
                            messages = prepared.messages,
                            assistant = frozenContext.assistant,
                            conversationSystemPrompt = frozenContext.conversationSystemPrompt,
                            conversationModeInjectionIds = frozenContext.conversationModeInjectionIds,
                            conversationLorebookIds = frozenContext.conversationLorebookIds,
                            workspaceCwd = frozenContext.workspaceCwd,
                            mode = frozenContext.plan.mode,
                            permissionPolicy = frozenContext.permissionPolicy,
                            memories = frozenContext.memories,
                            inputTransformers = buildList {
                                addAll(inputTransformers)
                                add(templateTransformer)
                                add(workspaceReminderTransformer)
                                add(projectDocsTransformer.fromSnapshot(frozenContext.projectDocsPrompt))
                            },
                            outputTransformers = outputTransformers,
                            tools = if (preflight.allowFunctionTools) {
                                frozenContext.describedTools.map { it.tool }
                            } else {
                                emptyList()
                            },
                            describedTools = if (preflight.allowFunctionTools) {
                                frozenContext.describedTools
                            } else {
                                emptyList()
                            },
                            workspace = frozenContext.workspace,
                            runRuntime = PersistedAgentRunRuntime(agentRunRepository, runId),
                            artifactRunScope = me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope(
                                assistantId = frozenContext.identity.assistantId,
                                conversationId = conversationId.toString(),
                                runId = runId,
                            ),
                            maxSteps = checkNotNull(frozenContext.plan.configSnapshot.maxSteps),
                            allowParallelToolCalls = preflight.allowParallelToolCalls,
                            useClientGeneratedToolExecutionIdentity =
                                preflight.useClientGeneratedToolExecutionIdentity,
                            providerIdleTimeoutMillis = frozenContext.plan.routing.providerIdleTimeoutMillis,
                            defaultToolTimeoutMillis = frozenContext.plan.routing.toolTimeoutMillis,
                        ).collect { chunk ->
                            if (!isCurrentGeneration(session, lease, runId)) return@collect
                            when (chunk) {
                                is GenerationChunk.Messages -> {
                                    val lastMessage = chunk.messages.lastOrNull()
                                    frozenContext.presentedMessageIds.addAll(
                                        chunk.messages.asSequence()
                                            .filter { it.id !in phaseBaselineMessageIds }
                                            .map { it.id }
                                            .toList(),
                                    )
                                    updateConversationForGeneration(
                                        conversationId,
                                        session,
                                        lease,
                                        runId,
                                        transform = { current -> current.updateCurrentMessages(chunk.messages) },
                                        afterUpdate = {
                                            lastMessage?.let { message ->
                                                appEventBus.tryEmit(
                                                    AppEvent.ChatGenerationUpdate(
                                                        conversationId,
                                                        runId,
                                                        phaseEpoch,
                                                        message,
                                                        frozenContext.senderName,
                                                    )
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    projection.cancelAndJoin()
                    projectProcessingStatus(conversationId, session, lease, runId, null)
                }
            }

            val finalConversation = finishRunPresentation(conversationId, session, lease, frozenContext)
                ?: return false
            val completionPublished = publishGenerationDone(conversationId, session, lease, runId)
            val finalRun = agentRunRepository.getRun(runId) ?: return completionPublished
            if (finalRun.status == AgentRunStatus.WAITING_APPROVAL.name) {
                scheduleWaitingApprovalContextRetention(frozenContext)
            } else {
                frozenContext.cancelApprovalRetention()
            }
            if (finalRun.status == AgentRunStatus.SUCCEEDED.name &&
                completionPublished && isCurrentGeneration(session, lease, runId)
            ) {
                coroutineScope {
                    launch { generateTitleForRun(conversationId, session, lease, frozenContext, finalConversation) }
                    launch {
                        generateSuggestionForRun(
                            conversationId,
                            session,
                            lease,
                            frozenContext,
                            finalConversation
                        )
                    }
                }
            }
            return completionPublished
        } catch (error: CancellationException) {
            // The launch boundary owns cancellation persistence. Keeping it there avoids running
            // the same non-cancellable finalizer twice as this exception crosses call boundaries.
            throw error
        } catch (error: Throwable) {
            agentRunRepository.failRun(runId, error.javaClass.simpleName, "preflight_or_generation")
            if (isCurrentGeneration(session, lease, runId)) {
                finishRunPresentation(conversationId, session, lease, frozenContext)
                addErrorForGeneration(
                    conversationId,
                    session,
                    lease,
                    runId,
                    error,
                    context.getString(R.string.error_title_generation),
                )
                Log.w(TAG, "generationFailed runId=" + runId + " errorType=" + error.javaClass.simpleName)
            }
            return false
        }
    }

    private fun isCurrentGeneration(
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
    ): Boolean {
        if (runId == null) return session.isCurrent(lease)
        val boundContext = frozenContextOrNull(frozenRunExecutionContexts, runId)
        return canPublishRunSideEffect(
            isCurrentLease = session.isCurrent(lease, runId),
            boundRunId = boundContext?.runId,
            runId = runId,
        )
    }

    private fun publishIfCurrent(
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
        block: () -> Unit,
    ): Boolean {
        if (runId != null && frozenContextOrNull(frozenRunExecutionContexts, runId)?.runId != runId) return false
        return session.runIfCurrent(lease, runId) {
            if (runId != null && frozenContextOrNull(frozenRunExecutionContexts, runId)?.runId != runId) {
                false
            } else {
                block()
                true
            }
        } == true
    }

    private suspend fun updateConversationForGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
        transform: (Conversation) -> Conversation,
        afterUpdate: (Conversation) -> Unit = {},
    ): Boolean = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
        if (!isCurrentGeneration(session, lease, runId)) return@withLock false
        currentCoroutineContext().ensureActive()
        if (!isCurrentGeneration(session, lease, runId)) return@withLock false
        val updated = transform(session.state.value)
        updateConversation(conversationId, updated)
        publishIfCurrent(session, lease, runId) { afterUpdate(updated) }
    }

    private suspend fun publishGenerationDone(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
    ): Boolean = publishIfCurrent(session, lease, runId) { _generationDoneFlow.tryEmit(conversationId) }

    private suspend fun addErrorForGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
        error: Throwable,
        title: String,
        solution: ChatErrorSolution? = null,
    ) {
        publishIfCurrent(session, lease, runId) {
            addError(error, conversationId, title = title, solution = solution)
        }
    }

    /** Caller holds the conversation generation mutex. */
    private suspend fun saveConversationForGenerationLocked(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String?,
        conversation: Conversation,
    ): Boolean {
        if (!isCurrentGeneration(session, lease, runId)) return false
        currentCoroutineContext().ensureActive()
        if (!isCurrentGeneration(session, lease, runId)) return false
        saveConversation(conversationId, conversation)
        return isCurrentGeneration(session, lease, runId)
    }

    private suspend fun projectProcessingStatus(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String,
        status: String?,
    ) {
        publishIfCurrent(session, lease, runId) { session.processingStatus.value = status }
    }

    private suspend fun cancelGenerationIfOwned(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String,
    ): Boolean = withContext(NonCancellable) {
        val finalized = withTimeoutOrNull(DEFAULT_CANCELLATION_FINALIZE_TIMEOUT_MILLIS) {
            generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                finalizeCancelledGenerationLocked(conversationId, session, lease, runId)
            }
        }
        if (finalized != true) {
            if (finalized == null) Log.w(TAG, "Cancellation finalization timed out runId=$runId")
            // Status is ephemeral, but keep the frozen context and active Run discoverable so a
            // replacement or explicit stop can retry durable card/presentation cleanup.
            session.runIfCurrent(lease, runId) {
                session.processingStatus.value = null
            }
        }
        finalized == true
    }

    /** Caller holds the conversation generation mutex. */
    private suspend fun finalizeCancelledGenerationLocked(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String,
    ): Boolean {
        if (!isCurrentGeneration(session, lease, runId)) return false
        try {
            finishInterruptedPendingTools(conversationId, runId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "cancelled tool presentation failed runId=$runId", error)
            return false
        }
        session.processingStatus.value = null
        try {
            val frozenContext = frozenContextOrNull(frozenRunExecutionContexts, runId) ?: return false
            val finishedConversation = finishRunPresentationLocked(
                conversationId,
                session,
                lease,
                frozenContext,
                publishEndedEvent = false,
            ) ?: return false
            try {
                agentRunRepository.cancelRun(runId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "cancelRun persistence failed runId=$runId", error)
                return false
            }
            scheduleRunEndedIfCurrent(
                session,
                lease,
                frozenContext,
                finishedConversation,
                includeContentPreview = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "cancelled run presentation failed runId=$runId", error)
            return false
        }
        removeFrozenRunExecutionContext(runId)
        return true
    }

    private suspend fun saveConversationAtEpoch(
        conversationId: Uuid,
        session: ConversationSession,
        expectedEpoch: Long,
        conversation: Conversation,
    ): Boolean {
        if (session.epochToken() != expectedEpoch) return false
        currentCoroutineContext().ensureActive()
        if (session.epochToken() != expectedEpoch) return false
        saveConversation(conversationId, conversation)
        return session.epochToken() == expectedEpoch
    }

    /** Caller holds the conversation generation mutex. */
    private suspend fun persistApprovalConversationOrBlock(
        conversationId: Uuid,
        session: ConversationSession,
        expectedEpoch: Long,
        runId: String,
        conversation: Conversation,
    ): Boolean {
        return try {
            if (saveConversationAtEpoch(conversationId, session, expectedEpoch, conversation)) {
                true
            } else {
                runCatching {
                    agentRunRepository.blockRun(runId, "APPROVAL_EPOCH_INVALIDATED", "continuation")
                }
                removeFrozenRunExecutionContext(runId)
                false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Approval state is already authoritative in the run database. Keep the live card aligned and
            // terminate fail-closed instead of leaving a WAITING_APPROVAL run that can never be resumed.
            if (session.epochToken() == expectedEpoch) {
                updateConversation(conversationId, conversation)
                addError(
                    error,
                    conversationId,
                    title = context.getString(R.string.error_title_tool_approval),
                )
            }
            runCatching {
                agentRunRepository.blockRun(runId, "APPROVAL_CARD_PERSIST_FAILED", "continuation")
            }
            removeFrozenRunExecutionContext(runId)
            false
        }
    }

    private suspend fun finishRunPresentation(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        frozenContext: FrozenRunExecutionContext,
    ): Conversation? {
        val finished = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
            finishRunPresentationLocked(conversationId, session, lease, frozenContext)
        } ?: return null
        return finished.takeIf { isCurrentGeneration(session, lease, frozenContext.runId) }
    }

    /** Caller holds the conversation generation mutex. */
    private suspend fun finishRunPresentationLocked(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        frozenContext: FrozenRunExecutionContext,
        publishEndedEvent: Boolean = true,
    ): Conversation? {
        val runId = frozenContext.runId
        if (!isCurrentGeneration(session, lease, runId)) return null
        val current = session.state.value
        val next = current.copy(
            messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.finishReasoning() })
            },
            updateAt = Instant.now(),
        )
        if (!saveConversationForGenerationLocked(conversationId, session, lease, runId, next)) return null
        if (publishEndedEvent) scheduleRunEndedIfCurrent(session, lease, frozenContext, next)
        return next
    }

    /** Caller holds the generation mutex; [lease] is the replacement boundary and is not Run-bound yet. */
    private suspend fun finishReplacementPresentationLocked(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
    ): Conversation? {
        if (!session.isCurrent(lease)) return null
        val current = session.state.value
        val next = current.copy(
            messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.finishReasoning() })
            },
            updateAt = Instant.now(),
        )
        return next.takeIf {
            saveConversationForGenerationLocked(conversationId, session, lease, null, next)
        }
    }

    private fun scheduleRunEndedIfCurrent(
        session: ConversationSession,
        lease: GenerationLease,
        frozenContext: FrozenRunExecutionContext,
        conversation: Conversation,
        ownerRunId: String? = frozenContext.runId,
        includeContentPreview: Boolean = true,
    ): Boolean {
        if (frozenContextOrNull(frozenRunExecutionContexts, frozenContext.runId) !== frozenContext) return false
        return session.runIfCurrent(lease, ownerRunId) {
            if (frozenContextOrNull(frozenRunExecutionContexts, frozenContext.runId) !== frozenContext) {
                false
            } else {
                scheduleRunEnded(frozenContext, conversation, includeContentPreview)
                true
            }
        } == true
    }

    private fun scheduleRunEnded(
        frozenContext: FrozenRunExecutionContext,
        conversation: Conversation,
        includeContentPreview: Boolean = true,
    ) {
        val phaseEpoch = frozenContext.activePhaseEpoch.get()
        if (!frozenContext.scheduledEndedPhaseEpochs.add(phaseEpoch)) return
        val event = AppEvent.ChatGenerationEnded(
            conversationId = conversation.id,
            runId = frozenContext.runId,
            phaseEpoch = phaseEpoch,
            senderName = frozenContext.senderName,
            contentPreview = if (includeContentPreview) {
                conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim().orEmpty()
            } else {
                null
            },
        )
        appScope.launch {
            try {
                appEventBus.emit(event)
            } catch (error: CancellationException) {
                frozenContext.scheduledEndedPhaseEpochs.remove(phaseEpoch)
                throw error
            } catch (error: Throwable) {
                frozenContext.scheduledEndedPhaseEpochs.remove(phaseEpoch)
                Log.w(TAG, "Unable to publish terminal generation event runId=${frozenContext.runId}", error)
            }
        }
    }

    private suspend fun generateTitleForRun(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        frozenContext: FrozenRunExecutionContext,
        conversation: Conversation,
    ) {
        if (conversation.title.isNotBlank() || !isCurrentGeneration(session, lease, frozenContext.runId)) return
        runCatching {
            val settings = frozenContext.settings
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = withTimeoutOrNull(
                minOf(
                    frozenContext.plan.routing.providerIdleTimeoutMillis,
                    DEFAULT_BACKGROUND_POSTPROCESS_TIMEOUT_MILLIS,
                ),
            ) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            settings.titlePrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to conversation.currentMessages.takeLast(4)
                                    .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                            )
                        )
                    ),
                    params = backgroundTextGenerationParams(model),
                )
            } ?: return
            val title = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
            mutateAndSaveConversationForGeneration(
                conversationId,
                session,
                lease,
                frozenContext.runId,
            ) { latest ->
                if (latest.title.isBlank()) latest.copy(title = title) else latest
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            addErrorForGeneration(
                conversationId,
                session,
                lease,
                frozenContext.runId,
                error,
                context.getString(R.string.error_title_generate_title),
                ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    private suspend fun generateSuggestionForRun(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        frozenContext: FrozenRunExecutionContext,
        conversation: Conversation,
    ) {
        runCatching {
            val settings = frozenContext.settings
            if (!settings.enableSuggestion || !isCurrentGeneration(session, lease, frozenContext.runId)) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = withTimeoutOrNull(
                minOf(
                    frozenContext.plan.routing.providerIdleTimeoutMillis,
                    DEFAULT_BACKGROUND_POSTPROCESS_TIMEOUT_MILLIS,
                ),
            ) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            settings.suggestionPrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to conversation.currentMessages.takeLast(8)
                                    .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                            )
                        )
                    ),
                    params = backgroundTextGenerationParams(model),
                )
            } ?: return
            val suggestions = result.choices.firstOrNull()?.message?.toText()?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            mutateAndSaveConversationForGeneration(
                conversationId,
                session,
                lease,
                frozenContext.runId,
            ) { latest -> latest.copy(chatSuggestions = suggestions.take(10)) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "generateSuggestion failed errorType=" + error.javaClass.simpleName)
        }
    }

    private suspend fun mutateAndSaveConversationForGeneration(
        conversationId: Uuid,
        session: ConversationSession,
        lease: GenerationLease,
        runId: String,
        transform: (Conversation) -> Conversation,
    ): Boolean = generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
        if (!isCurrentGeneration(session, lease, runId)) return@withLock false
        saveConversationForGenerationLocked(
            conversationId,
            session,
            lease,
            runId,
            transform(session.state.value),
        )
    }

    private suspend fun cleanupFrozenContextIfTerminal(runId: String) {
        val frozenContext = frozenContextOrNull(frozenRunExecutionContexts, runId) ?: return
        val status = agentRunRepository.getRun(runId)?.status ?: run {
            removeFrozenRunExecutionContext(runId)
            return
        }
        val terminal = AgentRunStatus.entries.firstOrNull { it.name == status }?.isTerminal == true
        val activePhase = frozenContext.activePhaseEpoch.get()
        val presentationCommitted = activePhase >= 0L && activePhase in frozenContext.scheduledEndedPhaseEpochs
        if (terminal && presentationCommitted) removeFrozenRunExecutionContext(runId, frozenContext)
    }

    private fun scheduleWaitingApprovalContextRetention(frozenContext: FrozenRunExecutionContext) {
        val retentionJob = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                delay(WAITING_APPROVAL_CONTEXT_RETENTION_MILLIS)
                expireWaitingApprovalContext(frozenContext, "APPROVAL_CONTEXT_EXPIRED")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to expire approval context runId=${frozenContext.runId}", error)
            }
        }
        if (frozenContext.installApprovalRetention(retentionJob)) {
            retentionJob.start()
        } else {
            retentionJob.cancel()
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { enforceWaitingApprovalContextLimit() }
                .onFailure { Log.w(TAG, "Unable to enforce approval context limit", it) }
        }
    }

    private suspend fun enforceWaitingApprovalContextLimit() {
        val waitingContexts = frozenRunExecutionContexts.values.mapNotNull { frozenContext ->
            val run = agentRunRepository.getRun(frozenContext.runId)
            frozenContext.takeIf { run?.status == AgentRunStatus.WAITING_APPROVAL.name }
        }.sortedBy(FrozenRunExecutionContext::createdAtMillis)
        val overflow = waitingContexts.size - MAX_RETAINED_WAITING_APPROVAL_CONTEXTS
        if (overflow <= 0) return
        waitingContexts.take(overflow).forEach { frozenContext ->
            expireWaitingApprovalContext(frozenContext, "APPROVAL_CONTEXT_CAPACITY")
        }
    }

    private suspend fun expireWaitingApprovalContext(
        frozenContext: FrozenRunExecutionContext,
        reasonCode: String,
    ) {
        val conversationId = runCatching { Uuid.parse(frozenContext.identity.conversationId) }.getOrNull()
            ?: run {
                removeFrozenRunExecutionContext(frozenContext.runId, frozenContext)
                return
            }
        generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
            if (frozenContextOrNull(frozenRunExecutionContexts, frozenContext.runId) !== frozenContext) return@withLock
            val run = agentRunRepository.getRun(frozenContext.runId) ?: run {
                removeFrozenRunExecutionContext(frozenContext.runId, frozenContext)
                return@withLock
            }
            if (run.status != AgentRunStatus.WAITING_APPROVAL.name) {
                val terminal = AgentRunStatus.entries.firstOrNull { it.name == run.status }?.isTerminal == true
                if (terminal) removeFrozenRunExecutionContext(frozenContext.runId, frozenContext)
                return@withLock
            }
            // Persist presentation first. If it fails, retain both the active Run and its context so a
            // later cap pass or explicit stop can retry without orphaning approval cards.
            finishInterruptedRunPresentationLocked(conversationId, frozenContext.runId) ?: return@withLock
            agentRunRepository.blockRun(frozenContext.runId, reasonCode, "approval_retention")
            removeFrozenRunExecutionContext(frozenContext.runId, frozenContext)
        }
    }

    private fun normalizeConversation(conversation: Conversation): Conversation {
        val normalizedNodes = conversation.messageNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }
        return if (normalizedNodes == conversation.messageNodes) conversation else {
            conversation.copy(messageNodes = normalizedNodes)
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

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        runId: String? = null,
        canWrite: () -> Boolean = { true },
    ): Conversation? {
        if (!canWrite()) return null
        val currentConversation = sessions[conversationId]?.state?.value
            ?: conversationRepo.getConversationById(conversationId)
            ?: return null
        val executionIds =
            runId?.let { agentRunRepository.getToolExecutions(it).mapTo(hashSetOf()) { execution -> execution.id } }
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
        if (!changed) return currentConversation
        if (!canWrite()) return null
        saveConversation(conversationId, updatedConversation)
        return updatedConversation
    }

    /** Caller holds the generation mutex; no lease is required when retiring an unowned stale Run. */
    private suspend fun finishInterruptedRunPresentationLocked(
        conversationId: Uuid,
        runId: String,
        reasoningMessageIds: Set<Uuid>? = null,
    ): Conversation? {
        val current = finishInterruptedPendingTools(conversationId, runId)
            ?: sessions[conversationId]?.state?.value
            ?: conversationRepo.getConversationById(conversationId)
            ?: return null
        val finished = current.copy(
            messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message ->
                    if (reasoningMessageIds == null || message.id in reasoningMessageIds) {
                        message.finishReasoning()
                    } else {
                        message
                    }
                })
            },
            updateAt = Instant.now(),
        )
        saveConversation(conversationId, finished)
        return finished
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
        check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
            "Conversation is being or was deleted: $conversationId"
        }
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
            "Conversation lifecycle changed while saving: $conversationId"
        }
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
                "Conversation lifecycle changed before insert: $conversationId"
            }
            conversationRepo.insertConversation(updatedConversation) {
                conversationId !in deletingConversations && conversationId !in deletedConversations
            }
        } else {
            check(conversationId !in deletingConversations && conversationId !in deletedConversations) {
                "Conversation lifecycle changed before update: $conversationId"
            }
            conversationRepo.updateConversation(updatedConversation) {
                conversationId !in deletingConversations && conversationId !in deletedConversations
            }
        }
    }

    /**
     * Imports a conversation through the same per-conversation lifecycle mutex as generation and deletion.
     * The old session epoch is invalidated before the durable replacement is exposed, so a deleted lifecycle's
     * generation can never regain ownership when its UUID is restored.
     */
    suspend fun restoreImportedConversation(conversation: Conversation): Boolean {
        agentRunRepository.awaitStartupRecovery()
        val conversationId = conversation.id
        val lock = generationLocks.computeIfAbsent(conversationId) { Mutex() }
        return lock.withLock {
            if (conversationId in deletingConversations) return@withLock false

            val wasDeleted = conversationId in deletedConversations
            if (!wasDeleted && conversationRepo.existsConversationById(conversationId)) {
                return@withLock false
            }
            if (!deletingConversations.add(conversationId)) return@withLock false

            try {
                sessions.remove(conversationId)?.let { staleSession ->
                    staleSession.cleanup()
                    _sessionsVersion.value++
                }
                val oldSessionIsDetached = sessions[conversationId] == null

                val alreadyRestored = conversationRepo.existsConversationById(conversationId)
                if (!alreadyRestored) {
                    conversationRepo.insertConversation(conversation) {
                        conversationId in deletingConversations &&
                            (wasDeleted || conversationId !in deletedConversations)
                    }
                }

                // Expose the restored UUID only after the row is durable and every old lease is detached.
                check(
                    canPublishRestoredConversation(
                        rowIsDurable = alreadyRestored || conversationRepo.existsConversationById(conversationId),
                        oldSessionIsDetached = oldSessionIsDetached,
                    )
                ) { "Restored conversation lifecycle is not durable: $conversationId" }
                deletedConversations.remove(conversationId)
                !alreadyRestored
            } catch (error: Throwable) {
                if (wasDeleted) deletedConversations.add(conversationId)
                throw error
            } finally {
                deletingConversations.remove(conversationId)
            }
        }
    }

    // ---- 翻译消息 ----

    /**
     * Deletes only after the active generation has cooperatively stopped. A false result means
     * cancellation did not converge within the bounded wait and no persistent data was removed.
     */
    suspend fun deleteConversation(conversationId: Uuid): Boolean {
        agentRunRepository.awaitStartupRecovery()
        val lock = generationLocks.computeIfAbsent(conversationId) { Mutex() }
        var cancellationJob: Job? = null
        var conversationSnapshot: Conversation? = null
        lock.withLock {
            if (!deletingConversations.add(conversationId)) return false
            val session = sessions[conversationId]
            conversationSnapshot = session?.state?.value ?: conversationRepo.getConversationById(conversationId)
            cancellationJob = session?.cancelCurrentJob()
        }

        return try {
            val stopped = withTimeoutOrNull(DEFAULT_CANCELLATION_FINALIZE_TIMEOUT_MILLIS) {
                cancellationJob?.join()
                true
            } == true
            if (!stopped) return false

            withContext(NonCancellable) {
                lock.withLock {
                    agentRunRepository.getActiveRun(conversationId.toString())?.let { activeRun ->
                        agentRunRepository.cancelRun(activeRun.id)
                    }
                    frozenRunExecutionContexts.values
                        .filter { it.identity.conversationId == conversationId.toString() }
                        .forEach { removeFrozenRunExecutionContext(it.runId, it) }

                    sessions.remove(conversationId)?.cleanup()
                    _sessionsVersion.value++
                    val conversation = conversationRepo.getConversationById(conversationId) ?: conversationSnapshot
                    deletedConversations.add(conversationId)
                    try {
                        if (conversation != null) conversationRepo.deleteConversation(conversation)
                    } catch (error: Throwable) {
                        // The DB delete is transactional, while file cleanup follows it. Reopen this id
                        // only when the conversation row still exists; otherwise late writers stay fenced.
                        if (conversationRepo.existsConversationById(conversationId)) {
                            deletedConversations.remove(conversationId)
                            throw error
                        }
                        // The durable row is already gone. Keep the tombstone and finish the UI/notification
                        // lifecycle even when best-effort FTS, file, or artifact cleanup reports a failure.
                        Log.w(TAG, "Post-delete cleanup failed for conversation=$conversationId", error)
                    }
                }
                appEventBus.emit(AppEvent.ChatGenerationDeleted(conversationId))
            }
            true
        } finally {
            deletingConversations.remove(conversationId)
        }
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid): Boolean {
        val conversations = conversationRepo.getConversationsOfAssistant(assistantId).first()
        return conversations.all { conversation -> deleteConversation(conversation.id) }
    }

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
                if (conversationId !in deletingConversations && conversationId !in deletedConversations) {
                    // Clear translation field only while this conversation lifecycle still exists.
                    clearTranslationField(conversationId, message.id)
                    addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
                }
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
        // Cancel promptly, then serialize terminal persistence and presentation with all generation writes.
        val targetLease = sessions[conversationId]?.leaseForRun(runId)
        targetLease?.job?.cancel()
        val finalized = withTimeoutOrNull(DEFAULT_CANCELLATION_FINALIZE_TIMEOUT_MILLIS) {
            generationLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                val run = agentRunRepository.getRun(runId) ?: return@withLock true
                if (run.conversationId != conversationId.toString()) return@withLock true
                // The request is bound to the Run identity, so it must also stop a continuation installed
                // after a WAITING_APPROVAL card was tapped. A different replacement Run is never selected.
                val currentSession = sessions[conversationId]
                val currentLease = currentSession?.leaseForRun(runId)
                currentLease?.job?.cancel()
                if (
                    currentSession != null &&
                    currentLease != null &&
                    frozenContextOrNull(frozenRunExecutionContexts, runId) != null
                ) {
                    if (finalizeCancelledGenerationLocked(conversationId, currentSession, currentLease, runId)) {
                        return@withLock true
                    }
                    // Job completion may release the lease between the read above and finalization.
                    // Re-read durable/session state and use the unowned-run cleanup path instead of
                    // treating that expected ownership race as an invariant violation.
                }

                val refreshedRun = agentRunRepository.getRun(runId) ?: return@withLock true
                if (refreshedRun.conversationId != conversationId.toString()) return@withLock true
                val refreshedSession = sessions[conversationId]
                val refreshedLease = refreshedSession?.leaseForRun(runId)
                val wasActive = refreshedRun.status in AgentRunStatus.ACTIVE.map(AgentRunStatus::name)
                if (wasActive || targetLease != null || currentLease != null || refreshedLease != null) {
                    val frozenContext = frozenContextOrNull(frozenRunExecutionContexts, runId)
                    val currentOwnerRunId = refreshedSession?.currentRunId()
                    val reasoningScope = if (currentOwnerRunId != null && currentOwnerRunId != runId) {
                        frozenContext?.presentedMessageIds?.toSet().orEmpty()
                    } else {
                        null
                    }
                    val finishedConversation = finishInterruptedRunPresentationLocked(
                        conversationId,
                        runId,
                        reasoningScope,
                    )
                    if (refreshedLease != null) refreshedSession?.processingStatus?.value = null
                    if (wasActive) agentRunRepository.cancelRun(runId)
                    if (frozenContext != null && finishedConversation != null) {
                        scheduleRunEnded(
                            frozenContext,
                            finishedConversation,
                            includeContentPreview = false,
                        )
                    }
                    removeFrozenRunExecutionContext(runId)
                }
                true
            }
        }
        check(finalized == true) { "Stop finalization timed out runId=$runId" }
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
