package me.rerere.rikkahub.service

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
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
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookDispatchContext
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.automation.act.AlwaysConfirm
import me.rerere.automation.act.AlwaysDeny
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.toCapability
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskToolPolicy
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.rikkahub.data.ai.agentevent.AgentEventDrainCoordinator
import me.rerere.rikkahub.data.ai.agentevent.AgentEventQueueReducer
import me.rerere.rikkahub.data.ai.agentevent.AgentEventStore
import me.rerere.rikkahub.data.ai.agentevent.ClaimOutcome
import me.rerere.rikkahub.data.ai.agentevent.ClaimAppendAction
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.ai.agentevent.AgentEventTerminalStatus
import me.rerere.rikkahub.data.ai.agentevent.SyntheticAppendResult
import me.rerere.rikkahub.data.ai.agentevent.TurnGateState
import me.rerere.rikkahub.data.ai.shellrun.ShellCompletion
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentCompletion
import me.rerere.rikkahub.data.ai.task.SubagentToolAnchor
import me.rerere.rikkahub.data.ai.shellrun.ShellRunStore
import me.rerere.rikkahub.data.ai.shellrun.ShellRunToolAnchor
import me.rerere.rikkahub.data.ai.runtime.AppToolCatalog
import me.rerere.rikkahub.data.ai.runtime.assembleBaseTools
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.ParentApprovalSurface
import me.rerere.rikkahub.data.ai.task.PendingChildApprovals
import me.rerere.rikkahub.data.ai.task.TaskApprovalRouter
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.task.TaskRunResult
import me.rerere.rikkahub.data.ai.task.TaskRunStore
import me.rerere.rikkahub.data.ai.task.injectChildApprovalPart
import me.rerere.rikkahub.data.ai.task.resolveChildApprovalPart
import me.rerere.rikkahub.data.ai.subagent.SubagentAutomationLease
import me.rerere.rikkahub.data.ai.subagent.buildSpawnTool
import me.rerere.rikkahub.data.ai.subagent.subagentBoardTools
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createFetchTools
import me.rerere.rikkahub.data.ai.tools.createImageGenTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.SkillAuthoringSpec
import me.rerere.rikkahub.data.ai.tools.createSkillAuthoringTools
import me.rerere.rikkahub.data.ai.tools.skillAuthoringSpecForToolName
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.getUiAutomationTools
import me.rerere.rikkahub.service.automation.AUTOMATION_YOLO_SUPPORTED
import me.rerere.rikkahub.service.automation.AutomationActivationTracker
import me.rerere.rikkahub.service.automation.AutomationRuntimeRegistry
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.ChatMessageTransformers
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getAssistantByIdOrCurrent
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.hasChatGpt
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.AGENT_EVENT_ID_METADATA_KEY
import me.rerere.rikkahub.data.model.AGENT_EVENT_KIND_METADATA_KEY
import me.rerere.rikkahub.data.model.AGENT_EVENT_SYNTHETIC_KIND
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.SYNTHETIC_KIND_METADATA_KEY
import me.rerere.rikkahub.data.model.syntheticAgentEventMarker
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.SHELL_BACKGROUNDED_MARKER
import me.rerere.rikkahub.data.model.isBackgroundableShell
import me.rerere.rikkahub.data.model.isSyntheticAgentEvent
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.sanitizeForUpload
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.ai.runtime.board.buildBoardTools
import me.rerere.ai.runtime.contract.DeliveryMode
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.ai.schedule.LoopFire
import me.rerere.ai.runtime.schedule.buildScheduleTools
import me.rerere.rikkahub.data.ai.task.BoardPortAdapter
import me.rerere.rikkahub.data.ai.schedule.SchedulePortAdapter
import me.rerere.rikkahub.data.repository.BoardActor
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.ai.runtime.memory.MEMORY_RECALL_K
import me.rerere.rikkahub.data.ai.memory.MemoryRecaller
import me.rerere.rikkahub.data.ai.memory.resolveMemoryRecallScope
import me.rerere.rikkahub.service.generation.ForegroundGenerationLifecycle
import me.rerere.rikkahub.service.mutation.ConversationMutations
import me.rerere.rikkahub.service.notification.ChatNotifications
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.common.text.applyPlaceholders
import me.rerere.rikkahub.utils.shouldRethrowVmError
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

private const val SYNTHETIC_TOOL_NAME_MAX_LENGTH = 64
private const val SYNTHETIC_TOOL_NAME_FALLBACK = "agent_event"
private val SYNTHETIC_TOOL_NAME_INVALID_CHARS = Regex("[^a-zA-Z0-9_-]")

// Total over any kind: a kind that is empty or all-invalid (sanitizes to "") would violate the
// provider tool-name contract ^[a-zA-Z0-9_-]{1,64}$ (min length 1), so fall back to a valid default.
internal fun sanitizeSyntheticToolName(kind: String): String =
    SYNTHETIC_TOOL_NAME_INVALID_CHARS.replace(kind, "_")
        .take(SYNTHETIC_TOOL_NAME_MAX_LENGTH)
        .ifEmpty { SYNTHETIC_TOOL_NAME_FALLBACK }

// Turn-end sequencing for the sendMessage + approval-resume paths (review mustFix #2). The invariant
// it pins: the Stop-hook continuation strictly precedes ONE turn-end job launch, so title/suggestion
// jobs are always built from the final transcript and never race a continued turn; a failed completion
// launches no jobs (the continuation still runs, matching the previous unconditional behavior). The
// completion result is HANDED to [continueAfterStopHook] so a continuation step that must not run after
// a failed turn (the #364 /goal loop) can gate itself on it, while a step that must run regardless
// (the #200 Stop hook, the #290 drain on the sendMessage path) ignores it. Top-level so the ordering
// contract is JVM-testable without constructing ChatService.
internal suspend fun sequenceTurnEnd(
    complete: suspend () -> Boolean,
    continueAfterStopHook: suspend (completed: Boolean) -> Unit,
    launchTurnEndJobs: () -> Unit,
) {
    val completed = complete()
    continueAfterStopHook(completed)
    if (completed) launchTurnEndJobs()
}

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

/**
 * Map the persisted `:app` [AutomationGrant] mirror onto the Android-free, non-`@Serializable`
 * kernel grant (Open Q1: mirror, not reuse — the kernel carries no serialization coupling). Pure
 * name-for-name enum translation; the kernel grant's own [toCapability] then enforces the
 * fail-closed gates (deny-all on absent surface, SUBMIT-strip, TTL/step bounds).
 */
private fun AutomationGrant.toKernelGrant(): me.rerere.automation.cap.AutomationGrant =
    me.rerere.automation.cap.AutomationGrant(
        enabled = enabled,
        allowedPackages = allowedPackages,
        verbs = verbs.map { Verb.valueOf(it.name) }.toSet(),
        sinks = sinks.map { Sink.valueOf(it.name) }.toSet(),
        ttlMinutes = ttlMinutes,
        maxSteps = maxSteps,
        yolo = yolo,
    )

/**
 * The capability a generation's automation lease derives from the effective grant — the per-run
 * [pendingGrant] (if any) ELSE the assistant's standing [assistantGrant] (Assumption 4: per-run
 * overrides the standing default). PURE so the deny-all root-cause invariant is JVM-testable without
 * the service.
 *
 * #187 v2 activation policy (finding 1): [masterSwitchEnabled]
 * ([Assistant.uiAutomationEnabled]) is the single gate for every grant source. The per-run
 * [pendingGrant] can override the assistant's standing [assistantGrant], but it cannot bypass the
 * master switch. With the switch off, neither source activates.
 *
 * Returns `null` (⇒ NO guard is minted ⇒ every request DENIED) when no source is both active AND a
 * usable authorization (disabled, no approved package, zero TTL, or zero steps). This preserves the
 * pre-#187-v2 deny-all an empty grant must keep: the root cause of the inert subsystem was
 * `surface = emptySet()` minted unconditionally; here a usable grant fills the surface the user
 * approved while an empty/absent grant — or any grant whose switch is off — still denies all.
 */
internal fun effectiveAutomationCapability(
    pendingGrant: AutomationGrant?,
    assistantGrant: AutomationGrant,
    masterSwitchEnabled: Boolean,
    sessionId: String,
    now: Long,
    // YOLO is honored ONLY once the user has acknowledged the danger (a global one-time consent stored
    // in DisplaySetting). Default false so the fail-closed scoped posture holds unless explicitly set.
    yoloAcknowledged: Boolean = false,
    // YOLO availability is also FLAVOR-gated at this derivation chokepoint, not just in the UI: the
    // Play build passes false so a restored/imported settings.json (carrying yolo=true + acknowledged)
    // can never mint an Unbounded/host-inclusive capability on Play. Default true preserves the
    // sideload/kernel-logic behavior for callers/tests that don't thread the flavor policy.
    yoloSupported: Boolean = true,
): Capability? {
    // The master switch gates the whole expression; a pending grant may override the standing grant,
    // but neither branch contributes authority while UI automation is disabled.
    if (!masterSwitchEnabled) return null
    // Per-run precedence with a YOLO floor (codex P0-4): a pending (per-run) grant overrides the
    // standing scope, but it can NEVER widen to YOLO — strip its yolo flag so a generic pending-grant
    // path cannot mint Unbounded for a non-YOLO assistant. YOLO comes ONLY from the standing assistant
    // grant, ONLY when the danger acknowledgement is present, AND only on a flavor that supports it;
    // otherwise it degrades to scoped.
    val effectiveGrant = (pendingGrant?.copy(yolo = false)) ?: assistantGrant
    val gatedGrant = if (effectiveGrant.yolo && (!yoloAcknowledged || !yoloSupported)) {
        effectiveGrant.copy(yolo = false)
    } else {
        effectiveGrant
    }
    return gatedGrant.toKernelGrant().toCapability(sessionId, now)
}

/**
 * The turn-boundary signal for the per-run automation grant (finding 3). A per-run grant authorizes a
 * whole TURN, but one `withAutomationLease` entry is NOT the whole turn: an ASK-guardrail approval
 * breaks the turn — a [ToolApprovalState.Pending] tool waits for the user — and the lease tears down,
 * then the approval-resume re-enters the lease. So the lease teardown must KEEP the grant while the
 * turn is still open and clear it only when the turn truly ended; an outstanding Pending tool approval
 * in the conversation is exactly "still open". PURE so the boundary is JVM-testable without the
 * service. (Mirrors the inline Pending checks the approval-resume + Stop-hook paths already use.)
 */
/**
 * The ordering-sensitive core of a SUBAGENT automation lease (Option B). Extracted + generic in [R] so
 * the guard-before-overlay invariant is JVM-testable without a real `TaskRunResult`.
 *
 * INVARIANT (codex P1): the guard MUST be registered on [session] BEFORE the STOP overlay is exposed
 * via [activation].activate. The kill-switch sweep acts only when [ConversationSession.hasActiveAutomation]
 * is true, so a tap in the gap between "overlay is tappable" and "guard registered" would otherwise
 * revoke nothing for a no-automation parent and the subagent would proceed with the kill switch already
 * pressed. This mirrors the main lease's guard-before-activate order.
 *
 * Fail-closed: when no STOP overlay can be activated, the guard is deregistered + revoked and
 * [onNoKillSwitch] runs (the subagent without automation tools — `ui_*` is never exposed without a
 * reachable kill switch). On the active path [onActive] runs with the lease held; on EVERY exit
 * (normal, error, cancellation) the guard is deregistered, the overlay deactivated, and the guard
 * revoked exactly once.
 */
internal suspend fun <R> openSubagentAutomationLeaseOnSession(
    session: ConversationSession,
    guard: CapabilityGuard,
    leaseKey: Uuid,
    activation: AutomationActivationTracker,
    onNoKillSwitch: suspend () -> R,
    onActive: suspend () -> R,
): R {
    // Register BEFORE exposing the overlay so a kill-switch tap in the activation window sees the guard.
    session.addSubagentAutomationGuard(guard)
    if (!activation.activate(leaseKey)) {
        session.removeSubagentAutomationGuard(guard)
        guard.revoke()
        return onNoKillSwitch()
    }
    return try {
        onActive()
    } finally {
        session.removeSubagentAutomationGuard(guard)
        activation.deactivate(leaseKey)
        // Revoke on release so no lingering reference to the guard can authorize after the lease.
        guard.revoke()
    }
}

internal fun conversationHasPendingToolApproval(conversation: Conversation): Boolean =
    conversation.currentMessages.any { message ->
        message.parts.any { it is UIMessagePart.Tool && it.isPending }
    }

internal data class DeferredShellToolAnchorCandidate(
    val taskId: Uuid,
    val anchor: ShellRunToolAnchor,
)

internal data class DeferredShellCompletionResolution(
    val conversation: Conversation,
    val node: MessageNode,
    val messageId: Uuid,
    val continueGeneration: Boolean,
)

internal fun shellCompletionTaskId(payloadJson: String): Uuid? =
    runCatching {
        Json.parseToJsonElement(payloadJson)
            .jsonObject["taskId"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { Uuid.parse(it) }
    }.getOrNull()

internal fun findDeferredShellToolAnchors(conversation: Conversation): List<DeferredShellToolAnchorCandidate> =
    conversation.messageNodes.flatMap { node ->
        val message = runCatching { node.currentMessage }.getOrNull() ?: return@flatMap emptyList()
        message.parts.mapNotNull { part ->
            val tool = part as? UIMessagePart.Tool ?: return@mapNotNull null
            if (tool.toolName != "workspace_shell" || !tool.isDeferred) return@mapNotNull null
            val taskId = deferredShellTaskId(tool) ?: return@mapNotNull null
            DeferredShellToolAnchorCandidate(
                taskId = taskId,
                anchor = ShellRunToolAnchor(
                    toolCallId = tool.toolCallId,
                    toolNodeId = node.id,
                    toolMessageId = message.id,
                ),
            )
        }
    }

internal fun buildSyntheticAgentEventMessage(event: AgentEventEntity): UIMessage =
    UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = event.id,
                toolName = sanitizeSyntheticToolName(event.kind),
                input = "",
                metadata = buildJsonObject {
                    put(SYNTHETIC_KIND_METADATA_KEY, AGENT_EVENT_SYNTHETIC_KIND)
                    put(AGENT_EVENT_ID_METADATA_KEY, event.id)
                    put(AGENT_EVENT_KIND_METADATA_KEY, event.kind)
                },
                output = listOf(
                    UIMessagePart.Text(
                        text = event.payloadJson,
                    )
                ),
            ),
        ),
    )

/**
 * Human-readable outcome text for a background subagent completion payload
 * (`{taskId,status,summary?,error?,steps?,tokens?}`). Robust to a missing/garbled payload.
 */
internal fun renderSubagentCompletionText(payloadJson: String): String {
    val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
    fun str(key: String) = obj?.get(key)?.jsonPrimitive?.contentOrNull
    val status = str("status") ?: "COMPLETED"
    val taskId = str("taskId")
    val summary = str("summary")
    val error = str("error")
    val head = buildString {
        append("[Background subagent ")
        append(if (status == "SUCCEEDED") "completed" else "ended — $status")
        if (!taskId.isNullOrBlank()) append(" · task $taskId")
        append("]")
    }
    val body = when {
        !summary.isNullOrBlank() -> "\n\nResult:\n$summary"
        !error.isNullOrBlank() -> "\n\n$error"
        else -> ""
    }
    return head + body
}

