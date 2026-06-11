package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.contextTokens
import me.rerere.ai.core.resolveReserveOutput
import me.rerere.ai.core.shouldAutoCompact
import me.rerere.ai.core.tokenPressure
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry.getContextWindowForModel
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.automation.act.AlwaysDeny
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.rikkahub.data.ai.runtime.AppToolCatalog
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.buildSpawnTool
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.getUiAutomationTools
import me.rerere.rikkahub.service.automation.AutomationActivationTracker
import me.rerere.rikkahub.service.automation.AutomationKillSwitch
import me.rerere.rikkahub.service.automation.AutomationRuntimeRegistry
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.KnowledgeContextTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
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
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.sanitizeForUpload
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.ai.runtime.memory.MEMORY_RECALL_K
import me.rerere.rikkahub.data.ai.memory.MemoryRecaller
import me.rerere.rikkahub.data.ai.memory.resolveMemoryRecallScope
import me.rerere.rikkahub.service.generation.AndroidGenerationForegroundController
import me.rerere.rikkahub.service.generation.GenerationForegroundCoordinator
import me.rerere.rikkahub.service.mutation.ConversationMutations
import me.rerere.rikkahub.service.notification.ChatNotificationSender
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.common.text.applyPlaceholders
import me.rerere.rikkahub.utils.shouldRethrowVmError
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

// WakeLock 续期节流间隔：远小于服务侧 15 分钟超时，确保连续生成时锁在超时前总能被刷新；又远大于
// 单 token 间隔，避免逐 token IPC。
internal const val WAKE_LOCK_RENEW_INTERVAL_MS = 60L * 1000L

// UI-automation lease bounds (#187 v1, design I5). A per-conversation grant is time-boxed and
// step-capped so a session expires on its own (primary recovery) even if no STOP is pressed.
private const val UI_AUTOMATION_LEASE_TTL_MS = 30L * 60L * 1000L // 30 min per task
private const val UI_AUTOMATION_MAX_STEPS = 256 // ADMITs over the lease (P22)

// 自动压缩保留的最近消息数：与手动压缩对话框（CompressContextDialog）的默认值一致。
private const val AUTO_COMPACT_KEEP_RECENT_MESSAGES = 32

// 自动压缩的目标摘要 token 数：与手动压缩对话框提供的中档默认一致。
private const val AUTO_COMPACT_TARGET_TOKENS = 2000

// 自动压缩熔断阈值（design #193 R5）：同一会话连续失败达到此次数后，停止再尝试自动压缩，避免在
// 不可逆超限的会话上每轮发起注定失败的压缩调用。值 3 与成熟实现一致。
internal const val MAX_AUTO_COMPACT_FAILURES = 3

// 聊天错误列表上限：反复的 provider 失败（如断流重试、鉴权失败循环）会不断调用 addError，若无上限
// _errors 会无界增长并常驻内存。保留最近 50 条足以让用户看到当前问题。
internal const val MAX_CHAT_ERRORS = 50

/**
 * 纯函数：给定上次续期时刻与当前时刻，判断是否到达续期间隔。抽出以便 JVM 单测（无 Android 依赖）。
 * [lastRenewAt] 为 0 表示尚未续期过——首个 chunk 即续期，使长任务的计时从首帧开始。
 */
internal fun shouldRenewWakeLock(
    lastRenewAt: Long,
    now: Long,
    intervalMs: Long = WAKE_LOCK_RENEW_INTERVAL_MS,
): Boolean = now - lastRenewAt >= intervalMs

// 流式 UI 状态合并窗口：约两帧（60Hz）。远大于单 token 间隔（合并掉逐 token 的整 Conversation
// StateFlow 重写，避免聊天页每 token 全量重组），又小到足以保持"实时"观感。落在 issue #108 建议的
// 16-50ms 区间内。仅决定"何时"把已合并好的状态推给 UI，绝不改变合并出的规范状态本身——最终值总会被强制刷新。
internal const val STREAMING_UI_COALESCE_INTERVAL_MS = 32L

/**
 * 纯函数：给定上次发布时刻与当前时刻，判断是否应把流式合并状态写入 UI StateFlow。抽出以便 JVM 单测
 * （无 Android/协程依赖），与 [shouldRenewWakeLock] 同构。
 * [lastPublishAt] 为 0 表示尚未发布过——首个 chunk 立即发布，使首 token 无启动延迟地显示。
 */
internal fun shouldPublishStreamingUpdate(
    lastPublishAt: Long,
    now: Long,
    intervalMs: Long = STREAMING_UI_COALESCE_INTERVAL_MS,
): Boolean = now - lastPublishAt >= intervalMs

/**
 * 纯函数：流终止时是否需要强制刷新最后一次合并的 chunk。
 *
 * 仅当存在已记住的最后一帧（[hasLastMessages]）且它尚未被节流窗口发布出去（[lastChunkPublished] 为
 * false）时才需刷新——否则末帧已经在 UI StateFlow 里，再写一次纯属重复。慢流（每帧都越过窗口）的末帧
 * 已发布，返回 false，不产生重复写。
 */
internal fun shouldFlushFinalStreamingUpdate(
    hasLastMessages: Boolean,
    lastChunkPublished: Boolean,
): Boolean = hasLastMessages && !lastChunkPublished

/**
 * 流式 UI 发布的合并器：把 #108 的"何时把合并状态写给 UI"决策（逐 chunk 窗口节流 + 终止强制刷新）
 * 收拢成一个纯状态机，由生产（[ChatService.handleMessageComplete]）与 JVM 单测共同驱动——publish 副作用
 * 由调用方注入（生产写 StateFlow，测试记录到 list）。把这层逻辑从 collect 闭包里抽出来，使得：
 *  - 删掉生产对 [finish] 的调用 → 末帧不再刷新 → 直接驱动本类的单测变红；
 *  - 把 [onChunk] 改成无条件 publish（绕过节流）→ 合并语义改变 → 单测变红。
 * 这样回归测试真正守住生产接线，而不是测一份手抄的 collect 循环。
 *
 * [now] 由调用方传入（生产 = System.currentTimeMillis()，测试 = 注入的时钟），保持纯函数可单测。
 */
