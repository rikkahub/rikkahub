package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.TaskBudgetClock
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.extractFinalAssistantText
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.runtime.MonotonicTaskBudgetClock
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Local sentinel thrown out of the child-flow `collect` to STOP collection on a budget breach.
 * Deliberately NOT a [CancellationException]: it must unwind past the collect (cancelling the
 * upstream child flow) but be caught at the collection site, not by [TaskCoordinator.execute]'s
 * cancellation arm — a budget stop is a normal terminal, not a parent cancel. Carries the
 * BudgetExhausted [TaskState] the breach event produced so the terminal result reports it.
 */
private class BudgetStop(val terminal: TaskState?) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The terminal outcome of a task run (review finding #1). [run]/[resume] return this instead of a
 * bare final-text string so the spawn tool can mirror the run's TERMINAL lifecycle into its
 * `UIMessagePart.Tool` output envelope — the status, the budget counters, and the
 * interrupted/budget-exhausted identity the live renderer (`TaskToolUI`) needs. The renderer's
 * legacy fallback (bare text => Succeeded) is then only hit by genuinely pre-envelope transcripts.
 *
 * Note: this carries the TERMINAL state only. Live status WHILE the child runs still requires the
 * streaming-envelope seam through `ChatService`'s async generation (the tracked follow-up gap); a
 * synchronous `execute` cannot emit intermediate envelopes.
 *
 * @param text the parent-visible final text (parity with the old `String` return).
 * @param state the terminal [TaskState] the run reached.
 * @param usage the run's final cumulative budget counters.
 * @param maxSteps the step ceiling this run was bounded by (for the renderer's `steps/maxSteps`).
 */
data class TaskRunResult(
    val text: String,
    val state: TaskState,
    val usage: TaskBudgetUsage,
    val maxSteps: Int,
)

/**
 * The lifecycle-aware orchestrator that runs one self-contained sub-task against another
 * [Assistant] (SPEC.md M4). It is the product replacement for `SubagentRunner`: same final-text
 * return so the spawn tool's output still lands in `UIMessagePart.Tool`, but with three additions
 * the runner lacked —
 *
 *  1. **A persisted task run.** Every run creates a [TaskState.Created] row via [store] and drives
 *     it through the pure `TaskStateReducer` (Enqueued -> SlotClaimed -> ChildProgressed ->
 *     FinalResult, or a failure/budget edge). The store is the single state authority; the
 *     coordinator only *emits* events, never writes a tag itself — so the persisted state can
 *     never disagree with TASK_STATE_LEGAL.
 *  2. **Budget + concurrency enforcement.** A [Semaphore] of [TaskBudget.globalConcurrency] caps
 *     in-flight children process-wide; a per-parent [Mutex] caps them at
 *     [TaskBudget.perParentConcurrency] per spawning tool call. Acquiring a slot is the natural
 *     coroutine queue: a run that cannot claim immediately simply suspends in [TaskState.Queued]
 *     until one frees — no spin, no sleep. Step/token/wall-time caps are checked against the
 *     child's reported usage; the first breach fires [TaskEvent.BudgetExceeded] and stops the run
 *     in [TaskState.BudgetExhausted].
 *  3. **It never bypasses `generateText`.** The engine seam is [GenerationHandler.generateText]
 *     (injected as [generate]); driving the child through it preserves the PreToolUse hook
 *     dispatch `GenerationHandler` already wires into `ChatTurnRuntime` (SPEC re-grounding row 2).
 *
 * SoC: like the runner it replaces, this is intentionally NOT the heavy `ChatService`. It calls the
 * agentic engine directly and collects the resulting [kotlinx.coroutines.flow.Flow] inline in the
 * caller's coroutine, so cancellation is inherited via structured concurrency (parent generation
 * job cancelled => this collection cancelled). Conversation persistence lives only in
 * `ChatService.saveConversation`, never in `generateText`, so a child never touches the
 * conversation Room tables.
 */
class TaskCoordinator(
    private val generate: SubagentGenerate,
    private val store: TaskRunStore = NoopTaskRunStore,
    private val defaultBudget: TaskBudget = TaskBudget(),
    private val monotonicNow: () -> Duration = { Duration.ZERO },
) {
    /**
     * DI/composition-root constructor: bind the engine to [GenerationHandler.generateText] and the
     * wall-time budget to a real [TaskBudgetClock].
     *
     * The [clock] is REQUIRED, not defaulted: a default here is exactly how wall-time enforcement
     * silently died in the shipped build (review finding #1) — the previous `monotonicNow` default
     * of `{ Duration.ZERO }` made `elapsed` always 0, so the wall-time cap never tripped. The
     * composition root must supply a monotonic clock ([MonotonicTaskBudgetClock]); there is no
     * zero-clock fallback that can re-disable the cap.
     */
    constructor(
        generationHandler: GenerationHandler,
        store: TaskRunStore,
        clock: TaskBudgetClock,
        defaultBudget: TaskBudget = TaskBudget(),
    ) : this(
        generate = { settings, model, messages, assistant, tools, maxSteps, processingStatus ->
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = maxSteps,
                processingStatus = processingStatus,
            )
        },
        store = store,
        defaultBudget = defaultBudget,
        monotonicNow = clock::monotonicNow,
    )

    /** Process-wide concurrency gate. Recomputed per cap so a test/override budget is honored. */
    private val globalSemaphores = ConcurrentHashMap<Int, Semaphore>()

    /**
     * Per-parent-tool-call concurrency gate, keyed by the spawn tool call id. Each entry is
     * reference-counted: every run grouped under the same parentToolCallId shares the one [Mutex],
     * and the entry is EVICTED once its last user departs ([releaseParentMutex] drops it at refs=0).
     * Without that eviction the map grew one permanent entry per spawn for the life of the
     * process-singleton coordinator — an unbounded leak on a hot path (review finding #3).
     *
     * [parentRegistryLock] guards only the brief refcount bookkeeping (getOrPut + refs++/--), NOT
     * the held lock or [execute]; it is a coroutine [Mutex] because acquisition runs suspendably.
     */
    private class RefCountedMutex {
        val mutex = Mutex()
        var refs = 0
    }

    private val parentMutexes = HashMap<String, RefCountedMutex>()
    private val parentRegistryLock = Mutex()

    private fun globalSemaphore(permits: Int): Semaphore =
        globalSemaphores.getOrPut(permits) { Semaphore(permits.coerceAtLeast(1)) }

    /**
     * Acquire (creating if absent) the shared [Mutex] for [parentToolCallId] and register one user.
     * The matching [releaseParentMutex] MUST run for every successful acquire (use try/finally), or
     * the entry leaks again.
     */
    private suspend fun acquireParentMutex(parentToolCallId: String): Mutex =
        parentRegistryLock.withLock {
            parentMutexes.getOrPut(parentToolCallId) { RefCountedMutex() }
                .also { it.refs++ }
                .mutex
        }

    /** Deregister one user of [parentToolCallId]; evict the entry when the last user leaves. */
    private suspend fun releaseParentMutex(parentToolCallId: String) =
        parentRegistryLock.withLock {
            val entry = parentMutexes[parentToolCallId] ?: return@withLock
            if (--entry.refs <= 0) {
                parentMutexes.remove(parentToolCallId)
            }
        }

    /** Live per-parent mutex entry count. Test-only: pins that eviction keeps the map bounded. */
    internal suspend fun parentMutexCount(): Int = parentRegistryLock.withLock { parentMutexes.size }

    /**
     * Run a sub-task to completion and return its terminal outcome ([TaskRunResult] — its `.text`
     * is the parity-preserving final text of `SubagentRunner.run`).
     *
     * @param parentToolCallId the spawn tool call that owns this child; the per-parent concurrency
     *   cap is keyed on it (a `null`/blank id means "no parent grouping" — only the global cap
     *   applies). Defaults blank so callers that don't track it still get global gating.
     * @param tools the child's tool pool; [filterToolsForSubagent] strips the spawn tool from it
     *   unconditionally (the depth-1 recursion guard, TASK_DEPTH_ONE) before it reaches the engine.
     */
    suspend fun run(
        sub: Assistant,
        prompt: String,
        parentModelId: Uuid?,
        settings: Settings,
        tools: List<Tool> = emptyList(),
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        parentConversationId: Uuid = Uuid.random(),
        parentToolCallId: String = "",
        taskId: Uuid = Uuid.random(),
        budget: TaskBudget = defaultBudget,
    ): TaskRunResult {
        val modelId = resolveSubagentModel(
            sub = sub.toAssistantConfig(),
            parentModelId = parentModelId,
            turn = TurnConfig(
                defaultModelId = settings.chatModelId,
                providers = emptyList(),
                assistants = emptyList(),
            ),
        )
        val model = settings.findModelById(modelId)
            ?: error("Subagent model not found for id $modelId")

        // Force memory OFF on the ephemeral sub so a throwaway child can never read/write the
        // PARENT's memory (the C1 data-integrity hazard); identical to the runner it replaces.
        val ephemeralSub = sub.copy(
            chatModelId = model.id,
            enableMemory = false,
            enableRecentChatsReference = false,
        )

        val maxSteps = sub.maxSteps ?: budget.maxSteps
        val messages = listOf(UIMessage.user(prompt))
        // The depth-1 recursion guard (TASK_DEPTH_ONE): the spawn tool is stripped from EVERY child
        // pool unconditionally, regardless of how the caller assembled it.
        val childTools = filterToolsForSubagent(tools, SPAWN_TOOL_NAME)

        // Acquire the global slot first, then (optionally) the per-parent slot. Acquisition is the
        // queue: a child with no free slot suspends here in TaskState.Queued. Ordering global-then-
        // parent consistently for every run prevents a lock-acquisition cycle. The parent-mutex
        // registration is released in a finally so its map entry is evicted once the last sibling
        // departs (review finding #3) — even on cancellation or a thrown execute().
        // Lifecycle closure (review mustFix): the run is already PERSISTED as Queued here, and
        // slot acquisition below is a suspension point — cancellation or a throw before execute()
        // begins must still drive the row to a terminal, or it stays active forever (invariant:
        // every exit from an active state reaches a terminal/resumable state on EVERY exit path).
        // Terminal writes on the cancellation path run under NonCancellable: the store suspends
        // (Room), and a suspending write inside an already-cancelled coroutine dies at its first
        // suspension point. Replays against execute()'s own terminal are absorbed by the reducer.
        // The flag scopes the closure to the PRE-execute window only: once execute() is entered,
        // its own handlers persist the terminal, and writing it again here would append a second
        // CancelRequested to the event log (the cancel-cascade property demands exactly one).
        // The closure starts BEFORE store.create (review mustFix round 4 #1): create and the
        // Enqueued write are themselves suspending, and a cancellation striking them must still
        // terminal-close whatever row exists — a terminal event against a row whose create never
        // persisted is a null no-op in the store, so the early coverage is safe.
        var enteredExecute = false
        var grouped = false
        return try {
            store.create(
                TaskSpec(
                    taskId = taskId,
                    parentConversationId = parentConversationId,
                    parentToolCallId = parentToolCallId,
                    agentTypeId = sub.id.toString(),
                    prompt = prompt,
                    parentModelId = parentModelId,
                    budget = budget,
                )
            )
            // Created -> Queued: the spawn tool call was accepted; the run now waits for a slot.
            store.applyEvent(taskId, TaskEvent.Enqueued)

            // grouped flips ONLY after acquireParentMutex returns (review mustFix round 5):
            // the acquire suspends on the registry lock, and a cancellation inside it must not
            // make the finally release a ref this run never registered — that would decrement a
            // sibling's entry and break per-parent serialization. No suspension point sits
            // between the acquire returning and the flag assignment.
            val parentMutex = if (parentToolCallId.isNotBlank()) {
                val acquired = acquireParentMutex(parentToolCallId)
                grouped = true
                acquired
            } else null

            globalSemaphore(budget.globalConcurrency).withPermit {
                if (parentMutex != null) {
                    parentMutex.withLock {
                        enteredExecute = true
                        execute(taskId, settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus, budget)
                    }
                } else {
                    enteredExecute = true
                    execute(taskId, settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus, budget)
                }
            }
        } catch (cancellation: CancellationException) {
            if (!enteredExecute) {
                withContext(NonCancellable) { store.applyEvent(taskId, TaskEvent.CancelRequested) }
            }
            throw cancellation
        } catch (error: Throwable) {
            if (!enteredExecute) {
                store.applyEvent(taskId, TaskEvent.ExecutionFailed(error.message ?: error::class.simpleName.orEmpty()))
            }
            throw error
        } finally {
            // The release itself suspends (registry lock); on a cancellation path it must still
            // run to completion or the ref leaks and the registry entry is never evicted.
            if (grouped) withContext(NonCancellable) { releaseParentMutex(parentToolCallId) }
        }
    }

    /**
     * Resume an [TaskState.Interrupted] run (SPEC.md M6, maintainer decisions #1/#3): keep the
     * SAME [taskId], seed a NEW child with the original [prompt] plus the persisted [progressSummary]
     * as context, and drive the run to a fresh terminal. There is no provider state to continue —
     * "resume" is a re-spawn from the summary, never a transcript replay (decision #1).
     *
     * **Single-active-handle is enforced in persisted state, not an in-memory flag (decision #3).**
     * The first thing this does is atomically take the `Interrupted -> Resuming` edge via
     * [TaskRunStore.claimResume], the ONLY edge out of `Interrupted`. claimResume reports the win
     * DIRECTLY (true only for the caller that found the run `Interrupted` before the fold), not via
     * the post-fold state — so a concurrent resume that arrives during the `Resuming` window (after
     * a sibling took the edge but before the child's first event flips it to `Running`) loses and
     * is rejected with [IllegalStateException] BEFORE spawning anything. A rejected resume therefore
     * never creates a second handle.
     *
     * @throws IllegalStateException if the run does not exist or is not currently resumable.
     */
    suspend fun resume(
        taskId: Uuid,
        sub: Assistant,
        prompt: String,
        progressSummary: String,
        parentModelId: Uuid?,
        settings: Settings,
        tools: List<Tool> = emptyList(),
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        budget: TaskBudget = defaultBudget,
    ): TaskRunResult {
        // The single-active-handle gate: atomically take the Interrupted -> Resuming edge. Only the
        // caller that drove the REAL transition wins; a losing concurrent resume (it found the run
        // already Resuming/Running) or a resume of a non-interrupted/absent run gets false and is
        // rejected here, BEFORE resolving the model or spawning anything — so it has no side
        // effects. claimResume reports the win directly, closing the Resuming-window race that an
        // applyEvent-return check could not distinguish (review finding #2).
        check(store.claimResume(taskId)) {
            "task $taskId is not resumable: at most one active handle per task"
        }
        // Everything past the committed Interrupted -> Resuming edge runs inside the closure:
        // model resolution can throw and slot acquisition can be cancelled, and both must
        // restore Interrupted (see resumeWithClosure).
        return resumeWithClosure(taskId, progressSummary) { markExecuteEntered ->

        val modelId = resolveSubagentModel(
            sub = sub.toAssistantConfig(),
            parentModelId = parentModelId,
            turn = TurnConfig(
                defaultModelId = settings.chatModelId,
                providers = emptyList(),
                assistants = emptyList(),
            ),
        )
        val model = settings.findModelById(modelId)
            ?: error("Subagent model not found for id $modelId")

        val ephemeralSub = sub.copy(
            chatModelId = model.id,
            enableMemory = false,
            enableRecentChatsReference = false,
        )
        val maxSteps = sub.maxSteps ?: budget.maxSteps
        // Seed the new child with the original task plus the persisted progress summary as context
        // (decision #1). The summary is injected, not replayed: a blank summary degrades to the
        // bare prompt, which is still a valid fresh spawn.
        val messages = listOf(UIMessage.user(resumePrompt(prompt, progressSummary)))
        val childTools = filterToolsForSubagent(tools, SPAWN_TOOL_NAME)

        // The run already holds no slot (its handle died); re-acquire one, then drive the engine.
        // From Resuming, the first child event folds Resuming -> Running (an early SlotClaimed is an
        // illegal no-op the reducer absorbs), so execute() is reused unchanged.
        globalSemaphore(budget.globalConcurrency).withPermit {
            markExecuteEntered()
            execute(taskId, settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus, budget)
        }

        }
    }

    /**
     * Lifecycle closure for the Resuming window (review mustFix): once claimResume committed
     * `Interrupted -> Resuming`, ANY pre-execute exit — model-resolution failure, cancellation
     * while waiting for a slot — must restore `Interrupted` (with the same [progressSummary]) so
     * the task stays resumable instead of stranding in Resuming and blocking every future resume.
     * If execute() already reached a real terminal (e.g. user cancel mid-run -> Cancelled), the
     * replayed ProcessInterrupted is absorbed by the reducer's terminal identity.
     */
    private suspend fun resumeWithClosure(
        taskId: Uuid,
        progressSummary: String,
        block: suspend (markExecuteEntered: () -> Unit) -> TaskRunResult,
    ): TaskRunResult {
        // Same pre-execute scoping as run(): once execute() is entered its own handlers own the
        // terminal, and a replayed ProcessInterrupted would pollute the event log.
        var enteredExecute = false
        return try {
            block { enteredExecute = true }
        } catch (cancellation: CancellationException) {
            if (!enteredExecute) {
                withContext(NonCancellable) { store.applyEvent(taskId, TaskEvent.ProcessInterrupted(progressSummary)) }
            }
            throw cancellation
        } catch (error: Throwable) {
            if (!enteredExecute) {
                store.applyEvent(taskId, TaskEvent.ProcessInterrupted(progressSummary))
            }
            throw error
        }
    }

    /**
     * The seed message a resume injects: the original task prompt followed by the persisted
     * progress summary as recovery context. Kept tiny and pure so the prompt shape is testable.
     */
    private fun resumePrompt(prompt: String, progressSummary: String): String =
        if (progressSummary.isBlank()) {
            prompt
        } else {
            "$prompt\n\n[Resuming a previously interrupted run. Progress so far:\n$progressSummary]"
        }

    /**
     * The slot-acquired body: drive the child through the engine seam, fold usage against the
     * budget, and terminate the run. Separated from [run] so the concurrency wrappers stay thin.
     */
    private suspend fun execute(
        taskId: Uuid,
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        ephemeralSub: Assistant,
        childTools: List<Tool>,
        maxSteps: Int,
        processingStatus: MutableStateFlow<String?>,
        budget: TaskBudget,
    ): TaskRunResult {
        val startedAt = monotonicNow()
        var finalMessages: List<UIMessage> = messages
        var progressed = false
        // Track the latest folded usage locally so the terminal result carries the run's final
        // counters for the renderer's budget row (review finding #1) without a second store read.
        var lastUsage = TaskBudgetUsage()

        return try {
            // Queued -> Starting: a concurrency slot is now held. This SUSPENDING write lives
            // inside the guarded try (review mustFix round 4 #2): the caller's enteredExecute flag
            // is already set, so a cancellation striking this write must be terminal-closed HERE —
            // neither closure outside would catch it.
            store.applyEvent(taskId, TaskEvent.SlotClaimed)
            try {
                generate(settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            finalMessages = chunk.messages
                            if (!progressed) {
                                // First child event: Starting -> Running.
                                store.applyEvent(taskId, TaskEvent.ChildProgressed)
                                progressed = true
                            }
                            // Persist the child's latest progress as an event summary (review
                            // finding #4). recoverInterruptedRuns() seeds a resume from the LAST
                            // persisted summary; without this the history is always empty, so a
                            // process-death recovery re-spawns from the bare prompt and repeats any
                            // side effects the child already performed. The summary is the latest
                            // assistant text — the same projection the terminal result extracts — so
                            // a resumed child sees what it had already done. A progress chunk with no
                            // assistant text yet (blank) is skipped: an empty marker would teach
                            // recovery nothing and only churn the sequence cursor.
                            val progressText = extractFinalAssistantText(chunk.messages)
                            if (progressText.isNotBlank()) {
                                store.appendEventSummary(taskId, progressText)
                            }
                            // Fold the child's CUMULATIVE usage (steps from assistant turns, tokens
                            // from the message usage counters, elapsed from the monotonic clock) and
                            // STOP the run on the first cap breach. A bare `return@collect` only
                            // skips one chunk and lets the child keep producing chunks / running
                            // tools past the cap (review finding #3); throwing the sentinel out of
                            // collect cancels the upstream child flow's coroutine so it actually
                            // stops, then is absorbed just below.
                            lastUsage = usageOf(chunk.messages, startedAt)
                            val breach = store.recordUsage(taskId, lastUsage, budget)
                            if (breach != null) {
                                val terminal = store.applyEvent(taskId, TaskEvent.BudgetExceeded(breach))
                                throw BudgetStop(terminal)
                            }
                        }
                    }
                }
            } catch (stop: BudgetStop) {
                // The breach already fired TaskEvent.BudgetExceeded and the collection unwound,
                // cancelling the child flow. The run is BudgetExhausted; surface the partial text.
                return resultOf(
                    text = extractFinalAssistantText(finalMessages),
                    terminal = stop.terminal,
                    usage = lastUsage,
                    maxSteps = maxSteps,
                )
            }
            // The run finished cleanly: drive FinalResult and report the Succeeded terminal.
            val result = extractFinalAssistantText(finalMessages)
            val terminal = store.applyEvent(taskId, TaskEvent.FinalResult(result))
            resultOf(text = result, terminal = terminal, usage = lastUsage, maxSteps = maxSteps)
        } catch (cancellation: CancellationException) {
            // Structured cancellation (parent generation stopped): surface the cancel to the
            // lifecycle, then rethrow so the coroutine tree tears down correctly. NonCancellable
            // is load-bearing: the store suspends (Room), and without it this terminal write is
            // itself cancelled at its first suspension point — the run would stay Running forever.
            withContext(NonCancellable) { store.applyEvent(taskId, TaskEvent.CancelRequested) }
            throw cancellation
        } catch (error: Throwable) {
            // Provider/unexpected error: Failed terminal, surfaced to the parent as a structured
            // tool-error string (existing tool-error-as-output behavior) rather than a thrown
            // exception that would abort the parent turn.
            val reason = error.message ?: error::class.simpleName.orEmpty()
            val terminal = store.applyEvent(taskId, TaskEvent.ExecutionFailed(reason))
            resultOf(text = "Subagent failed: $reason", terminal = terminal, usage = lastUsage, maxSteps = maxSteps)
        }
    }

    /**
     * Package the terminal [TaskState] for the result. [terminal] may be null (a no-op store, or a
     * missing run): the result then degrades to [TaskState.Succeeded] with the text, matching the
     * renderer's legacy fallback so nothing crashes.
     */
    private fun resultOf(text: String, terminal: TaskState?, usage: TaskBudgetUsage, maxSteps: Int): TaskRunResult =
        TaskRunResult(
            text = text,
            state = terminal ?: TaskState.Succeeded(text),
            usage = usage,
            maxSteps = maxSteps,
        )

    /**
     * The child's cumulative usage at this point in the stream: one step per assistant message,
     * total tokens summed across message usage counters, elapsed time from the monotonic clock.
     * Cumulative (not per-chunk) so [TaskBudgetUsage.record]'s component-wise max is a no-op merge
     * and stale/out-of-order chunks stay harmless (TASK_BUDGET_MONOTONE).
     */
    private fun usageOf(messages: List<UIMessage>, startedAt: Duration): TaskBudgetUsage {
        val steps = messages.count { it.role == MessageRole.ASSISTANT }
        val tokens = messages.sumOf { (it.usage?.totalTokens ?: 0).toLong() }
        val elapsed = (monotonicNow() - startedAt).coerceAtLeast(Duration.ZERO)
        return TaskBudgetUsage(steps = steps, tokens = tokens, elapsed = elapsed)
    }
}