/**
 * The visible USER message a background subagent completion is delivered as. A USER role is REQUIRED,
 * not cosmetic: a background spawn's parent turn already continued PAST the running marker and ended on
 * an assistant message, so the completion must arrive as a fresh user-role turn — both to end the
 * conversation on a user message (else the model rejects the continuation: "must end with a user
 * message" / no assistant prefill) AND to actually NOTIFY the parent of the outcome so it need not poll.
 * Carries the synthetic-event marker so FTS/stats/sanitizer exclude it (SYNTHETIC_DISTINCTNESS).
 */
internal fun buildSubagentCompletionNotice(event: AgentEventEntity): UIMessage =
    UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text(
                text = renderSubagentCompletionText(event.payloadJson),
                metadata = buildJsonObject {
                    put(SYNTHETIC_KIND_METADATA_KEY, AGENT_EVENT_SYNTHETIC_KIND)
                    put(AGENT_EVENT_ID_METADATA_KEY, event.id)
                    put(AGENT_EVENT_KIND_METADATA_KEY, event.kind)
                },
            ),
        ),
    )

/**
 * The visible USER message a `/loop` fire (#364 slice 2) is delivered as: the loop [prompt] verbatim,
 * carried as a fresh user-role turn so the conversation's assistant works on it. Like
 * [buildSubagentCompletionNotice] a USER role is required (the conversation ended on an assistant
 * message; the provider needs a user turn to continue) and it carries the synthetic-event marker so
 * FTS/stats/sanitizer treat it as agent-injected, not user-typed.
 */
internal fun buildLoopFireMessage(event: AgentEventEntity, prompt: String): UIMessage =
    UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text(
                text = prompt,
                metadata = buildJsonObject {
                    put(SYNTHETIC_KIND_METADATA_KEY, AGENT_EVENT_SYNTHETIC_KIND)
                    put(AGENT_EVENT_ID_METADATA_KEY, event.id)
                    put(AGENT_EVENT_KIND_METADATA_KEY, event.kind)
                },
            ),
        ),
    )

/**
 * The visible "✓ Goal achieved" notice appended when a `/goal` is judged met (#364 follow-up): the
 * autonomous goal loop used to clear the goal and stop SILENTLY, so the user had no signal it
 * finished. A plain ASSISTANT message — NOT the agent-event synthetic marker, which is event-id-keyed
 * ([syntheticAgentEventMarker] requires an [AGENT_EVENT_ID_METADATA_KEY]) and would be a half-state
 * lie here — so it is honest, durable, and readable when the app is backgrounded.
 */
internal fun buildGoalAchievedNotice(condition: String): UIMessage =
    UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text = "✓ Goal achieved: \"$condition\"")),
    )

internal fun resolveDeferredShellCompletion(
    conversation: Conversation,
    anchor: ShellRunToolAnchor,
    payloadJson: String,
): DeferredShellCompletionResolution? {
    var resolvedNode: MessageNode? = null
    var shouldContinue = false
    var foundAnchor = false
    val updatedNodes = conversation.messageNodes.map { node ->
        if (node.id != anchor.toolNodeId) return@map node
        // Only auto-continue when the anchored tool lives in the node's CURRENTLY SELECTED branch.
        // A deferred shell may sit in a branch alternative the user has since switched away from; we
        // still resolve its output for history correctness, but generation reads currentMessages
        // (the selected branch), so continuing on a completion from a non-selected branch would
        // spuriously generate on the wrong branch.
        val anchoredMessageIsSelected =
            node.messages.getOrNull(node.selectIndex)?.id == anchor.toolMessageId
        val updatedMessages = node.messages.map { message ->
            if (message.id != anchor.toolMessageId) return@map message
            var matchedTool = false
            val updatedParts = message.parts.map { part ->
                val tool = part as? UIMessagePart.Tool ?: return@map part
                if (tool.toolCallId != anchor.toolCallId) return@map part
                matchedTool = true
                val currentText = (tool.output.singleOrNull() as? UIMessagePart.Text)?.text
                if (tool.isDeferred) {
                    shouldContinue = anchoredMessageIsSelected
                    tool.copy(output = listOf(UIMessagePart.Text(payloadJson))).asResolved()
                } else if (currentText == payloadJson) {
                    tool.asResolved()
                } else {
                    tool
                }
            }
            if (matchedTool) foundAnchor = true
            if (matchedTool) message.copy(parts = updatedParts) else message
        }
        val updatedNode = node.copy(messages = updatedMessages)
        if (foundAnchor) resolvedNode = updatedNode
        updatedNode
    }
    val node = resolvedNode ?: return null
    return DeferredShellCompletionResolution(
        conversation = conversation.copy(messageNodes = updatedNodes, updateAt = Instant.now()),
        node = node,
        messageId = anchor.toolMessageId,
        continueGeneration = shouldContinue,
    )
}

private fun deferredShellTaskId(tool: UIMessagePart.Tool): Uuid? {
    val text = (tool.output.singleOrNull() as? UIMessagePart.Text)?.text ?: return null
    return runCatching {
        Json.parseToJsonElement(text)
            .jsonObject["taskId"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { Uuid.parse(it) }
    }.getOrNull()
}

/**
 * The taskId of a still-RUNNING background subagent marker (`agent`/`task` tool whose executed output
 * is `{status:"running",taskId,...}`), or null if [tool] is not one. Unlike a deferred shell tool, the
 * background marker is an EXECUTED tool output — so the match is on the running status, not isDeferred.
 */
internal fun runningSubagentTaskId(tool: UIMessagePart.Tool): Uuid? {
    if (tool.toolName != SPAWN_TOOL_MODEL_NAME && tool.toolName != SPAWN_TOOL_NAME) return null
    val text = (tool.output.singleOrNull() as? UIMessagePart.Text)?.text ?: return null
    return runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        if (obj["status"]?.jsonPrimitive?.contentOrNull != "running") return@runCatching null
        obj["taskId"]?.jsonPrimitive?.contentOrNull?.let { Uuid.parse(it) }
    }.getOrNull()
}

/** Background `agent`/`task` running markers in the conversation, as (taskId, anchor) candidates. */
internal fun findBackgroundSubagentToolAnchors(
    conversation: Conversation,
): List<Pair<Uuid, SubagentToolAnchor>> =
    conversation.messageNodes.flatMap { node ->
        val message = runCatching { node.currentMessage }.getOrNull() ?: return@flatMap emptyList()
        message.parts.mapNotNull { part ->
            val tool = part as? UIMessagePart.Tool ?: return@mapNotNull null
            val taskId = runningSubagentTaskId(tool) ?: return@mapNotNull null
            taskId to SubagentToolAnchor(
                toolCallId = tool.toolCallId,
                toolNodeId = node.id,
                toolMessageId = message.id,
            )
        }
    }

/**
 * Whether a background subagent's spawn [anchor] (the `agent`/`task` tool call) lives in the node's
 * CURRENTLY SELECTED branch. The completion is always delivered as a trailing USER notice, but we
 * auto-continue the parent ONLY when the anchor is on the selected branch — so a completion for a
 * branch the user regenerated/switched away from is recorded without driving a continuation on the
 * now-current branch (which never spawned that run). A pure read-only check (no mutation), so delivery
 * stays a single failure-atomic append.
 *
 * Returns FALSE when the anchor's node is no longer present (e.g. a regenerate TRUNCATED away the node
 * that held the spawn marker while the detached run kept going): a known anchor whose node/message is
 * gone is exactly the regenerated-away case and must NOT auto-continue. The anchorLESS case (no anchor
 * could be located at all) is handled by the caller (deliver + continue best-effort), not here.
 */
internal fun isBackgroundAnchorOnSelectedBranch(
    conversation: Conversation,
    anchor: SubagentToolAnchor,
): Boolean {
    val node = conversation.messageNodes.firstOrNull { it.id == anchor.toolNodeId } ?: return false
    return node.messages.getOrNull(node.selectIndex)?.id == anchor.toolMessageId
}

/**
 * Whether `withAutomationLease`'s teardown must PRESERVE the per-run grant for an approval-resume
 * (finding 3). The grant survives the lease entry ONLY when BOTH hold:
 *  - [completedNormally] — the generation block returned normally (the ASK-guardrail break: the
 *    runtime paused and the flow finished without error). A non-normal exit — an error, or a
 *    CancellationException from a Stop / a newer entry superseding this job — is a turn ABANDON: the
 *    turn will not resume, so the grant must clear or it would scope the next, unrelated turn.
 *  - [hasPendingApproval] — a Pending tool approval is still outstanding, i.e. the turn is genuinely
 *    paused waiting for the user (not simply finished).
 * Any other combination clears the grant (the #187 v2 transient-grant invariant). PURE so the
 * boundary is JVM-testable without the service.
 */
internal fun shouldPreservePerRunGrant(
    completedNormally: Boolean,
    hasPendingApproval: Boolean,
): Boolean = completedNormally && hasPendingApproval

internal fun finishInterruptedPendingToolsForNewSend(
    session: ConversationSession,
    cancelTool: (UIMessagePart.Tool) -> UIMessagePart.Tool,
): Conversation? {
    val currentConversation = session.state.value
    val lastNode = currentConversation.messageNodes.lastOrNull() ?: return null
    val lastMessage = lastNode.currentMessage
    val updatedMessage = lastMessage.finishPendingTools(cancelTool)
    if (updatedMessage == lastMessage) {
        return null
    }

    // A new-send/stop abandon finalizes the paused tool instead of resuming it. The preserved
    // per-run grant was only for that paused operation, so clear it before the updated conversation
    // is saved and before a later generation can derive a guard from stale state.
    session.pendingAutomationGrant = null

    return currentConversation.copy(
        messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
            messages = lastNode.messages.map { message ->
                if (message.id == lastMessage.id) updatedMessage else message
            }
        )
    )
}

/**
 * STOP_IS_DETACH_NOT_KILL (issue #291): whether an interrupted (not-yet-executed) tool part is a
 * `workspace_shell` run that a user stop should BACKGROUND rather than finalize as cancelled. The
 * coordinator already persisted the run DETACHED under NonCancellable and launched the detached
 * awaiter; the completion arrives later as a synthetic #290 event. So the turn finalizer must leave
 * this part pending instead of stamping `{status:cancelled}` over a run that is still alive. PURE so
 * the predicate is JVM-testable without the service. A backgrounded run is necessarily NOT executed
 * (the coordinator rethrew cancellation rather than returning inline output); an inline-exited or
 * killed run already has output and never reaches the finalizer.
 */