internal class StreamingUiCoalescer<T>(
    private val intervalMs: Long = STREAMING_UI_COALESCE_INTERVAL_MS,
    private val publish: (T) -> Unit,
) {
    private var lastPublishAt = 0L
    private var lastValue: T? = null
    private var hasValue = false
    private var lastChunkPublished = false

    /** 处理一个 chunk：记住其合并值，并按窗口节流决定是否立即 publish。 */
    fun onChunk(now: Long, value: T) {
        lastValue = value
        hasValue = true
        if (shouldPublishStreamingUpdate(lastPublishAt, now, intervalMs)) {
            lastPublishAt = now
            lastChunkPublished = true
            publish(value)
        } else {
            lastChunkPublished = false
        }
    }

    /** 流终止时强制刷新被节流窗口丢弃的末帧（若有且未发布）。任何终止路径都必须调用。 */
    fun finish() {
        if (shouldFlushFinalStreamingUpdate(hasValue, lastChunkPublished)) {
            @Suppress("UNCHECKED_CAST")
            publish(lastValue as T)
        }
    }

    /**
     * 把 [source] 装饰成一条"逐 chunk 节流 publish + 终止强制刷新末帧"的流：每个元素先跑 [sideEffect]
     * （WakeLock 续期、Live Update 通知等与 UI 发布解耦的副作用），再 [onChunk]（按窗口节流 publish）；
     * 并在**任何**终止路径（正常完成/取消/异常）的 onCompletion 里调 [finish] 强制刷新末帧。返回装饰后的
     * 流（不 collect），让调用方在其下游再链自己的 onCompletion——本 onCompletion 在上游、故 [finish] 先于
     * 下游的定型/持久化执行，保证它们读到已刷新的精确最终状态。
     *
     * 这是 #108 的接线点：onChunk（节流）+ onCompletion(finish)（终止刷新）都封在这一处，生产与 JVM 单测
     * 共用同一逻辑。删掉这里的 finish 调用 → 测试 collect 这条流时末帧不再刷新 → 单测变红（不再是手抄
     * collect 循环那种假阳性）。
     */
    fun coalesce(
        source: Flow<T>,
        now: () -> Long = { System.currentTimeMillis() },
        sideEffect: suspend (T) -> Unit = {},
    ): Flow<T> = source
        .onEach { value ->
            sideEffect(value)
            onChunk(now(), value)
        }
        .onCompletion { finish() }
}

/**
 * 纯函数：把 [new] 追加到错误列表并裁掉最旧的，保证列表至多 [max] 条。抽出以便 JVM 单测（无 Android
 * 依赖）。用 takeLast：新错误总被保留，溢出时丢弃的是最旧的那条（有界 FIFO，最近优先）。
 */
internal fun appendChatError(
    current: List<ChatError>,
    new: ChatError,
    max: Int = MAX_CHAT_ERRORS,
): List<ChatError> = (current + new).takeLast(max)

/** Terminal outcome of one auto-compact attempt, for the per-session circuit breaker (design #193 R5). */
internal enum class AutoCompactOutcome { SUCCESS, FAILURE, CANCELLATION }

/**
 * 纯函数：熔断器是否已跳闸。抽出以便 JVM 单测（无 Android 依赖）。失败计数达到 [max] 即跳闸——此后
 * [maybeAutoCompact] 整体跳过，直到一次成功把计数清零（CB2）。
 */
internal fun autoCompactBreakerTripped(
    failures: Int,
    max: Int = MAX_AUTO_COMPACT_FAILURES,
): Boolean = failures >= max

/**
 * 纯函数：给定当前连续失败计数与本次压缩的终态，返回新的计数。抽出以便 JVM 单测（无 Android/协程依赖），
 * 把熔断器的状态转移与 [maybeAutoCompact] 的副作用（压缩调用、错误上报）解耦，使 CB1-CB3 可单测且测的是
 * 生产真正使用的转移逻辑（改坏其中任一分支即令单测变红）。
 *
 * - [AutoCompactOutcome.SUCCESS]：清零（CB1）。
 * - [AutoCompactOutcome.FAILURE]：自增（CB1）——同一会话连续失败累加。
 * - [AutoCompactOutcome.CANCELLATION]：保持不变（CB3）——用户取消不是失败，绝不计入。
 */
internal fun nextAutoCompactFailureCount(
    current: Int,
    outcome: AutoCompactOutcome,
): Int = when (outcome) {
    AutoCompactOutcome.SUCCESS -> 0
    AutoCompactOutcome.FAILURE -> current + 1
    AutoCompactOutcome.CANCELLATION -> current
}

/**
 * 纯函数：把一条「保留」消息的 [TokenUsage] 中**唯一**因压缩而失真的字段——压力锚 [TokenUsage.totalTokens]
 * ——置零，其余三项（prompt/completion/cached）原样保留。抽出以便 JVM 单测（无 Android/协程依赖）。
 *
 * 为什么只清 totalTokens：压缩把被保留消息之前的历史折成摘要后，这些消息记录的 totalTokens 反映的是一个
 * **包含已被摘要前缀**的旧请求大小，已偏高且失真；而 contextTokens 的锚谓词正是 `totalTokens > 0`
 * （[me.rerere.ai.core.contextTokens]），故把它置零即让锚跳过该消息、回落到对压缩后较小历史的保守估算，
 * 避免 token 触发/尺寸告警每回合误触（并在约三回合内拉闸熔断）。
 *
 * 为什么**不**清整个 usage：prompt/completion/cachedTokens 是该消息真实的逐条统计，并非失真的锚——
 * 它们被 [me.rerere.rikkahub.data.db.dao.getTokenStats] 的终身聚合（直接 SUM 持久化 JSON 里的
 * `$.usage.promptTokens/completionTokens/cachedTokens`）与 nerd 行逐条读取。压缩会 saveConversation 把
 * 结果写回 Room，故置 null 会**永久**抹掉这些 token、不可还原；只清锚则两者均不受损。这是设计 #193 Stage-1
 * 对失真测量锚的最小化诚实失效，非 summarizer 行为变更（仍是 Stage-2 非目标）。
 */
