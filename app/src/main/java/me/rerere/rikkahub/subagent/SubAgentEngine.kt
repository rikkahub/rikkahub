package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"

/**
 * Phase 11 — sub-agent dispatch engine.
 *
 * The engine reuses the existing cron-headless dispatch pattern (mark conv headless,
 * sendMessage, await generation flow's terminal state). It deliberately does NOT
 * re-implement [me.rerere.rikkahub.data.ai.GenerationHandler] — that path is already
 * battle-tested and any duplicate would diverge.
 *
 * Recursion guard: SubAgentEngine refuses to dispatch if the calling conversation is
 * itself headless (i.e. we're already inside a sub-agent / cron / external-automation
 * run). The four `subagent_*` tools are also not registered for headless conversations
 * via the standard tool gating in [me.rerere.rikkahub.data.ai.tools.LocalTools] — but the
 * engine-level check is the load-bearing guard since a misconfigured assistant could
 * still try to call us. v1: no recursion.
 *
 * Concurrency caps:
 *  - Per-assistant cap from [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents]
 *  - Global cap from [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP]
 *  - Both enforced at dispatch entry — over-cap requests fail fast (background) or block
 *    up to 30s waiting for a slot before failing (foreground, per spec).
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {

    /**
     * [ChatService] is resolved lazily via Koin to break the construction cycle:
     *   - [ChatService] constructor takes [LocalTools]
     *   - [LocalTools] constructor takes [SubAgentEngine] (so subagent_dispatch can fire)
     *   - [SubAgentEngine] needs [ChatService] only at dispatch time (sendMessage), so
     *     eager constructor injection here would close the cycle.
     * Same lazy-Koin pattern as [me.rerere.rikkahub.workflow.execution.WorkflowEngine.localTools].
     * Verified post-DI-fix 2026-05-08 — installed APK reaches MainActivity without crash.
     */
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    /**
     * Dispatch a sub-agent. For foreground runs, blocks until terminal status; for
     * background, returns immediately with a PENDING-then-RUNNING run that the caller
     * can poll via subagent_get.
     */
    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // Recursion guard: if the caller is in a headless context already (cron job /
        // workflow / another sub-agent), reject. v1 does not allow nested sub-agents.
        if (parentChatId != null) {
            val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull()
            if (parentUuid != null && HeadlessConversations.isHeadless(parentUuid)) {
                return@withContext DispatchResult.Reject(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside another headless run"
                )
            }
        }
        val validation = SubAgentRequestValidator.validate(request)
        if (validation is SubAgentRequestValidator.Result.Reject) {
            return@withContext DispatchResult.Reject(validation.error, validation.detail)
        }
        val cleaned = (validation as SubAgentRequestValidator.Result.Ok).request

        // Concurrency cap. Global first (cheaper), then per-assistant.
        if (registry.globalActiveCount() >= SubAgentDefaults.GLOBAL_CONCURRENCY_CAP) {
            return@withContext DispatchResult.Reject(
                "global_cap_reached",
                "max ${SubAgentDefaults.GLOBAL_CONCURRENCY_CAP} concurrent sub-agents across all assistants"
            )
        }
        val perAssistantCap = currentAssistantCap(parentAssistantId)
        if (registry.activeCountForAssistant(parentAssistantId) >= perAssistantCap) {
            return@withContext DispatchResult.Reject(
                "assistant_cap_reached",
                "this assistant's max_concurrent_sub_agents cap of $perAssistantCap is reached"
            )
        }

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val initialRun = SubAgentRun(
            id = runId,
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            label = cleaned.label?.takeIf { it.isNotBlank() } ?: cleaned.task.take(60),
            task = cleaned.task,
            modelId = cleaned.modelId,
            tools = cleaned.tools,
            runInBackground = cleaned.runInBackground,
            timeoutSeconds = cleaned.timeoutSeconds,
            maxTrips = cleaned.maxTrips,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
        )
        registry.addPending(initialRun)

        val executionJob = appScope.launch(Dispatchers.IO) {
            executeRun(runId, parentAssistantId, parentChatId, cleaned)
        }
        registry.setJob(runId, executionJob)

        if (cleaned.runInBackground) {
            // Return immediately; final status delivered via registry observation.
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        } else {
            // Foreground — block until terminal.
            try {
                executionJob.join()
            } catch (t: Throwable) {
                Log.w(TAG, "foreground sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        }
    }

    private suspend fun currentAssistantCap(parentAssistantId: String): Int {
        val asstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull() ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        val settings = settingsStore.settingsFlow.first()
        val asst = settings.assistants.firstOrNull { it.id == asstUuid }
            ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        return asst.maxConcurrentSubAgents.coerceIn(
            SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
            SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
        )
    }

    private suspend fun executeRun(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }

        val parentAsstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run {
                markTerminal(runId, SubAgentStatus.FAILED, "bad parent assistant id")
                return
            }
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = parentAsstUuid,
            newConversation = true,
        ).copy(title = "[Sub-agent] ${request.label?.take(40) ?: request.task.take(40)}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            // Prepend a wrap-up instruction. Some models naturally write a summary paragraph
            // after their tool-call sequence; others stop after the last tool result and emit
            // no closing text. Without explicit text the parent has nothing to harvest and
            // the sub-agent's findings are lost.
            val taskWithWrapup = buildString {
                append(request.task)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(taskWithWrapup)))
            // The naive form `withTimeoutOrNull { …first { it == null } }` followed by a
            // `finished == null` check is BROKEN: `.first { it == null }` returns the matched
            // value — which IS null on successful completion (the Job? went to null when the
            // LLM finished). So `finished == null` was true on BOTH timeout AND success, and
            // every sub-agent looked TIMED_OUT despite actually finishing. Use a Unit sentinel
            // so the two outcomes are distinguishable.
            val completed: Unit? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
                Unit
            }
            if (completed == null) {
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded ${request.timeoutSeconds}-second cap")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            // Harvest the assistant's final text from the conversation. Best-effort —
            // we read the latest persisted state of the conversation and concatenate any
            // text parts from the last assistant message. This mirrors how the
            // CronJobWorker treats LLM-mode jobs.
            val finalText = harvestFinalText(conv.id)
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent run failed", t)
            // CancellationException → CANCELLED, anything else → FAILED.
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } finally {
            HeadlessConversations.unmark(conv.id)
            registry.clearJob(runId)
        }
    }

    private fun markTerminal(runId: String, status: SubAgentStatus, error: String?) {
        registry.update(runId) {
            it.copy(
                status = status,
                error = error,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Wake the parent conversation when a backgrounded sub-agent finishes — the parent's
     * LLM gets a synthetic user message describing the completion and naturally synthesises
     * a reply. Without this, the parent has no way to know the sub-agent finished except by
     * the user manually asking "what happened?".
     *
     * Skip rules:
     *  - Foreground runs: dispatch is synchronous (executionJob.join() in dispatch()), so the
     *    tool result already carries the final state. No wake needed.
     *  - Parents in headless mode: would loop / fork weirdly with cron + sub-agent + workflow
     *    runs. The parent must be a regular interactive (in-app or Telegram-bot) conversation.
     *  - parentChatId / runs missing: defensive.
     *
     * Cancellation hygiene: ChatService.sendMessage cancels any in-flight generation in the
     * target conversation. To avoid stomping on a turn the user is engaged with, we wait up
     * to 5 minutes for the parent to be idle before posting. After 5 minutes we post anyway
     * — better to interrupt than to silently lose the completion.
     */
    private suspend fun notifyParentIfBackground(parentChatId: String?, run: SubAgentRun?) {
        if (parentChatId == null || run == null || !run.runInBackground) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        if (HeadlessConversations.isHeadless(parentUuid)) return

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            run.error?.takeIf { it.isNotBlank() }?.let {
                appendLine("Error: $it")
            }
            run.result?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        }.trimEnd()

        runCatching {
            withTimeoutOrNull(5 * 60_000L) {
                chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
                Unit
            }
            chatService.sendMessage(parentUuid, listOf(UIMessagePart.Text(message)))
        }.onFailure {
            Log.w(TAG, "failed to notify parent $parentChatId of subagent completion", it)
        }
    }

    private suspend fun harvestFinalText(conversationId: Uuid): String {
        // The Conversation persisted by the generation pipeline contains the full message
        // history (messageNodes). Each MessageNode holds parallel branches in
        // `messages: List<UIMessage>` keyed by `selectIndex`. Walk the currently-selected
        // branch and pull text from the last assistant message — that's the sub-agent's
        // final summary.
        //
        // Robustness: if the last assistant message has NO Text part (some models stop
        // after a tool call and emit no closing text), walk back through previous assistant
        // messages and concatenate their Text parts so we don't return empty. Better to
        // surface partial intermediate text than to return "" and lose the sub-agent's
        // work entirely.
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = conv.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)
            }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            // Try the last assistant message's text first.
            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            // Fallback: collect text from all assistant messages (preserve order).
            assistantMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
                .trim()
        }.getOrDefault("")
    }
}