internal fun shouldBackgroundShellOnStop(tool: UIMessagePart.Tool): Boolean {
    if (tool.isExecuted) return false
    if (tool.isPending) return false  // approval-pending → cancel normally; no process started yet
    // isBackgroundableShell encodes toolName==workspace_shell && detachAfterSeconds>0 (the shared
    // predicate, so the finalizer and the sanitizer classify a backgroundable shell identically).
    // Default-kill shells (detachAfterSeconds absent/0) are cancelled normally — no completion event
    // ever arrives for them, so leaving them pending would strand the tool part forever.
    return tool.isBackgroundableShell()
}

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

    /**
     * Process one chunk: remember its merged value, and decide by the window throttle whether to publish
     * it now.
     *
     * [force] is the "must-publish" hint for low-frequency SEMANTIC-transition frames (a tool call
     * assembled but not yet executed, a pending-approval gate, a tool's output arriving): such a frame
     * must publish immediately even when it lands inside the throttle window — otherwise an in-progress
     * tool frame emitted right before a same-step `executeTool` suspension is held by the window and then
     * overwritten by the executed frame, so the running tool is never shown. Per-token streaming frames
     * pass [force]=false and are throttled as before.
     */
    fun onChunk(now: Long, value: T, force: Boolean = false) {
        lastValue = value
        hasValue = true
        if (force || shouldPublishStreamingUpdate(lastPublishAt, now, intervalMs)) {
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
        force: (T) -> Boolean = { false },
    ): Flow<T> = source
        .onEach { value ->
            sideEffect(value)
            onChunk(now(), value, force(value))
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

/**
 * A Stop hook asked the turn to continue: [additionalContext] is injected as a new user-role
 * message and one more completion runs. Produced by [ChatHookFirePoints.onStop] only when context
 * was actually injected AND no handler set `preventContinuation` — so the value's existence IS the
 * continuation decision (no separate boolean to keep in sync).
 */
internal data class StopHookContinuation(val additionalContext: String)

/**
 * The `/goal` judge's verdict (#364), kept as three distinct cases so a JUDGE FAILURE never silently
 * abandons the goal. [ChatHookFirePoints.onStop] collapses "met" / "blank" / "failed" all to a single
 * null; the goal loop needs them apart:
 *  - [Met]          the LLM judged the goal achieved (preventContinuation) -> clear the goal and stop.
 *  - [Continue]     not yet met -> inject [directive] as the next user turn and run one more turn.
 *  - [Inconclusive] the judge produced no usable verdict — a hook/provider/parse failure, a timeout,
 *                   a missing dispatcher/Stop matcher, or an empty answer. PAUSE: keep the goal armed
 *                   so a later manual send can retry; a transient judge failure must never clear it.
 */
internal sealed interface GoalVerdict {
    data object Met : GoalVerdict
    data class Continue(val directive: String) : GoalVerdict
    data object Inconclusive : GoalVerdict
}

/**
 * Whether the `/goal` autonomous loop may run another continuation (#364). [maxIterations] <= 0 means
 * UNLIMITED (the user opted out of an iteration cap); otherwise the loop stops once [iteration]
 * reaches the cap. Pure so the bound is JVM-unit-testable independently of the turn machinery; a
 * user-stop and the LLM "goal met" verdict end the loop separately, regardless of this bound.
 */
internal fun shouldContinueGoal(iteration: Int, maxIterations: Int): Boolean =
    maxIterations <= 0 || iteration < maxIterations

/**
 * ChatService-side hook fire-points (#200 T8): UserPromptSubmit at the send seam ([onUserPromptSubmit])
 * and Stop at turn end ([onStop]). Extracted to file level so production and JVM tests drive the
 * SAME logic against a real [HookDispatcher] (the [StreamingUiCoalescer] precedent — not a
 * hand-copied loop). A null dispatcher or an event with no configured matchers is the no-hooks
 * path: passthrough with zero dispatch work, exactly the pre-hooks behavior.
 *
 * UserPromptSubmit is inject-only by spec (v1): the decision field is a PreToolUse gating concern
 * and has no seam on send, so only `additionalContext` is consumed here.
 */
internal class ChatHookFirePoints(
    private val dispatcher: HookDispatcher?,
) {
    /**
     * Returns [parts] with the aggregated `additionalContext` appended as its own [UIMessagePart.Text]
     * — a visible part of the outgoing user turn (never silent), persisted with the message.
     */
    suspend fun onUserPromptSubmit(config: HookConfig, parts: List<UIMessagePart>): List<UIMessagePart> {
        val dispatcher = this.dispatcher ?: return parts
        if (config.hooks[HookEvent.UserPromptSubmit].isNullOrEmpty()) return parts
        val prompt = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val result = dispatcher.dispatch(
            event = HookEvent.UserPromptSubmit,
            input = buildJsonObject {
                put("hookEventName", HookEvent.UserPromptSubmit.name)
                put("prompt", prompt)
            }.toString(),
            ctx = HookDispatchContext(config = config),
        )
        // Blank context would append an empty bubble to the user's message — treat it as no injection.
        val context = result.additionalContext?.takeIf { it.isNotBlank() } ?: return parts
        return parts + UIMessagePart.Text(context)
    }

    /**
     * Stop fire-point: a hook may inject context to keep the agent going for one more completion;
     * `preventContinuation` (from ANY matched handler — aggregate ORs it) suppresses exactly that.
     * No context, or context vetoed, means the turn ends as it always did.
     */
    suspend fun onStop(config: HookConfig, lastAssistantText: String?): StopHookContinuation? {
        val dispatcher = this.dispatcher ?: return null
        if (config.hooks[HookEvent.Stop].isNullOrEmpty()) return null
        val result = dispatcher.dispatch(
            event = HookEvent.Stop,
            input = buildJsonObject {
                put("hookEventName", HookEvent.Stop.name)
                put("lastAssistantMessage", lastAssistantText.orEmpty())
            }.toString(),
            ctx = HookDispatchContext(config = config),
        )
        if (result.preventContinuation) return null
        val context = result.additionalContext?.takeIf { it.isNotBlank() } ?: return null
        return StopHookContinuation(context)
    }

    /**
     * Judge a `/goal` (#364) via the synthetic Stop hook, returning a 3-way [GoalVerdict] rather than
     * the collapsed nullable [onStop] gives — so the loop can tell "met" (clear the goal) from a judge
     * FAILURE (pause, keep it armed). A null dispatcher or no configured Stop matcher is
     * [GoalVerdict.Inconclusive]: with no judge there is no verdict, so the safe move is to pause, never
     * to clear the goal. A hook/provider/parse failure degrades (fail-open) to an empty aggregate,
     * which falls through to [GoalVerdict.Inconclusive] for the same reason.
     */
    suspend fun judgeGoal(config: HookConfig, lastAssistantText: String?): GoalVerdict {
        val dispatcher = this.dispatcher ?: return GoalVerdict.Inconclusive
        if (config.hooks[HookEvent.Stop].isNullOrEmpty()) return GoalVerdict.Inconclusive
        val result = dispatcher.dispatch(
            event = HookEvent.Stop,
            input = buildJsonObject {
                put("hookEventName", HookEvent.Stop.name)
                put("lastAssistantMessage", lastAssistantText.orEmpty())
            }.toString(),
            ctx = HookDispatchContext(config = config),
        )
        return when {
            result.preventContinuation -> GoalVerdict.Met
            !result.additionalContext.isNullOrBlank() -> GoalVerdict.Continue(result.additionalContext!!)
            else -> GoalVerdict.Inconclusive
        }
    }
}

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

/**
 * The automation kill-switch sweep (#187 §7/I9), extracted to file level (#360 P1a) so the
 * "revoke EVERY active-automation session and cancel its job, leave the rest untouched" policy is
 * JVM-unit-testable with real [ConversationSession]s — independent of constructing the full ChatService.
 * Revokes a session iff it has ANY live automation guard (the main lease OR a subagent lease, Option B),
 * then cancels its generation job so structured concurrency tears down the in-flight capture.
 */
internal fun revokeActiveAutomation(sessions: Collection<ConversationSession>) {
    sessions.forEach { session ->
        if (session.hasActiveAutomation()) {
            session.revokeAutomation()
            session.getJob()?.cancel()
        }
    }
}

class ChatService(
    // String-resource lookup (#360 P1b) — replaces the direct Application/Context dependency, which was
    // ChatService's last raw Android type. A test supplies a fake StringProvider.
    private val strings: StringProvider,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val memoryRecaller: MemoryRecaller,
    private val generationHandler: GenerationHandler,
    private val taskCoordinator: TaskCoordinator,
    private val chatMessageTransformers: ChatMessageTransformers,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    // Per-conversation work-item board (SPEC.md M3/T7). Board tools delegate through this single
    // repository — the same path the board UI uses (decision #4) — so legality is enforced once.
    private val taskBoardRepository: TaskBoardRepository,
    // Per-conversation task schedules (SPEC.md M4/T7). The schedule tools delegate through this
    // single repository — the same path the schedule UI uses — so legality is enforced once.
    private val taskScheduleRepository: TaskScheduleRepository,
    // Live subagent execution handles (SPEC.md M4/M6). The spawn path registers one handle per
    // child; its id is the owner of every board claim the child takes, and the same id is what
    // orphan release frees when the handle dies.
    private val executionHandles: ExecutionHandleRegistry,
    // Task-run persistence seam (SPEC.md M2). The child-approval router records auto-deny
    // reasons and drives the WaitingApproval round-trip on the SAME rows TaskCoordinator owns.
    private val taskRunStore: TaskRunStore,
    // Durable agent-event queue (issue #290): async injection of a synthetic message into a
    // conversation at an idle turn-end. ChatService owns the drain seam; the store is persistence
    // only.
    private val agentEventStore: AgentEventStore,
    private val shellRunStore: ShellRunStore,
    // On-device UI automation (#187 v1, read-only). Registry hands out the live, system-instantiated
    // AccessibilityRuntime as a pure backend. The kill-switch is no longer a direct dependency (#360
    // P1a): its register()/trip() wiring moved into [processBinding] and [automationActivation].
    private val automationRegistry: AutomationRuntimeRegistry,
    // Platform side-effect seams (#360 P1a), INJECTED so ChatService no longer constructs them from
    // `context` nor references Android lifecycle APIs directly — tests supply fakes:
    //  - [foregroundGeneration]: the active-generation → foreground-service lifecycle (was an inline
    //    GenerationForegroundCoordinator(AndroidGenerationForegroundController(context))).
    //  - [notifications]: Live Update / generation-done notifications (was an inline ChatNotificationSender(context)).
    //  - [processBinding]: ProcessLifecycleOwner foreground state + the kill-switch registration (was
    //    raw ProcessLifecycleOwner.get() + automationKillSwitch.register in init).
    //  - [automationActivation]: the STOP-overlay refcount/fail-closed tracker (was inline).
    private val foregroundGeneration: ForegroundGenerationLifecycle,
    private val notifications: ChatNotifications,
    private val processBinding: ChatServiceProcessBinding,
    private val automationActivation: AutomationActivationTracker,
    // Event hooks (#200 v1) are opt-in at the composition root: null preserves the pre-hooks
    // send/stop paths exactly (mirrors ChatTurnRuntime's optional dispatcher port).
    hookDispatcher: HookDispatcher? = null,
) {
    // UserPromptSubmit + Stop fire-points (#200 T8); the logic lives at file level so the JVM
    // suite drives the same code (ChatHookFirePointsTest).
    private val hookFirePoints = ChatHookFirePoints(hookDispatcher)

    // 统一会话管理 (#360 P2): the per-conversation session map + lifecycle live in a JVM-testable
    // registry. The construction inputs are forwarded so the registry names no settings store /
    // foreground controller; ChatService delegates getOrCreateSession/removeSession to it.
    private val sessionRegistry = SessionRegistry(
        scope = appScope,
        newConversation = { id ->
            Conversation.ofId(id = id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationStart = { foregroundGeneration.onGenerationStart() },
        onGenerationStop = { foregroundGeneration.onGenerationStop() },
    )
    // Conversation delete/restore coordination (#360 P3): the tombstone set + delete↔restore
    // serialization + the insert-before-clear ordering, extracted to a JVM-testable component.
    private val tombstones = ConversationTombstones()

    // In-flight forwarded CHILD approvals (SPEC.md M4 / Gap A), keyed taskId/childToolCallId.
    // The waiter suspends inside the live generation; handleToolApproval resolves it IN PLACE —
    // never through launchGenerationEntry, whose cancel-previous would kill the waiting child.
    private val pendingChildApprovals = PendingChildApprovals()

    // UI-automation lease clock (#187). A real wall-clock; the kernel injects it instead of reading
    // System.now directly so lease/TTL behaviour is reproducible in the :automation PBT suite.
    private val trustClock = TrustClock { System.currentTimeMillis() }

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

    // Process-binding handle (#360 P1a): undoes the foreground + kill-switch registration on cleanup so
    // a recreated ChatService does not leak a stale handler that captures dead `sessions`.
    private var processBindingHandle: AutoCloseable? = null

    // Durable agent-event drain COORDINATION (#290) extracted to a JVM-testable coordinator (#360 P4):
    // idle gating, the no-double-generation slot claim, the snapshot-bounded loop, and the re-poke. The
    // per-event DELIVERY (drainOneAgentEvent) stays here and is injected — it continues the model via the
    // turn runner (handleMessageComplete), so it extracts cleanly only with P6. The session-coupled
    // projections (turnGateState, the idle-slot claim, the conversation ref, hydrate) are forwarded.
    private val agentEventDrainCoordinator = AgentEventDrainCoordinator(
        store = agentEventStore,
        scope = appScope,
        turnGate = ::turnGateState,
        claimIdleSlot = { conversationId, jobFactory ->
            getOrCreateSession(conversationId).tryClaimIdleGenerationSlot(jobFactory)
        },
        withConversationRef = { conversationId, block -> launchWithConversationReference(conversationId, block) },
        hydrate = ::hydrateSessionFromStoreIfBlank,
        drainOne = ::drainOneAgentEvent,
        signalDrainPass = { conversationId -> _generationDoneFlow.emit(conversationId) },
        reportError = { e, conversationId ->
            addError(e, conversationId, title = strings.getString(R.string.error_title_generation))
        },
    )

    init {
        // Bind the two process-global, platform-coupled hooks (#360 P1a) behind the injected port
        // instead of referencing ProcessLifecycleOwner / AutomationKillSwitch.register here directly:
        //  - foreground state (drives notification gating); and
        //  - the floating-STOP kill-switch (#187 §7/I9): revoke EVERY active automation grant and cancel
        //    its generation job. The kill-switch CALLBACK still traverses THIS service's sessions, so the
        //    session-coupled policy stays in ChatService (via [revokeActiveAutomation]) while only the
        //    Android wiring moves behind the port — keeping P1a independent of the session-registry seam.
        processBindingHandle = processBinding.bind(
            onForegroundChanged = { _isForeground.value = it },
            onKillSwitchTrip = { revokeActiveAutomation(sessionRegistry.snapshot()) },
        )
    }

    fun cleanup() = runCatching {
        processBindingHandle?.close()
        processBindingHandle = null
        sessionRegistry.cleanupAll()
    }

    // ---- Session 管理 ----

    // Thin delegator (#360 P2): the session map + create/evict lifecycle live in [sessionRegistry].
    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        sessionRegistry.getOrCreate(conversationId)

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionRegistry.get(conversationId)?.release()
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
        val session = sessionRegistry.get(conversationId) ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessionRegistry.get(conversationId) ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    // ---- 自动化按运行授权 (#187 v2) ----

    /**
     * The package currently in the device foreground, read live from the accessibility runtime — the
     * single app an in-chat grant can be scoped to. Null when the accessibility service is not
     * connected. Surfaced here (not the registry) so the chat ViewModel depends only on ChatService.
     */
    fun automationForegroundPackage(): String? = automationRegistry.foregroundPackage()

    /**
     * Record the user's per-run automation scope on the conversation's session. Transient lease state
     * (lives beside `activeAutomationGuard`, cleared by `clearAutomationLeaseState`), NOT persisted on
     * the Assistant. The lease derivation consumes it to mint the guard; an empty/absent grant stays
     * fail-closed (deny-all). Writing it here keeps the private session map encapsulated in ChatService.
     */
    fun setPendingAutomationGrant(conversationId: Uuid, grant: AutomationGrant) {
        getOrCreateSession(conversationId).pendingAutomationGrant = grant
    }

    // 不在这里 catch/降级：本流是对一组 in-memory StateFlow（每个 session 的 generationJob，热流、
    // 永不完成、永不抛）做 combine，没有可重试的瞬时故障。下游对错误的诉求各不相同，故把错误边界下放到
    // 各消费者本身——ChatVM 的 stateIn 收集器需在异常下存活（见 #92，catch 置于 stateIn 之前）；
    // web 的 .first()/SSE 则应让真实异常以 HTTP 500 上抛，而非被静默改写成“无活跃任务”的 200。
    fun getConversationJobs(): Flow<Map<Uuid, Job?>> =
        assembleConversationJobsFlow(
            version = sessionRegistry.version,
            sessionsSnapshot = { sessionRegistry.snapshot().toList().map { it.id to it.generationJob } }
        )

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversationFromInitialization(conversationId, conversation)
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
            updateConversationFromInitialization(conversationId, newConversation)
        }
    }

    suspend fun initializeConversationForSkill(conversationId: Uuid, assistantId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(assistantId)
            ?: throw NotFoundException("skill assistant not found")

        if (conversation == null) {
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversationFromInitialization(conversationId, newConversation)
            return
        }

        if (!isA2aConversationBindingAllowed(conversation.assistantId, assistantId)) {
            throw BadRequestException("conversation already initialized for another assistant")
        }

        updateConversationFromInitialization(conversationId, conversation)
    }

    // ---- 发送消息 ----

    // The ONE generation entry-point lifecycle (audit Q3, PR #266): sendMessage,
    // regenerateAtMessage and handleToolApproval used to re-implement cancel-previous →
    // launch → try/catch → addError → done-emit → setJob, and drifted twice (the
    // cancellation-rethrow guard and the supersede join barrier each landed in only one
    // copy). The lifecycle now lives here once; the JVM-testable policy core is the
    // top-level [launchGenerationEntryJob] below.
    private fun launchGenerationEntry(
        conversationId: Uuid,
        errorTitle: String,
        block: suspend (ConversationSession) -> Unit,
    ): Job {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchGenerationEntryJob(
            scope = appScope,
            previousJob = previousJob,
            onError = { e ->
                Log.e(TAG, "generation entry failed: $errorTitle", e)
                addError(e, conversationId, title = errorTitle)
            },
        ) {
            block(session)
            _generationDoneFlow.emit(conversationId)
        }
        session.setJob(job)
        // Every generation-entry turn-end — success, failure, regenerate, approval-resume, cancel —
        // must settle pending agent events, e.g. a background-subagent completion enqueued WHILE this
        // turn was generating (its own idle-drain poke was refused for the live turn). The onSuccess
        // turn-end drain (handleMessageComplete) covers only the success branch; this catch-all pokes
        // an idle-gated drain once THIS job completes and the slot frees (async, so it runs after the
        // slot-release completion handler), so a completion is never stranded by a failed/regenerate/
        // approval turn. Idle-gated + AT_MOST_ONCE make it a no-op when the success
        // path already drained.
        job.invokeOnCompletion {
            // Catch-all for a skill-authoring lease whose turn errored/cancelled BEFORE handleMessageComplete
            // consumed it: clear it here so a stale write capability can never be picked up by the next
            // turn's consume. On the normal path the consume already nulled it, so this is a no-op.
            session.clearSkillAuthoring()
            appScope.launch { maybeDrainAgentEventsWhenIdle(conversationId) }
        }
        return job
    }

    fun sendMessageReturningJob(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        armSkillAuthoring: SkillAuthoringSpec? = null,
    ): Job {
        if (content.isEmptyInputMessage()) return Job().apply { complete() }

        return launchGenerationEntry(
            conversationId = conversationId,
            errorTitle = strings.getString(R.string.error_title_send_message),
        ) { session ->
            // An authoring send (only ChatVM's /create_skill//update_skill) arms the lease for THIS turn;
            // handleMessageComplete below consumes it when assembling this turn's tool pool. Any other
            // send arms nothing, so the consume reads null and the write tool is absent. (The lease is a
            // bare set, not a clear-then-set: a prior turn's lease is already gone — consumed by its own
            // handleMessageComplete, or cleared by the job-completion catch-all.)
            if (armSkillAuthoring != null) session.armSkillAuthoring(armSkillAuthoring)

            finishInterruptedPendingTools(conversationId)

            val currentConversation = session.state.value
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantByIdOrCurrent(currentConversation.assistantId)
            val processedContent = preprocessUserInputParts(content, assistant)

            // UserPromptSubmit hooks (#200 T8): the send seam. Injected additionalContext is
            // appended to the outgoing turn as its own visible Text part BEFORE the message is
            // persisted, so what the model sees is exactly what the user can see. No hooks
            // configured (or dispatcher absent) -> processedContent passes through untouched.
            val finalContent = hookFirePoints.onUserPromptSubmit(assistant.hooks, processedContent)

            // 添加消息到列表
            val newConversation = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = finalContent,
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
                // Stop hooks (#200 T8): turn end. Injected context (unless vetoed by
                // preventContinuation) triggers exactly ONE more completion — the continuation's
                // own turn end does NOT re-dispatch Stop, so a hook that always injects cannot
                // loop the agent unboundedly. Title/suggestion jobs are deferred until after the
                // continuation so they reflect the final transcript (review mustFix #2).
                sequenceTurnEnd(
                    complete = { handleMessageComplete(conversationId, runTurnEndJobs = false) },
                    continueAfterStopHook = { completed ->
                        // The single-shot assistant.hooks Stop continuation may itself run one more turn;
                        // its success (or the passthrough when none ran) is what gates /goal — NOT the
                        // original `completed`, which would let the goal advance after a FAILED Stop-hook
                        // continuation turn.
                        val turnSucceeded =
                            continueAfterStopHookIfRequested(conversationId, assistant, precedingTurnSucceeded = completed)
                        // The /goal autonomous loop (issue #364): re-entrant, goal-only, bounded by
                        // maxGoalIterations — runs after the single-shot assistant.hooks continuation
                        // so a user goal can keep the agent working until the condition is met. Gated on
                        // a SUCCESSFUL turn: a failed turn must not trigger an autonomous continuation
                        // (a failure is not evidence the goal needs another step, and an unlimited
                        // budget would otherwise spin on a persistent provider error).
                        continueGoalLoopIfActive(conversationId, precedingTurnSucceeded = turnSucceeded)
                        // Agent-event drain (issue #290): after the Stop-hook continuation, before
                        // the turn-end jobs (the "continuation before jobs" rule). The drain is
                        // idle-gated by its own pending-tool guard; a deferred background event is
                        // delivered here at a genuine turn-end, never mid-turn.
                        drainAgentEventsAtTurnEnd(conversationId)
                    },
                    launchTurnEndJobs = { launchTurnEndJobs(conversationId) },
                )
            }
        }
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        armSkillAuthoring: SkillAuthoringSpec? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        sendMessageReturningJob(conversationId, content, answer, armSkillAuthoring)
    }

    // Stop fire-point (#200 T8). Runs after the agentic turn handleMessageComplete owns has
    // finished. Two deliberate guards:
    //  - A HITL approval break is NOT a turn end — the user owns the next step, and the
    //    approval-resume path re-enters handleMessageComplete on its own. Firing Stop there would
    //    let a hook continue past a gate the user hasn't decided yet.
    //  - The continuation is single-shot per send: this method calls handleMessageComplete
    //    directly and never re-enters itself, bounding a context-always hook to one extra
    //    completion instead of an unbounded loop.
    // The injected context becomes a visible user-role message (never silent) so the transcript
    // shows exactly why the agent kept going.
    //
    // Returns the EFFECTIVE last-turn success so a downstream autonomous step (#364 /goal) can gate on
    // it: the incoming [precedingTurnSucceeded] when no continuation ran (a pending-tool break, no hook,
    // or a vetoed/blank context), else the result of the continuation turn this method ran. Without
    // this, /goal would advance after a FAILED Stop-hook continuation turn.
    private suspend fun continueAfterStopHookIfRequested(
        conversationId: Uuid,
        assistant: Assistant,
        precedingTurnSucceeded: Boolean,
    ): Boolean {
        val conversation = getConversationFlow(conversationId).value
        val lastMessage = conversation.currentMessages.lastOrNull()
        if (lastMessage?.parts?.any { it is UIMessagePart.Tool && it.isPending } == true) return precedingTurnSucceeded

        val continuation = hookFirePoints.onStop(
            config = assistant.hooks,
            lastAssistantText = lastMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.toText(),
        ) ?: return precedingTurnSucceeded

        saveConversation(
            conversationId,
            conversation.copy(
                messageNodes = conversation.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(continuation.additionalContext)),
                ).toMessageNode(),
            ),
        )
        // runTurnEndJobs = false: the sendMessage path owns the single turn-end job launch via
        // sequenceTurnEnd, after this continuation finished (review mustFix #2).
        return handleMessageComplete(conversationId, runTurnEndJobs = false)
    }

    // ---- Autonomous goal loop (issue #364, `/goal`) ----

    /**
     * Arm a session-scoped autonomous goal: the turn-end goal loop will keep continuing the agent
     * toward [condition] until an LLM judges it met (or the `maxGoalIterations` budget / a user-stop
     * ends it). Session-scoped — never persisted into the assistant config.
     */
    fun setGoal(conversationId: Uuid, condition: String) {
        getOrCreateSession(conversationId).armGoal(GoalSpec(condition.trim()))
    }

    /** Clear the active goal (the `/goal clear` path and the user-stop path). Idempotent. */
    fun clearGoal(conversationId: Uuid) {
        sessionRegistry.get(conversationId)?.clearGoal()
    }

    /**
     * The synthetic Stop-hook config that JUDGES a goal: an LLM Stop hook whose prompt asks whether
     * [condition] is achieved. Consumed by [ChatHookFirePoints.judgeGoal]: not-met → the hook returns
     * `additionalContext` (the next step) → [GoalVerdict.Continue]; met → the hook sets
     * `preventContinuation` → [GoalVerdict.Met]; a judge failure → [GoalVerdict.Inconclusive].
     * `trusted = true` because this config is minted in-process from the user's own `/goal`, never
     * imported. Kept separate from `assistant.hooks` so the user's persisted Stop hooks stay
     * SINGLE-SHOT — only this goal loop re-enters.
     */
    private fun goalStopConfig(condition: String): HookConfig = HookConfig(
        hooks = mapOf(
            HookEvent.Stop to listOf(
                HookMatcher(handlers = listOf(HookHandler.Llm(prompt = goalJudgePrompt(condition)))),
            ),
        ),
        trusted = true,
    )

    private fun goalJudgePrompt(condition: String): String = """
        The user set this GOAL for you to accomplish autonomously:
        "$condition"

        Decide, from the conversation so far, whether the goal is now FULLY achieved.
        - If it IS fully achieved: set "preventContinuation": true and do not continue.
        - If it is NOT yet achieved: set "additionalContext" to ONE short directive naming the next
          concrete step toward the goal. It is injected as the next user turn to keep you working.
          Leave "preventContinuation" false.
    """.trimIndent()

    /**
     * The `/goal` re-entrant continuation (#364). Runs at a genuine turn end (after the single-shot
     * `assistant.hooks` Stop continuation, before the agent-event drain). While a goal is armed it asks
     * an LLM whether the goal is met; if not, it injects the next-step directive as a visible user
     * message and runs one more turn, looping until met, until the `maxGoalIterations` budget is hit,
     * until the goal is cleared (a user-stop clears it), or until a pending tool approval breaks the
     * turn. Bounded by construction (`shouldContinueGoal`), so it can never loop unboundedly.
     *
     * [precedingTurnSucceeded] gates the whole loop: a FAILED turn (provider error, cancellation) must
     * not start an autonomous continuation — a failure is not evidence the goal needs another step, and
     * an unlimited budget (`maxGoalIterations <= 0`) would otherwise spin forever on a persistent
     * failure. The goal stays armed (a later manual send can retry); it is just not auto-advanced now.
     */
    private suspend fun continueGoalLoopIfActive(conversationId: Uuid, precedingTurnSucceeded: Boolean) {
        if (!precedingTurnSucceeded) return
        val session = sessionRegistry.get(conversationId) ?: return
        val maxIterations = settingsStore.settingsFlow.value.maxGoalIterations
        while (true) {
            val goal = session.activeGoal ?: return // cleared (user-stop / clear / met) -> stop
            // The budget is PER-GOAL: charge against the count carried on the goal, accumulated across
            // loop invocations, so a positive cap bounds the goal's TOTAL continuations even when an
            // approval break pauses and a later send resumes the loop. Identity-guarded so a concurrent
            // re-arm of a NEW goal is never wiped by THIS goal's budget running out.
            if (!shouldContinueGoal(goal.iterationsUsed, maxIterations)) {
                session.compareAndSetGoal(goal, null)
                return
            }
            val conversation = getConversationFlow(conversationId).value
            val lastMessage = conversation.currentMessages.lastOrNull()
            // A HITL approval break is not a turn end — the user owns the next step. Return WITHOUT
            // clearing so the goal (and its used-iteration count) survives for the resume.
            if (lastMessage?.parts?.any { it is UIMessagePart.Tool && it.isPending } == true) return

            val directive = when (val verdict = hookFirePoints.judgeGoal(
                config = goalStopConfig(goal.condition),
                lastAssistantText = lastMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.toText(),
            )) {
                // LLM judged the goal achieved -> clear and stop. Guarded so a goal re-armed during the
                // (slow) judge call is not clobbered by this stale "met"; only announce completion when
                // WE actually cleared THIS goal (#364 follow-up: the loop used to end silently).
                GoalVerdict.Met -> {
                    if (session.compareAndSetGoal(goal, null)) {
                        announceGoalAchieved(conversationId, goal.condition)
                    }
                    return
                }
                // The judge gave no usable verdict (hook/provider/parse failure, timeout, empty answer).
                // PAUSE: leave the goal armed (do NOT clear) so a transient failure can't abandon it; a
                // later manual send retries the judge.
                GoalVerdict.Inconclusive -> return
                is GoalVerdict.Continue -> verdict.directive
            }
            // Charge the budget BEFORE injecting/running, with an identity-guarded CAS: if a user-stop
            // or a fresh `/goal` flipped the reference while the judge ran, the CAS fails and we bail
            // WITHOUT injecting a directive or running a turn for a goal that is no longer ours.
            // Charging before the turn means a crash mid-turn can only OVER-count (safe), never let the
            // goal exceed its cap.
            if (!session.compareAndSetGoal(goal, goal.copy(iterationsUsed = goal.iterationsUsed + 1))) {
                return
            }
            saveConversation(
                conversationId,
                conversation.copy(
                    messageNodes = conversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(directive)),
                    ).toMessageNode(),
                ),
            )
            // Bail on a FAILED continuation turn (handleMessageComplete returns false on error; it
            // catches and records instead of throwing). Without this the loop would re-judge the failed
            // transcript and inject again — an unlimited budget would spin on a persistent failure. The
            // goal stays armed so a later manual send can retry; the budget was already charged above.
            if (!handleMessageComplete(conversationId, runTurnEndJobs = false)) return
        }
    }

    /**
     * Append the visible "✓ Goal achieved" notice for [conversationId] (#364 follow-up). Re-reads the
     * live conversation so the notice trails whatever the goal turn left, then persists it.
     */
    private suspend fun announceGoalAchieved(conversationId: Uuid, condition: String) {
        val current = getConversationFlow(conversationId).value
        saveConversation(
            conversationId,
            current.copy(
                messageNodes = current.messageNodes + buildGoalAchievedNotice(condition).toMessageNode(),
            ),
        )
    }

    // ---- Conversation loop (issue #364, `/loop`) ----

    // Serializes /loop supersede (create-then-clear-others) and clear so two concurrent `/loop`
    // commands cannot interleave their create/delete and end up deleting each other's just-created
    // schedule (leaving zero loops). Loop mutations are rare and fast, so a single mutex is enough.
    private val loopMutex = Mutex()

    /**
     * Arm a durable recurring `/loop` (#364 slice 2) on [conversationId]: a CONVERSATION_EVENT schedule
     * whose every-[every]-[unit] fire injects [prompt] as a USER turn back INTO this conversation (via
     * the agent-event queue), so the conversation keeps working on it in-session. Durable (survives
     * process death) with the 1-minute recurring floor (cron granularity).
     *
     * A conversation has at most ONE active loop ("keep alive" is singular), so this SUPERSEDES any
     * existing loop: it creates the new schedule first and only then clears the others — so a REJECTED
     * create (cap reached, sub-floor interval) leaves the existing loop intact instead of wiping it.
     * The target assistant is the conversation's own (unused for CONVERSATION_EVENT — nothing is
     * spawned — but a sensible non-null value).
     */
    suspend fun setLoop(
        conversationId: Uuid,
        every: Int,
        unit: RecurrenceUnit,
        prompt: String,
    ): ScheduleMutationResult = loopMutex.withLock {
        // A loop can be armed on a brand-new conversation that has not been persisted yet:
        // saveConversation deliberately skips an empty new conversation (新会话且为空时不保存), so
        // getConversationById is null when the user opens a fresh chat and types `/loop` as the FIRST
        // action — which previously rejected with "conversation not found". A CONVERSATION_EVENT loop
        // fire delivers back INTO this conversation (deliverLoopFire no-ops if it is absent), so it must
        // exist in Room. Fall back to the live in-session conversation (the active chat always has a
        // valid one via initializeConversation) and persist it so the schedule has a real target.
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: ensureLoopTargetPersisted(conversationId)
            ?: return@withLock ScheduleMutationResult.Rejected("conversation not found")
        val intervalMillis = recurrenceIntervalMillis(every, unit)
        val spec = RecurrenceSpec(every = every, unit = unit)
        val draft = ScheduleDraft(
            targetAssistantId = conversation.assistantId,
            prompt = prompt,
            kind = ScheduleKind.RECURRING,
            firstFireAt = System.currentTimeMillis() + intervalMillis,
            timeZoneId = ZoneId.systemDefault().id,
            recurrenceSpec = Json.encodeToString(RecurrenceSpec.serializer(), spec),
            deliveryMode = DeliveryMode.CONVERSATION_EVENT,
        )
        val result = taskScheduleRepository.create(conversationId, ScheduleOwner.USER, draft)
        if (result is ScheduleMutationResult.Accepted) {
            clearLoopSchedules(conversationId, keep = result.snapshot.id)
        }
        result
    }

    /**
     * Persist the live in-session conversation as a `/loop` target when arming a loop on a brand-new
     * conversation that has not been auto-saved yet (empty new conversations are not persisted). Returns
     * the conversation now in Room, or null when there genuinely is none.
     *
     * - A tombstoned (being-deleted) conversation returns null rather than RESURRECTING it via insert.
     * - The insert is race-safe: a concurrent first persist (e.g. a near-simultaneous message send) makes
     *   the @Insert conflict; the fallback re-reads the row the other writer just created. That re-read
     *   IS the recovery, not a swallowed error — a genuine failure leaves no row and returns null.
     */
    private suspend fun ensureLoopTargetPersisted(conversationId: Uuid): Conversation? {
        if (isConversationTombstoned(conversationId)) return null
        val live = getConversationFlow(conversationId).value
        return runCatching { conversationRepo.insertConversation(live); live }
            .getOrElse { conversationRepo.getConversationById(conversationId) }
    }

    /** Clear ALL `/loop` schedules on [conversationId] (the `/loop` / `/loop clear` path). Idempotent. */
    suspend fun clearLoop(conversationId: Uuid) = loopMutex.withLock {
        clearLoopSchedules(conversationId, keep = null)
    }

    /** Delete every CONVERSATION_EVENT schedule on [conversationId] except [keep] (if any). */
    private suspend fun clearLoopSchedules(conversationId: Uuid, keep: Uuid?) {
        taskScheduleRepository.list(conversationId)
            .filter { it.deliveryMode == DeliveryMode.CONVERSATION_EVENT && it.id != keep }
            .forEach { taskScheduleRepository.delete(conversationId, it.id) }
    }

    /**
     * Deliver one CONVERSATION_EVENT (`/loop`) fire by durably enqueuing the loop prompt as an
     * agent-event into [conversationId] (#364 slice 2). Bound into [ScheduleFireRunner] at the root.
     * SUSPEND (not the fire-and-forget [enqueueAgentEvent]) so the fire path persists the event WITHIN
     * its own coroutine before clearing the schedule's in-flight marker — the worker's process may die
     * the instant `doWork` returns. [dedupeKey] is the per-fire run id, so a duplicate worker that lost
     * the claim never double-injects (the queue's unique enqueue collapses it). After persisting, kick
     * an idle drain so an idle conversation gets the prompt now; a busy one gets it at turn-end.
     */
    suspend fun deliverLoopFire(conversationId: Uuid, prompt: String, scheduleId: Uuid, dedupeKey: String) {
        // The enqueue is the durable goal: a failure (or cancellation) MUST propagate so the fire is
        // NOT advanced as a successful delivery (the worker maps it to Result.failure; the fire runner's
        // NonCancellable finally still clears the schedule's in-flight marker, so nothing pins). This
        // mirrors the DETACHED path, where a throwing run() also propagates rather than being swallowed.
        val persisted = agentEventStore.enqueue(
            conversationId = conversationId,
            kind = LoopFire.KIND,
            payloadJson = LoopFire.payloadJson(prompt, scheduleId),
            dedupeKey = dedupeKey,
        )
        // The event is now DURABLE; the immediate drain kick is a best-effort optimization (it will
        // otherwise drain at the next idle turn-end / cold-start replay), so a kick failure must not
        // fail an already-persisted fire. Cancellation still propagates.
        if (persisted) {
            try {
                maybeDrainAgentEventsWhenIdle(conversationId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "loop fire drain kick failed (event already durable) for $conversationId", t)
            }
        }
    }

    private fun recurrenceIntervalMillis(every: Int, unit: RecurrenceUnit): Long = when (unit) {
        RecurrenceUnit.MINUTES -> every.toLong() * 60_000L
        RecurrenceUnit.HOURS -> every.toLong() * 3_600_000L
        RecurrenceUnit.DAYS -> every.toLong() * 86_400_000L
    }

    // ---- 异步代理事件队列 (issue #290) ----

    /**
     * Enqueue a durable agent-event for a conversation (issue #290). The event is persisted PENDING;
     * the visible synthetic message is NOT appended here (design correction #1) — appending mid-turn
     * would corrupt [continueAfterStopHookIfRequested]'s `lastOrNull()` branch, and deferring the
     * append lets one store transaction encode AT_MOST_ONCE.
     *
     * Delivery is gated: a drain is kicked NOW only when policy allows ([AgentEventQueueReducer.canDrain]
     * over the live [turnGateState] — no active generation AND no pending approval). Otherwise the
     * event waits and is drained at the next turn-end seam (IDLE_GATING / NO_DOUBLE_GENERATION).
     *
     * It MUST NOT go through [sendMessage]/[launchGenerationEntry] — those cancel the previous
     * generation (the central re-entrancy hazard); the idle-time drain calls [handleMessageComplete]
     * directly, never the superseding launcher.
     */
    fun enqueueAgentEvent(
        conversationId: Uuid,
        kind: String,
        payloadJson: String,
        dedupeKey: String,
    ) = agentEventDrainCoordinator.enqueue(conversationId, kind, payloadJson, dedupeKey)

    /**
     * Deliver a freshly-enqueued event immediately IFF the conversation is idle, as a generation that
     * is TRACKED in the session. The drain claims the session generation slot
     * ([ConversationSession.tryClaimIdleGenerationSlot]) so it is visible to idle-gating
     * ([ConversationSession.isGenerating]) and to stop/cancel: a concurrent enqueue then observes
     * isGenerating and leaves its event buffered, so no second drain runs concurrently
     * (NO_DOUBLE_GENERATION). The claim is idle-guarded and race-safe — it NEVER cancels a live
     * generation, so a background event can never supersede a user turn; when not idle the event
     * stays buffered (PENDING) for the next genuine turn-end drain.
     */
    fun maybeDrainAgentEventsWhenIdle(conversationId: Uuid) =
        agentEventDrainCoordinator.maybeDrainWhenIdle(conversationId)

    /**
     * Load the persisted conversation into a BLANK session before an agent-event drain appends to it.
     * Without this, a cold-start replay (or an enqueue for a conversation the UI never opened) drains a
     * placeholder session whose save would wipe the persisted message nodes (review finding: data
     * loss). Only a blank session is hydrated — a live, already-loaded conversation is never clobbered.
     */
    private suspend fun hydrateSessionFromStoreIfBlank(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.messageNodes.isNotEmpty()) return
        val persisted = conversationRepo.getConversationById(conversationId) ?: return
        if (persisted.messageNodes.isNotEmpty()) {
            session.state.value = persisted
        }
    }

    /**
     * The live turn-gate state for the agent-event drain policy ([AgentEventQueueReducer.canDrain]).
     * GENERATING when a provider collection is in flight; else PAUSED_FOR_APPROVAL when a pending
     * tool approval is outstanding (an approval pause has NO active job yet still owns a pending
     * step — the IDLE_GATING first-break point); else IDLE.
     */
    private fun turnGateState(conversationId: Uuid): TurnGateState {
        val session = getOrCreateSession(conversationId)
        return when {
            session.isGenerating -> TurnGateState.GENERATING
            conversationHasPendingToolApproval(session.state.value) -> TurnGateState.PAUSED_FOR_APPROVAL
            else -> TurnGateState.IDLE
        }
    }

    /**
     * Drain ONE pending agent-event at a turn-end seam (issue #290). Claims the oldest PENDING event,
     * appends its visible synthetic [MessageRole.ASSISTANT] message (carrying the SYNTHETIC_DISTINCTNESS
     * part metadata so later FTS/stats/sanitizer filters have a stable hook), and marks it CONSUMED —
     * all in ONE store transaction, so a second drain or a startup replay racing the same row is a
     * no-op (AT_MOST_ONCE). Then continues the model via [handleMessageComplete] with
     * `runTurnEndJobs = false` (NOT a superseding launcher).
     *
     * Guarded exactly like [continueAfterStopHookIfRequested]: if the last message holds a pending
     * tool the turn is NOT over (the user owns the next step), so no drain — the deferred event waits
     * for a genuine idle turn-end. One event per continuation (productDecision #5): the nested
     * completion runs with `runTurnEndJobs = false`, so it does not recurse into another drain.
     */
    private suspend fun drainAgentEventsAtTurnEnd(conversationId: Uuid) =
        agentEventDrainCoordinator.drainAtTurnEnd(conversationId)

    /** Deliver (or terminalize) the single oldest pending event; returns true if more may remain. */
    private suspend fun drainOneAgentEvent(conversationId: Uuid): Boolean {
        val conversation = getConversationFlow(conversationId).value
        val lastMessage = conversation.currentMessages.lastOrNull()
        if (lastMessage?.parts?.any { it is UIMessagePart.Tool && it.isPending } == true) return false

        val outcome = agentEventStore.claimAndAppendAndConsume(conversationId) { event ->
            val persistedConversation = conversationRepo.getConversationById(conversationId)
            if (isConversationTombstoned(conversationId) || persistedConversation == null) {
                return@claimAndAppendAndConsume ClaimAppendAction.Terminalize(
                    AgentEventTerminalStatus.CANCELLED,
                )
            }

            if (runCatching { Json.parseToJsonElement(event.payloadJson) }.isFailure) {
                return@claimAndAppendAndConsume ClaimAppendAction.Terminalize(
                    AgentEventTerminalStatus.FAILED,
                )
            }

            val alreadyVisible = findSyntheticNodeAndMessageIds(persistedConversation, event)
            if (alreadyVisible != null) {
                val (nodeId, messageId) = alreadyVisible
                return@claimAndAppendAndConsume ClaimAppendAction.Append(
                    synthetic = SyntheticAppendResult(
                        syntheticNodeId = nodeId.toString(),
                        syntheticMessageId = messageId.toString(),
                    ),
                    continueGeneration = false,
                )
            }

            if (event.kind == ShellCompletion.KIND) {
                val taskId = shellCompletionTaskId(event.payloadJson)
                val anchor = taskId?.let { id ->
                    shellRunStore.getToolAnchor(id)
                        ?: findDeferredShellToolAnchors(persistedConversation).firstOrNull { it.taskId == id }?.anchor
                }
                val resolution = anchor?.let {
                    resolveDeferredShellCompletion(
                        conversation = persistedConversation,
                        anchor = it,
                        payloadJson = event.payloadJson,
                    )
                }
                if (resolution != null) {
                    conversationRepo.updateMessageNode(resolution.conversation, resolution.node)
                    return@claimAndAppendAndConsume ClaimAppendAction.Append(
                        synthetic = SyntheticAppendResult(
                            syntheticNodeId = resolution.node.id.toString(),
                            syntheticMessageId = resolution.messageId.toString(),
                        ),
                        continueGeneration = resolution.continueGeneration,
                    )
                }
            }

            if (event.kind == SubagentCompletion.KIND) {
                // Deliver the outcome as a trailing USER notice. A background spawn's parent turn already
                // CONTINUED past the running marker and ended on an ASSISTANT message, so resolving the
                // buried tool and continuing from it would (a) leave the conversation ending on an
                // assistant turn — the provider rejects the continuation ("must end with a user message"
                // / no assistant prefill) — and (b) never tell the parent the outcome. The trailing USER
                // notice ends the turn on a user message AND is the parent's notification (so it need not
                // poll the run itself). Contrast deferred shell: it BLOCKS the turn, so its tool IS the
                // last message and resolve-then-continue is valid.
                val taskId = shellCompletionTaskId(event.payloadJson)
                val anchor = taskId?.let { id ->
                    taskRunStore.getToolAnchor(id)
                        ?: findBackgroundSubagentToolAnchors(persistedConversation)
                            .firstOrNull { it.first == id }?.second
                }
                // SELECTED-BRANCH GUARD: only auto-continue when the spawn's anchor lives in the node's
                // CURRENTLY SELECTED branch. If the user regenerated/switched away from the branch that
                // spawned this subagent, still deliver the notice (history + notification), but do NOT
                // drive a continuation on the now-current branch (it never spawned this run). A read-only
                // check — no resolve write — so delivery is a SINGLE append (failure-atomic: a thrown
                // append cannot leave a half-applied resolve behind). The agent/task tool bubble keeps its
                // running marker; the USER notice is the authoritative outcome.
                val onSelectedBranch = anchor == null ||
                    isBackgroundAnchorOnSelectedBranch(persistedConversation, anchor)

                val notice = buildSubagentCompletionNotice(event)
                val noticeNode = notice.toMessageNode()
                conversationRepo.appendMessageNode(
                    persistedConversation.copy(messageNodes = persistedConversation.messageNodes + noticeNode),
                    noticeNode,
                )
                return@claimAndAppendAndConsume ClaimAppendAction.Append(
                    synthetic = SyntheticAppendResult(
                        syntheticNodeId = noticeNode.id.toString(),
                        syntheticMessageId = notice.id.toString(),
                    ),
                    continueGeneration = onSelectedBranch,
                )
            }

            if (event.kind == LoopFire.KIND) {
                // A /loop fire (#364 slice 2) is a fresh USER prompt that DRIVES a turn — unlike the
                // subagent notice (which reports a finished detached run), so there is no tool anchor to
                // resolve and no selected-branch guard: a loop always continues generation. A malformed/
                // blank payload terminalizes FAILED rather than injecting an empty user turn.
                val prompt = LoopFire.promptOf(event.payloadJson)
                    ?: return@claimAndAppendAndConsume ClaimAppendAction.Terminalize(
                        AgentEventTerminalStatus.FAILED,
                    )
                val loopMessage = buildLoopFireMessage(event, prompt)
                val loopNode = loopMessage.toMessageNode()
                conversationRepo.appendMessageNode(
                    persistedConversation.copy(messageNodes = persistedConversation.messageNodes + loopNode),
                    loopNode,
                )
                return@claimAndAppendAndConsume ClaimAppendAction.Append(
                    synthetic = SyntheticAppendResult(
                        syntheticNodeId = loopNode.id.toString(),
                        syntheticMessageId = loopMessage.id.toString(),
                    ),
                    continueGeneration = true,
                )
            }

            val syntheticMessage = buildSyntheticAgentEventMessage(event)
            val syntheticNode = syntheticMessage.toMessageNode()
            conversationRepo.appendMessageNode(
                persistedConversation.copy(messageNodes = persistedConversation.messageNodes + syntheticNode),
                syntheticNode,
            )
            ClaimAppendAction.Append(
                synthetic = SyntheticAppendResult(
                    syntheticNodeId = syntheticNode.id.toString(),
                    syntheticMessageId = syntheticMessage.id.toString(),
                ),
            )
        }

        return when (outcome) {
            is ClaimOutcome.Delivered -> {
                refreshSessionAndFts(conversationId)
                // Continue the model on the just-appended synthetic message. runTurnEndJobs = false:
                // the continuation does not own the turn-end-job launch and does not itself recurse
                // into a drain (one event per continuation, productDecision #5); the outer loop drains
                // the next pending event after it returns.
                if (outcome.continueGeneration) {
                    val completed = handleMessageComplete(conversationId, runTurnEndJobs = false)
                    // The /goal autonomous loop (#364 slice 3): an agent-event continuation — a /loop
                    // fire, a background subagent/shell completion — is a genuine turn end too, so a goal
                    // armed in THIS live session must advance off it, not just off a user send / approval
                    // (the three turn-end seams now run the goal loop uniformly). Gated on success, and a
                    // cheap no-op when no goal is armed (returns after the activeGoal null-check), so the
                    // existing agent-event paths are unchanged when /goal is unused. The goal is in-memory
                    // (session-scoped), so a cold-start replay never runs it — correct, /goal dies with the
                    // process. The nested goal turns also use runTurnEndJobs = false, so no re-drain.
                    continueGoalLoopIfActive(conversationId, precedingTurnSucceeded = completed)
                }
                true // a delivered event may be followed by more pending ones — keep draining
            }
            // A tombstoned/malformed event was terminalized (consumed, not delivered); more may remain.
            is ClaimOutcome.Terminalized -> true
            // Empty queue, or a concurrent drain won the claim (it owns the rest of the chain).
            ClaimOutcome.Empty, ClaimOutcome.Lost -> false
        }
    }

    private suspend fun refreshSessionAndFts(conversationId: Uuid) {
        val persistedConversation = conversationRepo.getConversationById(conversationId) ?: return
        updateConversationStateOnly(conversationId, persistedConversation)
        conversationRepo.indexConversationById(conversationId)
    }

    private fun findSyntheticNodeAndMessageIds(
        conversation: Conversation,
        event: AgentEventEntity,
    ): Pair<Uuid, Uuid>? =
        conversation.messageNodes
            .asSequence()
            .flatMap { node ->
                node.messages
                    .asSequence()
                    .mapNotNull { message ->
                        val marker = message.syntheticAgentEventMarker()
                        if (marker?.first == event.kind && marker.second == event.id) {
                            node.id to message.id
                        } else {
                            null
                        }
                    }
            }
            .firstOrNull()

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
        val model = settings.getChatModelForAssistant(assistant) ?: return

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
            addError(it, conversationId, title = strings.getString(R.string.error_title_auto_compact))
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
        launchGenerationEntry(
            conversationId = conversationId,
            errorTitle = strings.getString(R.string.error_title_regenerate_message),
        ) { session ->
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
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ): Job? {
        // A forwarded CHILD approval (namespaced taskId/childToolCallId, Gap A) resolves IN
        // PLACE: the parent generation is alive, suspended inside the spawn tool waiting on this
        // very decision — launchGenerationEntry's cancel-previous would kill it, and the
        // continue-generation tail below belongs to the parent path only. A stale child id (its
        // waiter already gone) still gets its part state finalized, nothing else.
        if (TaskApprovalRouter.isNamespacedChildApprovalId(toolCallId)) {
            appScope.launch {
                try {
                    resolveChildApproval(conversationId, toolCallId, approved, reason, answer)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.e(TAG, "child approval resolution failed", error)
                    addError(error, conversationId, title = strings.getString(R.string.error_title_tool_approval))
                }
            }
            return null
        }

        return launchGenerationEntry(
            conversationId = conversationId,
            errorTitle = strings.getString(R.string.error_title_tool_approval),
        ) { session ->
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

            // If the resolved call is an authoring tool, re-arm the lease so the continuation that
            // executes the approved write re-assembles WITH create_skill/update_skill in the pool (the
            // arming turn already consumed the original lease; resume runs in this fresh job). Harmless
            // when denied (the tool is present but never called) or when no continuation runs (the
            // job-completion catch-all clears it).
            val resumedToolName = updatedNodes
                .firstNotNullOfOrNull { node ->
                    node.currentMessage.parts.firstOrNull {
                        it is UIMessagePart.Tool && it.toolCallId == toolCallId
                    } as? UIMessagePart.Tool
                }
                ?.toolName
            resumedToolName?.let { skillAuthoringSpecForToolName(it) }?.let { session.armSkillAuthoring(it) }

            // Check if there are still pending tools
            val hasPendingTools = updatedNodes.any { node ->
                node.currentMessage.parts.any { part ->
                    part is UIMessagePart.Tool && part.isPending
                }
            }

            // Only continue generation when all pending tools are handled. Route through the SAME
            // sequenceTurnEnd contract as the sendMessage path (issue #364): an approved tool that
            // ends the turn must re-enter the /goal autonomous loop BEFORE the agent-event drain and
            // the single turn-end job launch — otherwise the goal strands until the next ordinary send.
            // The assistant.hooks Stop hook stays single-shot and is deliberately NOT fired here (a
            // HITL approval break is not a turn end for it); only the re-entrant goal loop resumes.
            if (!hasPendingTools) {
                sequenceTurnEnd(
                    complete = { handleMessageComplete(conversationId, runTurnEndJobs = false) },
                    continueAfterStopHook = { completed ->
                        // /goal resumes only after a SUCCESSFUL approval-resume turn (#364). The drain
                        // is gated on success too, restoring the pre-#364 approval-resume behavior where
                        // handleMessageComplete(runTurnEndJobs = true) drained only inside onSuccess.
                        continueGoalLoopIfActive(conversationId, precedingTurnSucceeded = completed)
                        if (completed) drainAgentEventsAtTurnEnd(conversationId)
                    },
                    launchTurnEndJobs = { launchTurnEndJobs(conversationId) },
                )
            }
        }
    }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        handleToolApprovalReturningJob(conversationId, toolCallId, approved, reason, answer)
    }

    /**
     * Resolve one forwarded child approval (Gap A): write the decision onto the visible pending
     * part (so the live transcript records what was decided), then release the waiting child
     * through [pendingChildApprovals] with the FULL decision — an answer travels as
     * [TaskApprovalDecision.Answered] and becomes the child's tool
     * result without executing anything (ask_user-class tools). The resolve is a no-op for a
     * dead waiter; the part update still applies, finalizing a stale pending record cosmetically.
     */
    private suspend fun resolveChildApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ) {
        val state = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        val session = getOrCreateSession(conversationId)
        saveConversation(conversationId, resolveChildApprovalPart(session.state.value, toolCallId, state))
        val decision = when {
            answer != null -> TaskApprovalDecision.Answered(answer)
            approved -> TaskApprovalDecision.Approved
            else -> TaskApprovalDecision.Denied(reason)
        }
        pendingChildApprovals.resolve(toolCallId, decision)
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
        // Per-run-transient (#187 v2): PEEK the pending grant to derive THIS generation's lease — do
        // NOT consume (null) it here. A per-run grant is scoped to the whole TURN, and a turn can span
        // more than one lease entry: an ASK-guardrail approval breaks the turn (a Pending tool waits
        // for the user) and the lease tears down, then the approval-resume re-enters this lease and
        // must re-mint the SAME guard from the SAME grant (finding 3). Consuming on entry destroyed the
        // grant on the first pass, so the resume minted no guard, assembled no ui_* tools, and the
        // approved call errored "Tool not found". The grant's clearing instead happens in the finally,
        // gated on the real turn boundary (no pending approval) — which still prevents a per-run grant
        // from leaking onto a LATER, unrelated turn (the transient-grant invariant the consume-once
        // step was protecting, just keyed on the correct boundary).
        val pendingGrant = session.pendingAutomationGrant
        // Root cause (#187 v2): the lease used to mint `surface = emptySet()` UNCONDITIONALLY, so the
        // guard DENIED every request — the automation subsystem was inert. The capability now derives
        // from the EFFECTIVE grant (consumed per-run grant ?: the assistant's standing
        // `automationGrant`), filling exactly the surface/verbs/sinks/TTL/steps the user approved. An
        // empty/absent grant ⇒ `effectiveAutomationCapability` returns null ⇒ NO guard is minted ⇒ the
        // guard-closed-over tools still DENY (no regression). SUBMIT is stripped inside the derivation
        // (submit-class stays the stricter, separate opt-in), so a grant can never bypass the confirm
        // gate. The STOP overlay below remains mandatory for ANY minted guard.
        //
        // Finding 1: `uiAutomationEnabled` is passed into the derivation as the single master gate
        // for BOTH grant sources. A per-run grant can override the standing grant only after that gate
        // is open; with the switch off, no automation guard is minted.
        // YOLO ("bypass all restriction") is honored only after the user accepted the danger once: a
        // global acknowledgement persisted in DisplaySetting. Read it here so the derivation can gate
        // the standing grant's yolo flag (a pending grant can never widen to YOLO regardless).
        val yoloAcknowledged = settingsStore.settingsFlow.value.displaySetting.automationYoloAcknowledged
        val capability: Capability? = effectiveAutomationCapability(
            pendingGrant = pendingGrant,
            assistantGrant = assistant.automationGrant,
            masterSwitchEnabled = assistant.uiAutomationEnabled,
            sessionId = conversationId.toString(),
            now = trustClock.now(),
            yoloAcknowledged = yoloAcknowledged,
            // Flavor-gate YOLO at the derivation: false on Play, so restored/imported acknowledged+yolo
            // state can never mint an unrestricted capability there (the empty UI seam is not enough).
            yoloSupported = AUTOMATION_YOLO_SUPPORTED,
        )
        val automationGuard: CapabilityGuard? = capability?.let { cap ->
            CapabilityGuard(capability = cap, clock = trustClock)
                .also { guard -> session.activeAutomationGuard = guard }
        }
        if (automationGuard != null && !automationActivation.activate(conversationId)) {
            if (session.activeAutomationGuard === automationGuard) session.clearAutomationLeaseState()
            automationGuard.revoke()
            throw IllegalStateException(strings.getString(R.string.automation_kill_switch_unavailable))
        }
        // Tracks a NORMAL return from [block] vs an exceptional exit. Only a normal completion that
        // PAUSED on an approval is a resumable break; a CancellationException (Stop, or a newer entry
        // superseding this job via the previousJob.join barrier) is a turn ABANDON, after which the
        // grant must NOT survive — otherwise the next, unrelated turn inherits this run's authorization.
        var completedNormally = false
        try {
            val result = block(automationGuard)
            completedNormally = true
            return result
        } finally {
            if (automationGuard != null && session.activeAutomationGuard === automationGuard) {
                // Keep the per-run grant alive ONLY when the turn is still open AND was not abandoned:
                // a normal completion that left a Pending tool approval is the ASK-guardrail break
                // (finding 3) — the resume re-enters this lease and re-mints the guard + STOP overlay
                // from the preserved grant. Every OTHER terminal path — normal finish with no pending
                // approval, an error, or a cancellation (Stop / supersede / regenerate) — clears the
                // grant so a one-run authorization can never scope a LATER, unrelated turn. The
                // per-generation guard and the STOP overlay are torn down regardless.
                val preserveGrant = shouldPreservePerRunGrant(
                    completedNormally = completedNormally,
                    hasPendingApproval = conversationHasPendingToolApproval(session.state.value),
                )
                session.clearAutomationLeaseState(preserveGrant = preserveGrant)
                automationActivation.deactivate(conversationId)
            }
        }
    }

    /**
     * Open a UI-automation lease for a SPAWNED SUBAGENT (Option B — subagent UI automation). Unlike
     * the main lease, the capability is derived from the SUBAGENT assistant's OWN grant (not the
     * parent's and not attenuated from it), so a parent with no automation can delegate device work to
     * an automation specialist subagent. Authority is still fully gated: the same one-time danger
     * acknowledgement + flavor support ([effectiveAutomationCapability]'s yolo gates), the ttl/step
     * lease, the kill switch, and the submit-class confirm all apply.
     *
     * Lifecycle (mirrors [withAutomationLease] but keyed by a per-spawn lease id, and the guard lives
     * in the session's SUBAGENT-guard set so the kill-switch sweep reaches it even when the parent has
     * no main lease):
     *  - derive the capability from `sub`'s grant; if none usable ⇒ run with NO automation tools.
     *  - require a live a11y backend ([AutomationRuntimeRegistry.core]); absent ⇒ no automation tools.
     *  - FAIL CLOSED: if the STOP overlay cannot be activated, do NOT expose automation tools (the
     *    subagent must never observe/act without a reachable kill switch) — it runs without them.
     *  - register the guard on [session] (kill-switch reach), supply `ui_*` tools, and on EVERY exit
     *    (normal, error, cancellation) deregister + deactivate the overlay + revoke the guard.
     */
    private suspend fun openSubagentAutomationLease(
        sub: Assistant,
        session: ConversationSession,
        block: suspend (autoTools: List<Tool>) -> TaskRunResult,
    ): TaskRunResult {
        val settings = settingsStore.settingsFlow.value
        val leaseKey = Uuid.random()
        val capability = effectiveAutomationCapability(
            // A subagent has no per-run/pending grant; its authority is its own STANDING grant only.
            pendingGrant = null,
            assistantGrant = sub.automationGrant,
            masterSwitchEnabled = sub.uiAutomationEnabled,
            sessionId = leaseKey.toString(),
            now = trustClock.now(),
            yoloAcknowledged = settings.displaySetting.automationYoloAcknowledged,
            yoloSupported = AUTOMATION_YOLO_SUPPORTED,
        ) ?: return block(emptyList())

        // No live a11y backend ⇒ ui_observe/act cannot run; the subagent proceeds without automation.
        val core = automationRegistry.core() ?: return block(emptyList())

        val guard = CapabilityGuard(capability = capability, clock = trustClock)
        // The ordering-sensitive lease (register-before-activate, fail-closed, release-on-every-exit)
        // is [openSubagentAutomationLeaseOnSession] so the guard-before-overlay invariant is testable.
        return openSubagentAutomationLeaseOnSession(
            session = session,
            guard = guard,
            leaseKey = leaseKey,
            activation = automationActivation,
            onNoKillSwitch = { block(emptyList()) },
            onActive = {
                val autoTools = getUiAutomationTools(
                    guard = guard,
                    core = core,
                    foregroundPkg = { automationRegistry.foregroundPackage() },
                    // Same rule as the main turn: YOLO (includeHost) auto-approves the submit-class
                    // confirm; otherwise the overlay channel, or fail-closed AlwaysDeny if none.
                    confirm = if (guard.includeHost) {
                        AlwaysConfirm
                    } else {
                        automationRegistry.confirmChannel() ?: AlwaysDeny
                    },
                )
                block(autoTools)
            },
        )
    }

    // ---- 处理消息补全 ----

    // Returns whether the completion ran to success — the sendMessage path needs it to decide
    // whether the deferred turn-end jobs may launch (see [sequenceTurnEnd]).
    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        runTurnEndJobs: Boolean = true,
    ): Boolean {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantByIdOrCurrent(initialConversation.assistantId)
        val model = settings.getChatModelForAssistant(assistant) ?: return false

        val senderName = resolveSenderName(
            useAssistantAvatar = assistant.useAssistantAvatar,
            assistantName = assistant.name,
            modelDisplayName = model.displayName,
        ) { strings.getString(R.string.assistant_page_default_assistant) }

        return runCatching {

            // reset suggestions
            updateConversationStateOnly(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool — warn when the model can't run the tools the turn would otherwise carry.
            // The MCP enumeration is the lazy probe, run only when the cheaper checks don't decide it.
            if (shouldWarnToolUnavailable(
                    modelSupportsTools = model.abilities.contains(ModelAbility.TOOL),
                    webSearchEnabled = settings.enableWebSearch,
                    hasMcpTools = { mcpManager.getAllAvailableTools(assistant).isNotEmpty() },
                )
            ) {
                addError(
                    IllegalStateException(strings.getString(R.string.tools_warning)),
                    conversationId,
                    title = strings.getString(R.string.error_title_tool_unavailable)
                )
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)

            // Consume the skill-authoring lease for THIS turn's pool exactly once. handleMessageComplete is
            // the single tool-assembly seam every turn type reaches (sends, approval-resume, regenerate,
            // /loop + agent-event drains — some of which run OUTSIDE launchGenerationEntry), so taking the
            // lease HERE makes the write tool available to precisely the arming turn (and an authoring
            // approval-resume, which re-arms first) and never bleed into a later autonomous continuation
            // that merely reuses this session.
            val turnSkillAuthoring = session.consumeSkillAuthoring()

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
            // Gap A: the parent's approval surface for forwarded CHILD approvals. requestApproval
            // makes the request visible FIRST — a pending UIMessagePart.Tool keyed by the
            // namespaced taskId/childToolCallId, anchored under the running `task` step
            // (TASK_APPROVAL_VISIBLE: no hidden suspension) — then parks on pendingChildApprovals
            // until handleToolApproval resolves it. Fail closed: with no visible task step to
            // anchor on, the request is DENIED rather than suspended invisibly. Cancellation of
            // the generation cancels the waiting child through the suspended await itself.
            val childApprovalSurface = object : ParentApprovalSurface {
                override suspend fun requestApproval(
                    namespacedToolCallId: String,
                    request: TaskApprovalRequest,
                ): TaskApprovalDecision {
                    // Register BEFORE making the request visible (review mustFix #1): the moment
                    // the part is on screen a decision can arrive, and a resolve that finds no
                    // waiter is dropped — the child would then suspend forever on a decision the
                    // user already made. An early resolve now completes the registered deferred
                    // and await returns immediately.
                    pendingChildApprovals.register(namespacedToolCallId)
                    val visible = try {
                        injectChildApprovalPart(
                            conversation = getOrCreateSession(conversationId).state.value,
                            namespacedToolCallId = namespacedToolCallId,
                            toolName = request.toolName,
                            argumentsJson = request.argumentsJson,
                        )?.also { saveConversation(conversationId, it) }
                    } catch (error: Throwable) {
                        pendingChildApprovals.abandon(namespacedToolCallId)
                        throw error
                    }
                    if (visible == null) {
                        pendingChildApprovals.abandon(namespacedToolCallId)
                        return TaskApprovalDecision.Denied(
                            "no visible task step to anchor the approval on"
                        )
                    }
                    return pendingChildApprovals.await(namespacedToolCallId)
                }
            }

            val appToolCatalog = AppToolCatalog(
                // Non-MCP/non-spawn base pool: search/local/workspace/ui-automation/skills. The seam's
                // (target, mode) args are unused on the main turn — production only ever invokes this
                // catalog with targetAssistant == assistant.toAssistantConfig(), so the in-scope app
                // `assistant` is the faithful source (no lossy AssistantConfig round-trip).
                baseTools = { _, _ ->
                    // The (target, mode) args are unused on the main turn — production only ever invokes
                    // this catalog with targetAssistant == assistant.toAssistantConfig(), so the in-scope
                    // app `assistant` is the faithful source. The gate policy (which capability under which
                    // condition, and in which order) lives in [assembleBaseTools] (#360 P5); each producer
                    // below is invoked LAZILY behind its gate, so a disabled capability is never built.
                    assembleBaseTools(
                        webSearchEnabled = settings.enableWebSearch,
                        // Deferred managed-backend tool — present only when the managed provider is
                        // configured, so a chat with a model that lacks native web-fetch (e.g. Anthropic)
                        // can still reach it. fetch is Codex-only.
                        managedFetchAvailable = settings.hasChatGpt(),
                        skillsEnabled = assistant.enabledSkills.isNotEmpty(),
                        // Skill-authoring write tools are offered ONLY for the turn that a /create_skill or
                        // /update_skill slash command armed (the lease was consumed into turnSkillAuthoring
                        // above), so they are absent on every ordinary or autonomous turn.
                        skillAuthoringActive = turnSkillAuthoring != null,
                        searchTools = { createSearchTools(settings) },
                        fetchTools = { createFetchTools(settings) },
                        // image-gen works through EITHER managed image provider (Codex gpt-image-2 or Gagy
                        // Gemini) and self-gates to empty when neither is set, so it is unconditional.
                        imageGenTools = { createImageGenTools(settings, filesManager, providerManager) },
                        localTools = { localTools.getTools(assistant.localTools) },
                        workspaceTools = {
                            createWorkspaceTools(assistant.workspaceId?.toString(), conversationId, workspaceRepository)
                        },
                        uiAutomationTools = {
                            // ui_observe (#187 v1) + nav act verbs ui_scroll/ui_global (#198 slice 8) +
                            // ui_set_text (slice 9) + ui_tap (slice 10), all over the same core. Read the
                            // a11y core ONCE here (this position, after the suspend workspace read) and use
                            // that single snapshot for both the null-check and the build — empty surface
                            // unless connected. Each tool then authorizes via the closed-over guard before
                            // the backend (S2).
                            automationRegistry.core()?.let { automationCore ->
                                getUiAutomationTools(
                                    guard = automationGuard,
                                    core = automationCore,
                                    foregroundPkg = { automationRegistry.foregroundPackage() },
                                    // The out-of-band confirm for a dangerous (submit-class) tap (#198 slice
                                    // 11). YOLO (`includeHost` on the minted guard) auto-approves it: the
                                    // user already accepted the danger, so the human confirm step is
                                    // short-circuited — the SubmitClassifier + guard still run in-path (the
                                    // SUBMIT sink is still derived and budget-checked) and the kill-switch
                                    // overlay stays mandatory; only the prompt is skipped. Gated strictly on
                                    // the YOLO capability, NOT a fallback for a missing overlay. Scoped mode
                                    // fails closed: if no overlay-backed channel is reachable (a11y service
                                    // not connected), a dangerous sink can never be confirmed, so it is
                                    // always denied — never silently auto-confirmed.
                                    confirm = if (automationGuard?.includeHost == true) {
                                        AlwaysConfirm
                                    } else {
                                        automationRegistry.confirmChannel() ?: AlwaysDeny
                                    },
                                )
                            } ?: emptyList()
                        },
                        skillTools = {
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        },
                        skillAuthoringTools = {
                            // The lease was consumed into turnSkillAuthoring for this turn; build the
                            // matching write tool from it (absent on every non-authoring turn).
                            turnSkillAuthoring?.let { spec ->
                                createSkillAuthoringTools(spec = spec, skillManager = skillManager)
                            } ?: emptyList()
                        },
                    )
                },
                // MCP tools are selected off the TARGET assistant the catalog passes (the §C3 by-target
                // invariant), named `mcp__…` by the catalog's mapMcpTool. Honoring `target` keeps this
                // correct by construction if the catalog is ever reused for a subagent pool, not just the
                // current main turn (where target == this assistant's config).
                mcpToolsForAssistant = { target -> mcpManager.getAllAvailableTools(target) },
                mcpCall = { sid, name, args -> mcpManager.callTool(sid, name, args) },
                // Human server name → the readable model-facing MCP name `mcp__<serverName>__<tool>`
                // (issue #356 #2).
                mcpServerName = { serverId -> mcpManager.getServerName(serverId) },
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
                            coordinator = taskCoordinator,
                            parentModelId = parentModelId,
                            settings = settings,
                            registry = executionHandles,
                            buildSubagentTools = { sub, handle ->
                                buildList {
                                    addAll(localTools.getTools(sub.localTools))
                                    // Mirror the main turn's non-registry tool injections so a subagent
                                    // reaches the same capabilities it's configured for (the spawn path
                                    // bypasses AppToolCatalog, so these would otherwise be main-turn-only):
                                    //  - web search, gated on the same global settings.enableWebSearch;
                                    //  - the SUB's own workspace (its workspaceId) shell/file tools, which
                                    //    self-gate to empty without a bound workspace and by product flavor.
                                    if (settings.enableWebSearch) {
                                        addAll(createSearchTools(settings))
                                    }
                                    addAll(
                                        createWorkspaceTools(
                                            sub.workspaceId?.toString(),
                                            conversationId,
                                            workspaceRepository,
                                        )
                                    )
                                    if (sub.enabledSkills.isNotEmpty()) {
                                        addAll(
                                            createSkillTools(
                                                enabledSkills = sub.enabledSkills,
                                                allSkills = skillManager.listSkills(),
                                                skillManager = skillManager,
                                            )
                                        )
                                    }
                                    // Readable, collision-free MCP names for the subagent pool, mapped at
                                    // the pool level so de-dup is consistent (issue #356 #2). Same naming
                                    // path as the main-agent pool (AppToolCatalog) — must not drift.
                                    addAll(
                                        buildMcpTools(
                                            entries = mcpManager.getAllAvailableTools(sub),
                                            serverName = { serverId -> mcpManager.getServerName(serverId) },
                                        ) { sid, name, args -> mcpManager.callTool(sid, name, args) }
                                    )
                                    // The shared per-conversation board tools (finding #1). The
                                    // production spawn path feeds THIS list straight to
                                    // TaskCoordinator.run, never the catalog's TurnMode.Subagent arm,
                                    // so without this a spawned subagent cannot task_list/task_update
                                    // the board the parent and UI share (spec assumption 5 / decision
                                    // #5). Bound to THIS conversation and owned by the child's live
                                    // EXECUTION HANDLE (findings #1/#5) so claims are precisely
                                    // attributable and orphan release frees a dead handle's claims;
                                    // the repository remains the single enforcement point (decision #4).
                                    addAll(
                                        subagentBoardTools(
                                            repository = taskBoardRepository,
                                            conversationId = conversationId,
                                            registry = executionHandles,
                                            handle = handle,
                                            sub = sub,
                                        )
                                    )
                                }
                            },
                            releaseOrphanedClaims = { handleId ->
                                taskBoardRepository.releaseClaimsOf(handleId)
                            },
                            // The child-approval gate (Gap A, decision #2): an EXPLICIT per-
                            // assistant allowlist — default empty = forward nothing — over the
                            // surface above; non-allowlisted approval tools auto-deny with the
                            // reason recorded in the task summary.
                            approvalGateFor = { sub ->
                                TaskApprovalRouter(
                                    policyFor = {
                                        TaskToolPolicy(
                                            approvalForwardAllowlist = sub.subagentApprovalAllowlist.toSet()
                                        )
                                    },
                                    surface = childApprovalSurface,
                                    store = taskRunStore,
                                )
                            },
                            processingStatus = session.processingStatus,
                            progressLabel = { subName ->
                                strings.getString(R.string.chat_subagent_running, subName)
                            },
                            // Associate the persisted task row with THIS conversation so the board
                            // panel / retention / cleanup find it (review finding #2). Same id the
                            // board port binds below.
                            parentConversationId = conversationId,
                            // Subagent UI automation (Option B): the lease mints a guard from the
                            // SUBAGENT's own grant and registers it on THIS session so the kill switch
                            // covers it, even when the parent has no automation of its own.
                            automationLease = SubagentAutomationLease { sub, block ->
                                openSubagentAutomationLease(sub, session, block)
                            },
                            // Opt-in `background` spawn (background-spawn v1): the app-lifetime scope a
                            // detached child runs on (so it outlives this turn), and the idle-drain poke
                            // that delivers its enqueued completion the moment the child terminates
                            // instead of waiting for the user's next turn-end.
                            backgroundScope = appScope,
                            onBackgroundComplete = { maybeDrainAgentEventsWhenIdle(conversationId) },
                        )
                    }
                },
                // Per-conversation board tools (SPEC.md M3/T7), added to the base pool for BOTH the
                // main turn and any spawned subagent (decision #5: subagents coordinate over the one
                // shared board). The port binds THIS conversation's id + the parent actor, so the
                // tool never sees a conversation id; the repository is the single enforcement point
                // shared with the board UI (decision #4). The parent turn acts as the user/owner.
                boardTools = {
                    buildBoardTools(
                        BoardPortAdapter(
                            repository = taskBoardRepository,
                            conversationId = conversationId,
                            actor = BoardActor(
                                handleId = "conversation:$conversationId",
                                displayName = senderName,
                            ),
                        )
                    )
                },
                // Per-conversation schedule tools (SPEC.md M4/T7), built on a port bound to THIS
                // conversation's id with owner=AGENT — the agent path uses the current conversation
                // id, never TaskCoordinator.run's Uuid.random() default. The port (SchedulePortAdapter)
                // is the only place that knows scope/owner; the tool never sees either, and the
                // repository remains the single legality path shared with the schedule UI.
                scheduleTools = {
                    buildScheduleTools(
                        SchedulePortAdapter(
                            repository = taskScheduleRepository,
                            conversationId = conversationId,
                            owner = ScheduleOwner.AGENT,
                        )
                    )
                },
            )

            // 流式 UI 合并状态：每个 chunk 携带累计全量 messages（节点按 INDEX 合并、节点内消息按 id 匹配，
            // 见 Conversation.updateCurrentMessages）。把"何时把合并状态写给 UI"的窗口节流 + 终止刷新决策收拢进
            // StreamingUiCoalescer（纯状态机，与 JVM 单测共用）。publish 副作用经 publishStreamingMessages 以单次
            // CAS（StateFlow.update）把合并 messages 重新合并进**当时的** live StateFlow（而非读一个陈旧快照再写
            // 回），故一次并发的 UI 写入（收藏切换/编辑/标题等非消息字段）不会被覆盖——updateCurrentMessages 经
            // node.copy 重建节点、保留 isFavorite 等非消息字段，last-writer-wins → idempotent merge-onto-current。
            // Keyed on the whole chunk (not just its messages) so the per-chunk `force` flag — set by the
            // runtime on semantic tool-boundary emits — reaches the coalescer; publish extracts .messages.
            val uiCoalescer = StreamingUiCoalescer<GenerationChunk.Messages>(
                publish = { chunk ->
                    publishStreamingMessages(getOrCreateSession(conversationId).state, chunk.messages)
                }
            )
            val effectiveMessages = sliceTurnMessages(conversation.currentMessages, messageRange)
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
                // Surface the armed `/goal` into the system prompt every turn so the model is steered by
                // it and can report it (a normal user turn, a goal continuation, and a loop fire all run
                // through here). Null when no goal is armed.
                activeGoal = session.activeGoal?.condition,
                memories = recalledMemories,
                inputTransformers = chatMessageTransformers.input,
                outputTransformers = chatMessageTransformers.output,
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
                            is GenerationChunk.Messages -> chunk
                        }
                    },
                    // A semantic tool-boundary frame (force=true) bypasses the throttle so the in-progress
                    // tool / approval gate / freshly-arrived output always reaches the UI.
                    force = { it.force },
                    sideEffect = { chunk ->
                        // 任何流式进展都重置前台服务的 WakeLock 超时，使长 agentic 循环（工具/MCP/搜索跨多段
                        // 子-120s SSE）在息屏下不会在 15 分钟处误超时停机。按时间节流，避免逐 token IPC。
                        foregroundGeneration.onStreamingProgress()

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            chunk.messages.lastOrNull()?.parts?.let {
                                notifications.sendLiveUpdate(conversationId, senderName, it)
                            }
                        }
                    },
                )
            }.onCompletion {
                // UI automation (#187): the per-conversation lease + STOP overlay are released in the
                // outer finally (covers the eager-arg throw before this onCompletion is even
                // attached), not here. The lease itself is also self-expiring (TTL) as a backstop.

                // Live Update 通知使用 per-conversation 的 (tag,id) 键，当前会话结束后即时移除，避免并发会话互相覆盖。
                notifications.cancelLiveUpdate(conversationId)

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
                    notifications.sendGenerationDone(conversationId, senderName, preview)
                }
            }.collect { /* 终端消费：所有 chunk 副作用已在 coalesce 内完成 */ }

            }
        }.onFailure {
            // Live Update 通知为 per-conversation 生命周期，已在 onCompletion 统一取消。
            if (it !is CancellationException) Log.e(TAG, "handleMessageComplete: generation failed", it)
            addError(it, conversationId, title = strings.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            saveConversation(conversationId, getConversationFlow(conversationId).value)

            // Review mustFix #2: the sendMessage path defers these to AFTER the Stop-hook
            // continuation (runTurnEndJobs = false + sequenceTurnEnd) — launching here would
            // build title/suggestions from the pre-continuation transcript and race the final
            // metadata. Other entry points (regenerate, approval-resume) end the turn here.
            if (runTurnEndJobs) {
                // Agent-event drain (issue #290): the regenerate/approval-resume turn-end is here.
                // Gated on a SUCCESSFUL turn (we are in onSuccess) and on runTurnEndJobs so the
                // nested continuation (runTurnEndJobs = false) cannot recurse into another drain
                // (one event per continuation, productDecision #5). Drain BEFORE the turn-end jobs
                // so title/suggestions reflect the post-continuation transcript.
                drainAgentEventsAtTurnEnd(conversationId)
                launchTurnEndJobs(conversationId)
            }
        }.isSuccess
    }

    // Post-turn side jobs (title + suggestions), built from the conversation as it is NOW —
    // callers must only invoke this once the turn is final.
    private fun launchTurnEndJobs(conversationId: Uuid) {
        val finalConversation = getConversationFlow(conversationId).value
        launchWithConversationReference(conversationId) {
            generateTitle(conversationId, finalConversation)
        }
        launchWithConversationReference(conversationId) {
            generateSuggestion(conversationId, finalConversation)
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
        // STOP_IS_DETACH_NOT_KILL (issue #291): a user stop during a workspace_shell foreground wait
        // BACKGROUNDS the run (the coordinator persisted DETACHED under NonCancellable and launched a
        // detached awaiter on AppScope), it does NOT kill it. So a still-pending workspace_shell tool
        // part at finalize time must NOT be stamped {status:cancelled} — its completion arrives later
        // as a synthetic #290 event. Stamp the SAME honest {status:running} marker the shipped Detached
        // path emits (approvalState UNCHANGED — Denied is resumable), so this finalizer agrees
        // byte-for-byte with sanitizeForUpload's repairOrphanTools; whichever runs first makes the part
        // executed and the other no-ops. Every other interrupted tool is still finalized as cancelled.
        if (shouldBackgroundShellOnStop(tool)) {
            return tool.copy(output = listOf(UIMessagePart.Text(SHELL_BACKGROUNDED_MARKER)))
        }
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
        val session = getOrCreateSession(conversationId)
        val updatedConversation = finishInterruptedPendingToolsForNewSend(
            session = session,
            cancelTool = ::cancelToolByUser,
        ) ?: return
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
                title = strings.getString(R.string.error_title_generate_title),
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

            sessionRegistry.get(conversationId)?.let { session ->
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
                ?: sessionRegistry.get(conversationId)?.state?.value
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
            throw IllegalStateException(strings.getString(R.string.chat_page_compress_not_enough_messages))
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

    private fun updateConversationFromInitialization(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        casUpdateState(
            state = session.state,
            update = { current -> preserveConcurrentSyntheticAgentEventNodes(conversation, current) },
            onTransition = { old, new -> checkFilesDelete(newConversation = new, oldConversation = old) },
        )
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
        // A tombstoned id never saves here: during delete the row still EXISTS until the repo delete
        // commits, so an in-flight finalizer (generation onCompletion, title/suggestion job) must be
        // blocked unconditionally — keying off row-existence would race the delete and resurrect the
        // chat. The tombstone is cleared ONLY by the explicit restore path ([restoreConversation]).
        if (isConversationTombstoned(conversation.id)) return

        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        updateConversationWithFileCleanup(conversationId, conversation)

        if (!exists) {
            conversationRepo.insertConversation(conversation)
        } else {
            conversationRepo.updateConversation(conversation)
        }
        attachDeferredShellToolAnchors(conversation)
    }

    private suspend fun attachDeferredShellToolAnchors(conversation: Conversation) {
        findDeferredShellToolAnchors(conversation).forEach { candidate ->
            val attached = shellRunStore.attachToolAnchor(candidate.taskId, candidate.anchor)
            if (!attached) {
                Log.w(
                    TAG,
                    "attachDeferredShellToolAnchors: failed to attach anchor for task ${candidate.taskId}"
                )
            }
        }
        // Same persistence for background subagent running markers, so a completion can resolve back
        // into the original agent/task tool output by taskId. A false return is benign (already
        // anchored / row gone) — the drain's conversation-scan fallback covers a missing anchor.
        findBackgroundSubagentToolAnchors(conversation).forEach { (taskId, anchor) ->
            taskRunStore.attachToolAnchor(taskId, anchor)
        }
    }

    private fun isConversationTombstoned(conversationId: Uuid): Boolean =
        tombstones.isTombstoned(conversationId)

    // Delete↔restore serialization + the tombstone set live in [tombstones] (#360 P3): History fires
    // delete fire-and-forget and offers Undo immediately, so a restore in that window must observe a
    // fully-committed delete (and never the reverse). The ordering invariants (mark-before-body on
    // delete; insert-before-clear on restore) are encoded + tested in ConversationTombstones.
    suspend fun deleteConversation(conversation: Conversation) = tombstones.delete(conversation.id) {
        stopGeneration(conversation.id)
        // Cancel any DETACHED background subagents this conversation owns — stopGeneration only stops the
        // foreground turn; a background run is on appScope and would otherwise keep running, count against
        // a finite cap, and try to deliver into the now-deleted conversation.
        taskCoordinator.cancelBackgroundForConversation(conversation.id)
        sessionRegistry.remove(conversation.id, force = true)
        conversationRepo.deleteConversation(conversation)
    }

    /** Restore a previously-deleted conversation (History "Undo"); see [ConversationTombstones.restore]. */
    suspend fun restoreConversation(conversation: Conversation) = tombstones.restore(conversation.id) {
        conversationRepo.insertConversation(conversation)
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
                val loadingText = strings.getString(R.string.translating)
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
                // Clear the translation field on error AND on cancellation (the loading placeholder
                // must never stick), but cancellation itself must propagate, not report.
                clearTranslationField(conversationId, message.id)
                if (shouldRethrowVmError(e)) throw e
                addError(e, conversationId, title = strings.getString(R.string.error_title_translate_message))
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
        val assistant = settings.getAssistantByIdOrCurrent(currentConversation.assistantId)
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
    suspend fun stopGeneration(conversationId: Uuid, expectedJob: Job) {
        val session = sessionRegistry.get(conversationId) ?: return
        if (!shouldStopA2aJob(session.getJob(), expectedJob)) return

        val job = session.getJob() ?: return
        // The in-app Stop is the second kill-switch (#187 §7): revoke THIS conversation's automation
        // grant before cancelling so a tool step that is mid-authorize fails closed. Cancelling the
        // job tears down only this conversation's in-flight capture (the capture is a child of this
        // generation coroutine) — a concurrent automation session is untouched. The cancellation
        // propagates through withAutomationLease as a non-normal exit, so its finally clears the
        // per-run grant too (finding 3): a stopped turn will not resume, so the grant must not survive.
        sessionRegistry.get(conversationId)?.revokeAutomation()
        // A user-stop always wins over an autonomous /goal (#364): clear it so the loop does not
        // resume on the next turn end. The unconditional clear beats any in-flight loop CAS (the
        // loop's charge re-checks identity and bails). The in-flight goal-loop coroutine is a child of
        // this job and is torn down by the cancel below.
        sessionRegistry.get(conversationId)?.clearGoal()
        // (Skill authoring needs no clear here: a stopped turn never reaches the handleMessageComplete
        // consume, and the job-completion catch-all clears any lease the cancelled turn left armed.)
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }

    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessionRegistry.get(conversationId)?.getJob() ?: return
        stopGeneration(conversationId, job)
    }

}

internal fun shouldStopA2aJob(currentJob: Job?, expectedJob: Job): Boolean = currentJob === expectedJob

internal fun isA2aConversationBindingAllowed(
    existingAssistantId: Uuid?,
    requestedAssistantId: Uuid,
): Boolean = existingAssistantId == null || existingAssistantId == requestedAssistantId

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
    modelName: String,
    serverId: Uuid,
    tool: McpTool,
    callTool: suspend (Uuid, String, JsonObject) -> List<UIMessagePart>,
): Tool = Tool(
    name = modelName,
    description = tool.description ?: "",
    parameters = { tool.inputSchema },
    needsApproval = tool.needsApproval,
    // The execute closure forwards the RAW (serverId, tool.name) to the MCP server, so the model-facing
    // [modelName] is free to be sanitized/disambiguated/deduped without any reverse map.
    execute = { callTool(serverId, tool.name, it.jsonObject) },
)

/** Provider function-name length limit (OpenAI/Anthropic/Google all cap at 64 chars). */
internal const val MCP_TOOL_NAME_MAX_LEN = 64
private const val MCP_SERVER_SLUG_MAX = 24
// Headroom reserved on the base name for a `_<n>` de-duplication suffix (covers up to `_999`).
private const val MCP_DEDUP_RESERVE = 4

/** Sanitizes [raw] to a provider-safe `[A-Za-z0-9_]` segment, trimming framing underscores. */
private fun sanitizeMcpSegment(raw: String): String = buildString {
    raw.forEach { c ->
        append(if (c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '_')
    }
}.trim('_')

/**
 * The READABLE per-server segment of an MCP tool name (issue #356 #2): the human server name, sanitized
 * to `[A-Za-z0-9_]` and bounded, so the model sees `mcp__context7__lookup`, not an opaque id. Falls back
 * to a short stable id (`srv<8 hex of the UUID>`) only when the server is unnamed.
 */
internal fun mcpServerSlug(serverName: String, serverId: Uuid): String {
    val slug = sanitizeMcpSegment(serverName)
        .ifEmpty { "srv" + serverId.toString().replace("-", "").take(8) }
    return slug.take(MCP_SERVER_SLUG_MAX)
}

/**
 * The base model-facing name for an MCP tool: `mcp__<serverSlug>__<toolSlug>`, provider-safe
 * (`[A-Za-z0-9_]`) and bounded so it leaves headroom for a de-dup suffix. NOT yet deduplicated —
 * [buildMcpTools] resolves any residual collision (two identically-named servers, or two raw names that
 * sanitize alike) with a numeric suffix.
 */
internal fun mcpModelToolName(serverSlug: String, rawToolName: String): String {
    val prefix = "mcp__${serverSlug}__"
    val maxTool = (MCP_TOOL_NAME_MAX_LEN - prefix.length - MCP_DEDUP_RESERVE).coerceAtLeast(1)
    val slug = sanitizeMcpSegment(rawToolName).ifEmpty { "tool" }
    return prefix + slug.take(maxTool)
}

/**
 * Adapts the MCP (serverId, tool) [entries] of ONE tool pool into AI-SDK [Tool]s with readable,
 * provider-safe, COLLISION-FREE model-facing names (issue #356 #2). The legacy `"mcp__" + tool.name`
 * collided when two servers exposed the same tool name — the runtime resolves a call by FIRST
 * exact-name match, so the second server's tool was unreachable. Here each name is
 * `mcp__<serverSlug>__<toolSlug>` (server slug from [serverName]); any residual duplicate is given a
 * `_<n>` suffix, deterministically by [entries] order, so a persisted call resolves to the same name
 * across restarts of the same config.
 *
 * [callTool] forwards the RAW (serverId, rawName). Shared by both the main-agent pool ([AppToolCatalog])
 * and the subagent pool so the naming invariant cannot drift between them.
 */
internal fun buildMcpTools(
    entries: List<Pair<Uuid, McpTool>>,
    serverName: (Uuid) -> String,
    callTool: suspend (Uuid, String, JsonObject) -> List<UIMessagePart>,
): List<Tool> {
    val taken = HashSet<String>()
    return entries.map { (serverId, tool) ->
        val base = mcpModelToolName(mcpServerSlug(serverName(serverId), serverId), tool.name)
        var name = base
        var n = 2
        while (!taken.add(name)) {
            // Length-aware suffixing: keep the de-duped name within the provider cap even when the
            // suffix outgrows MCP_DEDUP_RESERVE (e.g. a 5-char `_1000`), truncating the base if needed.
            // The `taken` check still guarantees uniqueness, so a truncated candidate that collides
            // simply advances to the next suffix.
            val suffix = "_$n"
            name = base.take((MCP_TOOL_NAME_MAX_LEN - suffix.length).coerceAtLeast(0)) + suffix
            n++
        }
        mapMcpTool(name, serverId, tool, callTool)
    }
}

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
 * Initialization writes a Room snapshot that may have been read before startup replay appended and
 * consumed a synthetic agent-event node, then continued the model. When the live state still has the
 * snapshot as its persisted prefix plus a newer tail anchored by that synthetic node, keep the whole
 * tail so a CONSUMED event cannot lose its synthetic node, assistant reply, or tool continuation. If
 * the prefix differs, or the tail is not an agent-event replay tail, the snapshot is a real
 * edit/branch/truncate and wins.
 */
internal fun preserveConcurrentSyntheticAgentEventNodes(
    snapshot: Conversation,
    live: Conversation,
): Conversation {
    if (snapshot.id != live.id) return snapshot
    if (!live.hasPersistedPrefix(snapshot)) return snapshot

    val snapshotNodeIds = snapshot.messageNodes.mapTo(mutableSetOf()) { it.id }
    val snapshotMessageIds = snapshot.messageNodes
        .flatMapTo(mutableSetOf()) { node -> node.messages.map { it.id } }
    val liveTail = live.messageNodes.drop(snapshot.messageNodes.size)
    if (liveTail.isEmpty()) return snapshot
    val appendOnlyTail = liveTail.all { node ->
        node.id !in snapshotNodeIds &&
            node.messages.none { it.id in snapshotMessageIds } &&
            node.selectedMessageCreatedAfter(snapshot.updateAt)
    }

    return if (!appendOnlyTail || !liveTail.first().isSelectedSyntheticAgentEvent()) {
        snapshot
    } else {
        snapshot.copy(messageNodes = snapshot.messageNodes + liveTail)
    }
}

private fun Conversation.hasPersistedPrefix(snapshot: Conversation): Boolean =
    messageNodes.size >= snapshot.messageNodes.size &&
        snapshot.messageNodes.indices.all { index ->
            messageNodes[index].hasSamePersistedContent(snapshot.messageNodes[index])
        }

private fun MessageNode.hasSamePersistedContent(other: MessageNode): Boolean =
    id == other.id &&
        selectIndex == other.selectIndex &&
        messages == other.messages

private fun MessageNode.selectedMessageCreatedAfter(instant: Instant): Boolean {
    val message = messages.getOrNull(selectIndex) ?: return false
    return message.createdAt
        .toJavaLocalDateTime()
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .isAfter(instant)
}

private fun MessageNode.isSelectedSyntheticAgentEvent(): Boolean =
    messages.getOrNull(selectIndex)?.isSyntheticAgentEvent() == true

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

/**
 * The generation entry-point job lifecycle policy shared by [ChatService.sendMessage],
 * [ChatService.regenerateAtMessage] and [ChatService.handleToolApproval] (via
 * `launchGenerationEntry`). Extracted as a top-level function over plain coroutine primitives so
 * the two invariants that previously drifted across the three hand-rolled copies are JVM
 * unit-tested against production wiring rather than a mirror:
 *
 *  - SUPERSEDE BARRIER: [block] must not run until [previousJob] (the cancelled generation this
 *    one replaces, including its NonCancellable persistence finalizer) has finished — otherwise
 *    the old job's writes race the new job's and can resurrect state the user just removed.
 *    The join is deliberately unguarded: [Job.join] never rethrows the joined job's failure, it
 *    only throws this job's own [CancellationException] — which must reach the catch below and
 *    be rethrown via [shouldRethrowVmError] so [block] never runs after cancellation.
 *  - CANCELLATION RETHROW: cancellation (stopGeneration / a newer entry superseding this job)
 *    must propagate per [shouldRethrowVmError], never be reported through [onError] — a cancelled
 *    job that "completes normally" breaks the structured-concurrency contract CoroutineUtils pins.
 *
 * Every other [Exception] is reported through [onError] instead of crashing [scope].
 */
internal fun launchGenerationEntryJob(
    scope: CoroutineScope,
    previousJob: Job?,
    onError: (Exception) -> Unit,
    block: suspend () -> Unit,
): Job = scope.launch {
    try {
        previousJob?.join()
        block()
    } catch (e: Exception) {
        if (shouldRethrowVmError(e)) throw e
        onError(e)
    }
}