internal fun invalidateStalePressureAnchor(usage: TokenUsage?): TokenUsage? =
    usage?.copy(totalTokens = 0)

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
        OcrTransformer,
        // Single message-surface knowledge assembly point (issue #141): replaces both
        // DocumentAsPromptTransformer and KnowledgeRetrievalTransformer. Placed AFTER OcrTransformer
        // so RAG's query basis stays identical to today (post-OCR text); attachments are Document
        // parts OcrTransformer ignores, so document injection is unaffected by the position.
        KnowledgeContextTransformer,
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
    private val workspaceRepository: WorkspaceRepository,
    private val memoryRecaller: MemoryRecaller,
    private val generationHandler: GenerationHandler,
    private val subagentRunner: SubagentRunner,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    // On-device UI automation (#187 v1, read-only). Registry hands out the live, system-instantiated
    // AccessibilityRuntime as a pure backend; the kill-switch dispatches STOP to the active guard(s).
    private val automationRegistry: AutomationRuntimeRegistry,
    private val automationKillSwitch: AutomationKillSwitch,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 活跃生成 -> 前台服务的生命周期状态机。引用计数（0->1 启动、1->0 停止）、前台服务运行标志、
    // WakeLock 续期节流统一由协调器独占持有；ChatService 只委派，不再直接调用 GenerationForegroundService。
    private val foregroundCoordinator = GenerationForegroundCoordinator(AndroidGenerationForegroundController(context))

    // 通知发送器：封装"生成完成"与"Live Update"通知的构建，不持有会话状态（内容由调用方传入）。
    private val notificationSender = ChatNotificationSender(context)

    // UI-automation lease clock (#187). A real wall-clock; the kernel injects it instead of reading
    // System.now directly so lease/TTL behaviour is reproducible in the :automation PBT suite.
    private val trustClock = TrustClock { System.currentTimeMillis() }

    // Refcounts the conversations with a live automation lease and owns the single STOP overlay
    // (#187 §7). The overlay is process-global but the leases are per-conversation: toggling it on
    // a per-completion boolean removed the any-app kill-switch for a still-active concurrent session.
    // Show on the 0→1 edge, hide on 1→0; showOverlay reports reachability so the caller fails closed.
    private val automationActivation = AutomationActivationTracker(
        showOverlay = { automationRegistry.showKillSwitch(onStop = { automationKillSwitch.trip() }) },
        hideOverlay = { automationRegistry.hideKillSwitch() },
    )

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
            appendChatError(it, ChatError(title = title, error = error, conversationId = conversationId, solution = solution))
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

        // Kill-switch (#187 design §7/I9): the floating STOP overlay (reachable from any app) trips
        // this — revoke EVERY active automation grant (future authorize ⇒ DENY) and cancel its
        // generation job. Cancelling the job tears down that session's in-flight capture by
        // structured concurrency (the capture is a child of the generation coroutine), so this is
        // the global kill-switch that legitimately stops ALL automation sessions at once.
        automationKillSwitch.register {
            sessions.values.forEach { session ->
                if (session.activeAutomationGuard != null) {
                    session.revokeAutomation()
                    session.getJob()?.cancel()
                }
            }
        }
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
                onIdle = { removeSession(it) },
                onGenerationStart = { foregroundCoordinator.onGenerationStart() },
                onGenerationStop = { foregroundCoordinator.onGenerationStop() },
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

    // 不在这里 catch/降级：本流是对一组 in-memory StateFlow（每个 session 的 generationJob，热流、
    // 永不完成、永不抛）做 combine，没有可重试的瞬时故障。下游对错误的诉求各不相同，故把错误边界下放到
    // 各消费者本身——ChatVM 的 stateIn 收集器需在异常下存活（见 #92，catch 置于 stateIn 之前）；
    // web 的 .first()/SSE 则应让真实异常以 HTTP 500 上抛，而非被静默改写成“无活跃任务”的 200。
    fun getConversationJobs(): Flow<Map<Uuid, Job?>> =
        assembleConversationJobsFlow(
            version = _sessionsVersion,
            sessionsSnapshot = { sessions.values.toList().map { it.id to it.generationJob } }
        )

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversationWithFileCleanup(conversationId, conversation)
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
            updateConversationWithFileCleanup(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

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

                // 在生成下一次回复前，若达到阈值则自动压缩历史（仅限本 sendMessage 路径；
                // regenerate / 工具审批续跑路径不在本次范围内）。判定抽成纯函数 [shouldAutoCompact]
                // 以便 JVM 单测。压缩失败时降级——把错误上报后仍按未压缩的历史继续发送，绝不因压缩失败
                // 阻断用户消息，也绝不静默吞掉异常。
                maybeAutoCompact(conversationId, assistant)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                // 用户主动停止（stopGeneration 取消本 job）是协作式取消，不是“发送失败”：CancellationException
                // 必须重新抛出让取消沿结构化并发向上传播，绝不被当作普通异常吞掉。旧代码 catch(Exception) 捕获了
                // 它却不重抛、落到 addError（addError 虽对取消早退、不上报，但本 job 会“正常完成”而非传播取消，破坏
                // 结构化并发）。maybeAutoCompact 在取消路径重新抛出的 CancellationException 同样经此重抛传播。
                // 复用全局分类器 shouldRethrowVmError（与 launchVm/runEmitting 同一约定）；仅捕获 Exception，保留
                // 原 Error 不被本块捕获的语义。
                if (shouldRethrowVmError(e)) throw e
                Log.e(TAG, "sendMessage: generation pipeline failed", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    // 自动压缩历史的触发与执行（design #193 Stage 1：token 触发器 + 熔断器）。
    //
    // 触发判定改用真实 token 压力而非消息条数：以最后一条带 usage 的消息的真实 totalTokens 为锚，加上
    // 锚之后（待发送的用户轮）的保守估算（contextTokens），对照按模型上下文窗口推导的软/硬阈值
    // （tokenPressure）。窗口由 getContextWindowForModel 解析（覆盖 -> 注册表族 -> 128k 默认）。
    //
    // 熔断器（R5，本阶段真正修复的可用性 bug）：一旦某会话不可逆超限，token 触发器会每轮触发压缩，而每次
    // 压缩本身又是一次注定失败的模型调用。连续非取消失败累加到 [MAX_AUTO_COMPACT_FAILURES] 后整体跳过；
    // 成功清零（CB1）；用户取消（CancellationException）不计入、不上报（CB3）。
    //
    // 失败时不抛——上报错误并按未压缩历史继续（降级而非阻断），绝不因压缩失败阻断用户消息，也绝不静默吞
    // 异常。compressConversation 直接调用 provider（不经 agentic loop / 不回到本函数），故不存在“压缩中再触发
    // 压缩”的递归；熔断器进一步给任何未来重构兜底。
    private suspend fun maybeAutoCompact(conversationId: Uuid, assistant: Assistant) {
        val session = getOrCreateSession(conversationId)
        // 熔断跳过优先于一切（CB2：trip 后不论压力如何都不压缩，直到一次成功清零）。
        if (autoCompactBreakerTripped(session.consecutiveAutoCompactFailures)) return

        if (!assistant.autoCompactEnabled) return

        val conversation = session.state.value
        val settings = settingsStore.settingsFlow.first()
        // 即将接收下一次请求的对话模型——其窗口才是触发判定的依据（压缩模型可能不同，与触发无关）。
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val window = getContextWindowForModel(model)
        val messages = conversation.currentMessages
        val pressure = tokenPressure(
            contextTokens = contextTokens(messages),
            window = window,
            thresholdFraction = assistant.autoCompactThreshold,
            reserveOutput = resolveReserveOutput(assistant.maxTokens),
        )
        val shouldCompact = shouldAutoCompact(
            enabled = true, // 已在上方早退
            // 仍有可压缩历史：等于或小于 keepRecent 时无内容可压，compressConversation 会抛“消息不足”。
            hasCompressibleHistory = messages.size > AUTO_COMPACT_KEEP_RECENT_MESSAGES,
            breakerTripped = false, // 已在上方早退
            pressure = pressure,
        )
        if (!shouldCompact) return

        compressConversation(
            conversationId = conversationId,
            conversation = conversation,
            additionalPrompt = "",
            targetTokens = AUTO_COMPACT_TARGET_TOKENS,
            keepRecentMessages = AUTO_COMPACT_KEEP_RECENT_MESSAGES,
        ).onSuccess {
            session.consecutiveAutoCompactFailures =
                nextAutoCompactFailureCount(session.consecutiveAutoCompactFailures, AutoCompactOutcome.SUCCESS)
        }.onFailure {
            // CB3：用户取消不是失败——计数保持不变（neutral），且必须重新抛出，让取消协作式向上传播。
            // compressConversation 用 runCatching 把异常（含 CancellationException）收成 Result.failure，
            // 若这里只是“分类后吞掉”，stopGeneration 触发的取消就被静默吃掉、结构化并发被破坏。
            if (it is CancellationException) {
                session.consecutiveAutoCompactFailures = nextAutoCompactFailureCount(
                    session.consecutiveAutoCompactFailures, AutoCompactOutcome.CANCELLATION
                )
                throw it
            }
            session.consecutiveAutoCompactFailures = nextAutoCompactFailureCount(
                session.consecutiveAutoCompactFailures, AutoCompactOutcome.FAILURE
            )
            Log.e(TAG, "maybeAutoCompact: history compaction failed", it)
            addError(it, conversationId, title = context.getString(R.string.error_title_auto_compact))
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
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
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
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // UI automation (#187 v1) per-conversation capability lease (issue #244, god-function split #2b).
    // Unifies the three coupled lifecycle obligations that were scattered across the generation:
    //   1. mint ONE CapabilityGuard for this generation when the assistant has automation enabled
    //      (gate finding 11 / S1 — bound to THIS conversation, not a standing per-assistant grant),
    //      and register it on the session;
    //   2. fail closed (design I9/§7): activate the refcounted STOP overlay; if no kill-switch is
    //      reachable (a11y service not connected, or addView failed), revoke the just-minted guard and
    //      throw so generation aborts with a surfaced error — never expose ui_observe without a STOP;
    //   3. release the lease + overlay on EVERY terminal path in the finally — normal finish, error,
    //      cancellation, OR a throw while assembling generateText's eager arguments (memory/skill/MCP
    //      lookups are eager, BEFORE the flow's onCompletion is attached). So [block] MUST wrap the
    //      eager-arg assembly (the AppToolCatalog tool pool + memory recall) too, which onCompletion
    //      alone would miss — leaking the STOP overlay forever.
    //
    // BOTH the guard-clear AND the overlay deactivate are identity-guarded on
    // `activeAutomationGuard === guard`. The activation tracker is keyed by conversationId, so for a
    // cancel-relaunch on the SAME conversation (regenerate / tool-approval cancel an in-flight gen,
    // then re-enter handleMessageComplete WITHOUT sendMessage's previousJob.join() barrier) the old
    // gen's late finally would otherwise deactivate(conversationId) — emptying the refcount and hiding
    // the floating STOP — while the new gen's guard is already live and observing a foreign app.
    // Gating the deactivate on identity means the superseded generation (whose guard is no longer the
    // session's active one) touches neither the guard slot nor the overlay; the live generation owns
    // the 1→0 deactivate when IT terminates. (sendMessage's join() makes the old finally run before
    // the new activate, so identity holds there too.)
    //
    // [inline] so a non-local return/throw out of [block] (incl. CancellationException and the
    // automation_kill_switch_unavailable throw) propagates exactly as it did inline.
    private inline fun <R> withAutomationLease(
        conversationId: Uuid,
        assistant: Assistant,
        session: ConversationSession,
        block: (CapabilityGuard?) -> R,
    ): R {
        val automationGuard: CapabilityGuard? = if (assistant.uiAutomationEnabled) {
            CapabilityGuard(
                capability = Capability.root(
                    sessionId = conversationId.toString(),
                    // Surface stays empty-by-default = deny-all (S1): a per-app whitelist is a
                    // separate later UI. This grant makes the nav verbs, the input sink, and the
                    // general tap (Verb.TAP, #198 slice 10) AUTHORIZABLE but does NOT widen the
                    // admitted surface — authorize still DENYs on the surface branch for any real
                    // foreground app today, exactly as OBSERVE does.
                    surface = emptySet(),
                    verbs = setOf(Verb.OBSERVE, Verb.SCROLL, Verb.GLOBAL, Verb.SET_TEXT, Verb.TAP),
                    // GLOBAL_NAV must be in budget for ui_global's authorize to pass the
                    // sink-in-budget branch; TYPE_INTO for ui_set_text (#198 slice 9, the input
                    // sink). ui_scroll and ui_tap (#198 slice 10) carry NO sink for an ordinary tap
                    // (the SCROLL/TAP verb suffices). Sink.SUBMIT is INTENTIONALLY WITHHELD from this
                    // default lease (#198 slice 11, the conservative default): a submit-class
                    // (send/pay/checkout) tap derives SUBMIT in core.act, and with SUBMIT not in
                    // budget the guard DENYs it at the sink-in-budget branch BEFORE the confirm gate
                    // is even reached. So the confirm gate is fully wired and proven but un-reachable
                    // through this default lease — submit-class automation is a separate, stricter,
                    // explicit opt-in (a later grant that adds SUBMIT to the budget), exactly mirroring
                    // how slices 8-10 made verbs AUTHORIZABLE without widening the admitted surface.
                    // Surface stays empty = deny-all (S1): these grants make the verbs/sinks
                    // AUTHORIZABLE but do NOT widen the admitted surface — authorize still DENYs on
                    // surface for any real foreground app today.
                    sinkBudget = setOf(Sink.GLOBAL_NAV, Sink.TYPE_INTO),
                    lease = Lease(
                        expiresAt = trustClock.now() + UI_AUTOMATION_LEASE_TTL_MS,
                        maxSteps = UI_AUTOMATION_MAX_STEPS,
                    ),
                ),
                clock = trustClock,
            ).also { guard -> session.activeAutomationGuard = guard }
        } else {
            null
        }
        if (automationGuard != null && !automationActivation.activate(conversationId)) {
            if (session.activeAutomationGuard === automationGuard) session.activeAutomationGuard = null
            automationGuard.revoke()
            throw IllegalStateException(context.getString(R.string.automation_kill_switch_unavailable))
        }
        try {
            return block(automationGuard)
        } finally {
            if (automationGuard != null && session.activeAutomationGuard === automationGuard) {
                session.activeAutomationGuard = null
                automationActivation.deactivate(conversationId)
            }
        }
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversationStateOnly(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)

            // UI automation (#187 v1): the per-conversation capability lease + refcounted STOP
            // overlay lifecycle (mint guard → fail-closed activate → identity-guarded release on
            // EVERY terminal path, incl. a throw while assembling generateText's eager arguments) is
            // unified in withAutomationLease. block() must cover the eager-arg assembly
            // (the AppToolCatalog tool pool + memory recall) too, because those run BEFORE the flow's
            // onCompletion is attached — onCompletion alone would miss them and leak the overlay.
            withAutomationLease(conversationId, assistant, session) { automationGuard ->

            // Tool assembly is delegated to the neutral [ToolCatalog] port (issue #243 slice 10): the
            // per-generation [AppToolCatalog] reproduces the former buildGenerationTools body via its
            // four seams, wired here off the in-scope app `assistant`/`settings` + this generation's
            // `automationGuard` and the session's processingStatus (the spawn-status carrier). The main
            // turn invokes it as TurnMode.Main with the spawn tool included only when some assistant is
            // spawnable — exactly the pre-slice main-pool semantics.
            val appToolCatalog = AppToolCatalog(
                // Non-MCP/non-spawn base pool: search/local/workspace/ui-automation/skills. The seam's
                // (target, mode) args are unused on the main turn — production only ever invokes this
                // catalog with targetAssistant == assistant.toAssistantConfig(), so the in-scope app
                // `assistant` is the faithful source (no lossy AssistantConfig round-trip).
                baseTools = { _, _ ->
                    buildList {
                        if (settings.enableWebSearch) {
                            addAll(createSearchTools(settings))
                        }
                        addAll(localTools.getTools(assistant.localTools))
                        addAll(createWorkspaceTools(assistant.workspaceId?.toString(), workspaceRepository))
                        // ui_observe (#187 v1) + nav act verbs ui_scroll/ui_global (#198 slice 8) +
                        // ui_set_text (slice 9) + ui_tap (slice 10), all over the same core. Empty surface
                        // unless automation is enabled AND a guard was minted; each tool authorizes via the
                        // closed-over guard BEFORE the backend (S2). No-op (empty) when disabled or when the
                        // a11y service is not connected (the core() is null ⇒ no guard path is reachable).
                        automationRegistry.core()?.let { automationCore ->
                            addAll(
                                getUiAutomationTools(
                                    assistant = assistant,
                                    guard = automationGuard,
                                    core = automationCore,
                                    foregroundPkg = { automationRegistry.foregroundPackage() },
                                    // The out-of-band confirm for a dangerous (submit-class) tap (#198 slice
                                    // 11). Fail closed: if no overlay-backed channel is reachable (a11y
                                    // service not connected), a dangerous sink can never be confirmed, so it
                                    // is always denied — never silently auto-confirmed.
                                    confirm = automationRegistry.confirmChannel() ?: AlwaysDeny,
                                )
                            )
                        }
                        if (assistant.enabledSkills.isNotEmpty()) {
                            addAll(
                                createSkillTools(
                                    enabledSkills = assistant.enabledSkills,
                                    allSkills = skillManager.listSkills(),
                                    skillManager = skillManager,
                                )
                            )
                        }
                    }
                },
                // MCP tools are selected off the TARGET assistant; on the main turn that is the in-scope
                // app `assistant` (the §C3 by-target invariant), named `mcp__…` by the catalog's mapMcpTool.
                mcpToolsForAssistant = { _ -> mcpManager.getAllAvailableTools(assistant) },
                mcpCall = { sid, name, args -> mcpManager.callTool(sid, name, args) },
                // Subagent spawn tool (issue #201). The catalog adds it only on a Main turn with
                // includeSpawnTool true; the sub's own pool is built off the TARGET (sub) assistant's
                // allowlist (local + skills + MCP keyed off `sub`), never containing the spawn tool —
                // the structural recursion guard. parentModelId is the parent (main) assistant's model,
                // passed by the catalog so an unpinned subagent inherits it via resolveSubagentModel.
                spawnTool = { parentModelId ->
                    val spawnableAssistants = settings.assistants.filter { it.spawnable }
                    if (spawnableAssistants.isEmpty()) {
                        null
                    } else {
                        buildSpawnTool(
                            spawnableAssistants = spawnableAssistants,
                            runner = subagentRunner,
                            parentModelId = parentModelId,
                            settings = settings,
                            buildSubagentTools = { sub ->
                                buildList {
                                    addAll(localTools.getTools(sub.localTools))
                                    if (sub.enabledSkills.isNotEmpty()) {
                                        addAll(
                                            createSkillTools(
                                                enabledSkills = sub.enabledSkills,
                                                allSkills = skillManager.listSkills(),
                                                skillManager = skillManager,
                                            )
                                        )
                                    }
                                    mcpManager.getAllAvailableTools(sub).forEach { (serverId, tool) ->
                                        add(mapMcpTool(serverId, tool) { sid, name, args ->
                                            mcpManager.callTool(sid, name, args)
                                        })
                                    }
                                }
                            },
                            processingStatus = session.processingStatus,
                            progressLabel = { subName ->
                                context.getString(R.string.chat_subagent_running, subName)
                            },
                        )
                    }
                },
            )

            // 流式 UI 合并状态：每个 chunk 携带累计全量 messages（节点按 INDEX 合并、节点内消息按 id 匹配，
            // 见 Conversation.updateCurrentMessages）。把"何时把合并状态写给 UI"的窗口节流 + 终止刷新决策收拢进
            // StreamingUiCoalescer（纯状态机，与 JVM 单测共用）。publish 副作用经 publishStreamingMessages 以单次
            // CAS（StateFlow.update）把合并 messages 重新合并进**当时的** live StateFlow（而非读一个陈旧快照再写
            // 回），故一次并发的 UI 写入（收藏切换/编辑/标题等非消息字段）不会被覆盖——updateCurrentMessages 经
            // node.copy 重建节点、保留 isFavorite 等非消息字段，last-writer-wins → idempotent merge-onto-current。
            val uiCoalescer = StreamingUiCoalescer<List<UIMessage>>(
                publish = { messages ->
                    publishStreamingMessages(getOrCreateSession(conversationId).state, messages)
                }
            )
            val effectiveMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            // Relevance recall (issue #210) replaces the historic full memory dump: gated on
            // enableMemory, scoped global-or-assistant, ranked against the last user turn. Skipped ⇒
            // empty (no memories injected) — so memory-off behaviour is unchanged.
            val recalledMemories = resolveMemoryRecallScope(assistant, effectiveMessages)
                ?.let { scope -> memoryRecaller.recall(scope.query, scope.assistantId, MEMORY_RECALL_K) }
                ?: emptyList()
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = effectiveMessages,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                memories = recalledMemories,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = appToolCatalog.tools(
                    ToolAssemblyContext(
                        mode = TurnMode.Main,
                        targetAssistant = assistant.toAssistantConfig(),
                        // The subagent-turn carrier is null on a main turn; the spawn tool's parent
                        // model is the main assistant's own chatModelId, supplied by the catalog.
                        parentModelId = null,
                        // Main turn keeps approval-gated tools (the approval UI is reachable here).
                        allowApprovalTools = true,
                        includeSpawnTool = settings.assistants.any { it.spawnable },
                    )
                ),
            ).let { chunkFlow ->
                // UI 发布生命周期统一交给合并器：把累计全量 chunk.messages 流过 uiCoalescer.coalesce，逐 chunk
                // 节流写 UI，并在任何终止路径强制刷新末帧（finish）。这是 #108 的接线点——节流 + 终止刷新都封在
                // coalesce 里，生产与 JVM 单测共用同一逻辑（删掉其 finish 调用会令单测变红）。coalesce 的
                // onCompletion 在上游、故 finish 先于下面的持久化 onCompletion 执行，保证定型/通知读到刷新后的
                // 精确最终状态。
                uiCoalescer.coalesce(
                    source = chunkFlow.map { chunk ->
                        when (chunk) {
                            is GenerationChunk.Messages -> chunk.messages
                        }
                    },
                    sideEffect = { messages ->
                        // 任何流式进展都重置前台服务的 WakeLock 超时，使长 agentic 循环（工具/MCP/搜索跨多段
                        // 子-120s SSE）在息屏下不会在 15 分钟处误超时停机。按时间节流，避免逐 token IPC。
                        foregroundCoordinator.onStreamingProgress()

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            messages.lastOrNull()?.parts?.let {
                                notificationSender.sendLiveUpdate(conversationId, senderName, it)
                            }
                        }
                    },
                )
            }.onCompletion {
                // UI automation (#187): the per-conversation lease + STOP overlay are released in the
                // outer finally (covers the eager-arg throw before this onCompletion is even
                // attached), not here. The lease itself is also self-expiring (TTL) as a backstop.

                // Live Update 通知的移除由 GenerationForegroundService 在 1->0 边
                // stopForeground(STOP_FOREGROUND_REMOVE) 唯一负责，这里不再 per-conversation 取消，
                // 否则多会话并发时单个会话结束会误删其它会话仍在使用的共享常驻通知。

                // UI 末帧强制刷新由上游 coalesce 的 onCompletion 负责，已先于此处执行——下面读
                // getConversationFlow().value 的定型保存与通知预览都已看到刷新后的精确最终状态。这里只负责与
                // UI 发布无关的持久化与完成通知。

                // 可能被取消了，或者意外结束，兜底更新。
                // 中断的 assistant turn 在这里第一次被定型；必须把清洗后的有效状态
                // 持久化（saveConversation），否则 .onSuccess 没跑时下一次发送会 400。
                // NonCancellable：stopGeneration 会 cancel 本协程，而 saveConversation 的
                // existsConversationById 是可取消挂起点，未包裹时取消路径会在写库前抛
                // CancellationException，定型既不落库也不更新内存。
                withContext(NonCancellable) {
                    val updatedConversation = getConversationFlow(conversationId).value.copy(
                        messageNodes = getConversationFlow(conversationId).value.messageNodes
                            .sanitizeForUpload(),
                        updateAt = Instant.now()
                    )
                    saveConversation(conversationId, updatedConversation)
                }

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    val preview = getConversationFlow(conversationId).value.currentMessages
                        .lastOrNull()?.toText()?.take(50)?.trim() ?: ""
                    notificationSender.sendGenerationDone(conversationId, senderName, preview)
                }
            }.collect { /* 终端消费：所有 chunk 副作用已在 coalesce 内完成 */ }

            }
        }.onFailure {
            // Live Update 通知的移除由 GenerationForegroundService 在 1->0 边负责（见 onCompletion 注释）。
            if (it !is CancellationException) Log.e(TAG, "handleMessageComplete: generation failed", it)
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
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
        updateConversationWithFileCleanup(
            conversationId,
            conversation.copy(messageNodes = conversation.messageNodes.sanitizeForUpload())
        )
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

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
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
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
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
            if (it !is CancellationException) Log.w(TAG, "generateTitle: title generation failed", it)
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
                updateConversationWithFileCleanup(
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
            if (it !is CancellationException) Log.w(TAG, "generateSuggestion: chat suggestion generation failed", it)
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

        // Create new conversation with compressed history as multiple user messages + kept messages.
        //
        // Invalidate ONLY the stale pressure anchor on the kept messages (design #193), not the whole
        // TokenUsage: a kept message's totalTokens recorded the prompt size of a request that INCLUDED
        // the now-summarized prefix, so after this rewrite that total is stale-high. contextTokens()
        // anchors on the last message whose totalTokens > 0, so leaving it would make the token trigger /
        // size warning re-fire every turn on a conversation we just shrank, defeating the very point of
        // compaction (and tripping the circuit breaker in ~3 turns). Zeroing totalTokens lets
        // contextTokens fall back to a conservative estimate of the smaller post-compaction history until
        // the next real generation writes a fresh, accurate usage. See invalidateStalePressureAnchor.
        //
        // The other three fields (prompt/completion/cachedTokens) are NOT anchors — they are this
        // message's true per-message stats. They feed the irreversible lifetime aggregate
        // (getTokenStats SUMs them straight out of the persisted JSON) and the per-message nerd line,
        // and saveConversation below persists this rewrite to Room. Nulling the whole usage (the prior
        // approach) would erase those tokens permanently with no round-trip; invalidating only the
        // anchor preserves them while still skipping the kept message in the trigger.
        //
        // This is a deliberate Stage-1 trade-off (NOT a summarizer change), at the compaction write site
        // because compaction IS the event that makes prior totalTokens stale. The contextTokens-side
        // `totalTokens > 0` anchor only covers all-zero usage, not a stale NON-zero total; making the
        // measurement skip stale anchors generically would require a compaction-boundary marker, an
        // explicit Stage-2 non-goal (design R7: boundary metadata recorded but not consumed in Stage 1).
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(
                messagesToKeep.map {
                    it.copy(usage = invalidateStalePressureAnchor(it.usage)).toMessageNode()
                },
            )
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversationStateOnly(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        getOrCreateSession(conversationId).state.value = conversation
    }

    private fun updateConversationWithFileCleanup(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    // 把 UI 侧的 read-modify-write 折成单次 CAS，使其无法丢失一次并发的流式 publish 或另一处 UI 写入（#108 的第
    // 二个竞态读写口）。CAS 闭包必须无副作用——[update] 在三个调用点都是纯变换（.copy/返回新 Conversation），可在
    // 竞争下安全重跑。保留原 id-guard 语义：变换后 id 与会话不符则不换（早退不写、不清理文件），与旧
    // updateConversationWithFileCleanup 的 id 校验一致。
    //
    // 关键：文件清理这一破坏性副作用必须配对“本次 CAS 真正换入的那一对（old, new）”。这里用 compareAndSet 自旋，
    // 在同一次成功的迭代里同时拿到 old 与本次换入的 new，CAS 成功后才在闭包之外只跑一次 checkFilesDelete。不能用
    // getAndUpdate(原子 old) 再 .value 读 new：那是两次非原子读，跨调度器竞争下别的 writer 会在两步之间把状态换掉，
    // 使 new 与 old 并非相邻转换，差量算错可能误删仍被最终会话引用的文件（悬空 URI / 丢数据）。
    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        casUpdateState(
            state = session.state,
            update = { current ->
                val updated = update(current)
                if (updated.id != conversationId) current else updated
            },
            onTransition = { old, new -> checkFilesDelete(newConversation = new, oldConversation = old) },
        )
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val deleted = removedFileUris(
            oldFiles = oldConversation.files.map { it.toString() },
            newFiles = newConversation.files.map { it.toString() },
        )
        if (deleted.isNotEmpty()) {
            filesManager.deleteChatFiles(deleted.map { it.toUri() })
            Log.w(TAG, "checkFilesDelete: $deleted")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversationWithFileCleanup(conversationId, updatedConversation)

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
        val result = ConversationMutations.updateTranslation(currentConversation, messageId, translationText)
        updateConversationWithFileCleanup(conversationId, result)
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

        // role 占位：ConversationMutations.editMessage 会对每个命中节点用 node.role 重新落款，
        // 这里的 role 仅为构造合法 UIMessage，最终被覆盖（保持原“按节点 role 落款”语义不变）。
        val newMessage = UIMessage(role = MessageRole.USER, parts = processedParts)
        val result = ConversationMutations.editMessage(currentConversation, messageId, newMessage)
            ?: return

        saveConversation(conversationId, result)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val forkConversation = ConversationMutations.forkAtMessage(currentConversation, messageId) {
            it.copyWithForkedFileUrl()
        }

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updated = ConversationMutations.selectMessageNode(currentConversation, nodeId, selectIndex)
        // 无变更（目标索引已是当前选中值）时 helper 返回原实例 —— 据引用相等跳过持久化，保留原早退不落库语义。
        if (updated === currentConversation) {
            return
        }

        saveConversation(conversationId, updated)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = ConversationMutations.deleteMessage(currentConversation, messageId)

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

    private suspend fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        suspend fun copyLocalFileIfNeeded(url: String): String {
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
        val result = ConversationMutations.updateTranslation(currentConversation, messageId, null)
        updateConversationWithFileCleanup(conversationId, result)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        // The in-app Stop is the second kill-switch (#187 §7): revoke THIS conversation's automation
        // grant before cancelling so a tool step that is mid-authorize fails closed. Cancelling the
        // job tears down only this conversation's in-flight capture (the capture is a child of this
        // generation coroutine) — a concurrent automation session is untouched.
        sessions[conversationId]?.revokeAutomation()
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}

/**
 * Pure file-diff decision used by [ChatService.checkFilesDelete]. Returns the file URIs present
 * in [oldFiles] but absent from [newFiles] (i.e. references that a mutation removed).
 *
 * Extracted as a top-level function over plain String URIs so it can be JVM unit-tested:
 * [Conversation.files] returns `List<Uri>` and `android.net.Uri` is unavailable under
 * `unitTests.isReturnDefaultValues = true` (its methods return null/defaults), so the logic
 * cannot be exercised through the Uri-typed path on the JVM.
 */
internal fun removedFileUris(oldFiles: List<String>, newFiles: List<String>): List<String> {
    val newSet = newFiles.toHashSet()
    return oldFiles.filter { it !in newSet }
}

/**
 * Adapts a single MCP [tool] (selected for some server [serverId]) into the AI-SDK [Tool] shape
 * (issue #244, DRY #1). This owns the one invariant the two pools (main-agent + subagent) must not
 * let drift: the `"mcp__"` name prefix and the `callTool(serverId, tool.name, args)` wiring.
 *
 * [callTool] is injected (not the Android/Koin-coupled [McpManager]) so the mapping is a pure,
 * JVM-unit-testable function — mirroring [selectMcpToolsForAssistant]/[callToolWithHeal] in McpManager.kt.
 */
internal fun mapMcpTool(
    serverId: Uuid,
    tool: McpTool,
    callTool: suspend (Uuid, String, JsonObject) -> List<UIMessagePart>,
): Tool = Tool(
    name = "mcp__" + tool.name,
    description = tool.description ?: "",
    parameters = { tool.inputSchema },
    needsApproval = tool.needsApproval,
    execute = { callTool(serverId, tool.name, it.jsonObject) },
)

/**
 * Atomic streaming-UI publish seam (issue #108). Merges the accumulated [messages] into the LIVE
 * conversation as a single compare-and-set ([MutableStateFlow.update]) read-modify-write, replacing the
 * old non-atomic `state.value.updateCurrentMessages(messages)` + `state.value = merged` pair.
 *
 * Why CAS, not read-then-set: the streaming publish (background) and a UI write that flips a non-message
 * field — e.g. the @Transient [MessageNode.isFavorite] via [ChatService.updateConversationState] — mutate
 * the same StateFlow from different dispatchers. With the old pair, a favorite landing between the read
 * and the write was lost (TOCTOU). `update {}` re-applies the merge onto the LATEST value, and
 * [Conversation.updateCurrentMessages] rebuilds each node via `node.copy(messages=..., selectIndex=...)`
 * (preserving isFavorite), so last-writer-wins is idempotent merge-onto-current and the favorite survives.
 *
 * Extracted as a top-level pure function so the lost-update regression is JVM-unit-tested against this
 * exact production wiring: reverting the body to the read-then-`.value=` pair reddens
 * StreamingPublishAtomicityTest.
 */
internal fun publishStreamingMessages(state: MutableStateFlow<Conversation>, messages: List<UIMessage>) {
    state.update { current -> current.updateCurrentMessages(messages) }
}

/**
 * Atomic state read-modify-write that PAIRS a destructive side effect with the exact transition it
 * installed (issue #108).
 *
 * [update] must be side-effect-free: under contention it is re-run on the LATEST value each lost-CAS
 * iteration. [onTransition] runs at most once, AFTER a successful [MutableStateFlow.compareAndSet],
 * with the precise `(old, new)` pair that this call's CAS swapped — `old` is the value the CAS
 * replaced, `new` is the value it installed, and they are guaranteed adjacent.
 *
 * Why not `getAndUpdate { ... }` then a second `state.value` read: that is two non-atomic reads. A
 * cross-dispatcher writer can swap the state between them, so the second read is some LATER value, not
 * the one paired with the `old` from `getAndUpdate`. Diffing that mismatched pair drives
 * [ChatService.checkFilesDelete] to delete bytes the live conversation still references (dangling URI /
 * data loss). Extracted as a top-level generic function so this pairing invariant is JVM-unit-tested
 * against the production wiring rather than a hand-copied mirror, and over a plain [T] so the test need
 * not construct a `Conversation` whose `files` route through `android.net.Uri` (null under unit tests).
 */
internal inline fun <T> casUpdateState(
    state: MutableStateFlow<T>,
    update: (T) -> T,
    onTransition: (old: T, new: T) -> Unit,
) {
    while (true) {
        val old = state.value
        val new = update(old)
        if (new === old) return
        if (state.compareAndSet(old, new)) {
            onTransition(old, new)
            return
        }
    }
}

/**
 * Pure flow-assembly seam behind [ChatService.getConversationJobs]. Rebuilds a combined
 * `Map<Uuid, Job?>` of every session's active generation job each time [version] ticks, by combining
 * the per-session `generationJob` flows from [sessionsSnapshot].
 *
 * Extracted as a top-level function over plain [Flow]s so the #92 invariant can be JVM unit-tested
 * against production wiring rather than a hand-copied mirror.
 *
 * INVARIANT (issue #92): this seam carries NO `catch`/no emptyMap-on-error degradation. An upstream
 * throw must PROPAGATE — the error boundary is each consumer's (ChatVM's `stateIn` collector degrades
 * to emptyMap; web's `.first()`/SSE lets the throw surface as HTTP 500). Re-adding a swallowing catch
 * here silently rewrites a web failure into a false-success "no active jobs" 200 — the exact #92 bug.
 */
internal fun assembleConversationJobsFlow(
    version: Flow<Long>,
    sessionsSnapshot: () -> List<Pair<Uuid, Flow<Job?>>>,
): Flow<Map<Uuid, Job?>> =
    version.flatMapLatest {
        val current = sessionsSnapshot()
        if (current.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(current.map { (id, jobFlow) -> jobFlow.map { job -> id to job } }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }
