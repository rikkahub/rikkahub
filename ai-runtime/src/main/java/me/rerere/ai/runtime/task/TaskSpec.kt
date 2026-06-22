package me.rerere.ai.runtime.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Pure (Android-free) task-lifecycle domain for the subagent-spawn tool (SPEC.md M1).
 *
 * Everything in this package is neutral: no platform imports, no concrete tool names — the
 * approval allowlist in [TaskToolPolicy] carries caller-supplied names only (P6 token gate).
 */

/**
 * The immutable description of one spawned task — what to run, for whom, and under what budget.
 *
 * @param taskId stable identity of the task run; survives interruption and resume (a resume
 *   keeps the SAME task id and only spawns a new execution handle — maintainer decision #1).
 * @param parentConversationId the conversation whose generation spawned this task.
 * @param parentToolCallId the spawn tool call in the parent transcript that owns this task's
 *   live output; child approvals are namespaced `taskId/childToolCallId` under it.
 * @param agentTypeId the [AgentTypeSpec.id] this task runs as.
 * @param prompt the child's task prompt. On resume the persisted progress summary is injected
 *   as additional context next to this prompt — never a transcript replay (decision #1).
 * @param depth spawn depth of the task; the hard bound is [TaskBudget.maxDepth] (= 1: a child
 *   never spawns).
 * @param parentModelId the spawning model, inherited when the agent type pins none.
 */
data class TaskSpec(
    val taskId: Uuid,
    val parentConversationId: Uuid,
    val parentToolCallId: String,
    val agentTypeId: String,
    val prompt: String,
    val depth: Int = 1,
    val parentModelId: Uuid? = null,
    val budget: TaskBudget = TaskBudget(),
    /**
     * When true the run is DETACHED (background): the spawn tool returns immediately and the child
     * runs on an app-lifetime scope, its terminal delivered later via a durable completion event.
     * Default false keeps the synchronous, parent-awaited behaviour.
     */
    val isBackground: Boolean = false,
)

/**
 * Per-task resource budget. Defaults are the approved design's (SPEC.md, binding): 64 steps,
 * depth hard-bounded at 1, one concurrent task per parent, one globally (OQ1 resolution), 10 min wall time with
 * a 30 min hard ceiling no caller may exceed ([effectiveWallTime] clamps).
 *
 * Token usage is tracked from child usage counters but uncapped by default ([maxTokens] null).
 */
data class TaskBudget(
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val perParentConcurrency: Int = DEFAULT_PER_PARENT_CONCURRENCY,
    val globalConcurrency: Int = DEFAULT_GLOBAL_CONCURRENCY,
    val wallTime: Duration = DEFAULT_WALL_TIME,
    val maxTokens: Long? = null,
) {
    /** The wall-time cap actually enforced: the requested [wallTime], never above the hard max. */
    val effectiveWallTime: Duration
        get() = minOf(wallTime, HARD_MAX_WALL_TIME)

    /**
     * The first cap the given usage exceeds, or null while every counter is within its cap.
     * Caps are inclusive: a run that consumed exactly its budget has not breached it.
     *
     * Because [TaskBudgetUsage.record] is monotone and caps are fixed per run, a non-null
     * result here is permanent — the TASK_BUDGET_MONOTONE invariant.
     */
    fun firstBreach(usage: TaskBudgetUsage): TaskBudgetBreach? = when {
        usage.steps > maxSteps -> TaskBudgetBreach(TaskBudgetCap.Steps, usage)
        maxTokens != null && usage.tokens > maxTokens -> TaskBudgetBreach(TaskBudgetCap.Tokens, usage)
        usage.elapsed > effectiveWallTime -> TaskBudgetBreach(TaskBudgetCap.WallTime, usage)
        else -> null
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 64
        const val DEFAULT_MAX_DEPTH = 1
        const val DEFAULT_PER_PARENT_CONCURRENCY = 1
        // 1 since the OQ1 resolution: Android 15 caps the cumulative backgrounded dataSync FGS
        // budget per day, and concurrent SSE streams multiply radio/wake-lock pressure under it.
        // A later increase should come from device state (charging + unmetered) or a user
        // setting feeding TaskBudget — change this constant only with that mechanism.
        const val DEFAULT_GLOBAL_CONCURRENCY = 1
        val DEFAULT_WALL_TIME = 10.minutes
        val HARD_MAX_WALL_TIME = 30.minutes
    }
}

/**
 * Cumulative budget counters for one task run.
 *
 * Children report CUMULATIVE counters, so accumulation is a component-wise max ([record]):
 * counters can never decrease, and stale, replayed, or out-of-order reports are harmless
 * (record is idempotent, commutative, and associative) — the TASK_BUDGET_MONOTONE invariant.
 */
data class TaskBudgetUsage(
    val steps: Int = 0,
    val tokens: Long = 0,
    val elapsed: Duration = Duration.ZERO,
) {
    /** Merge a child's cumulative usage report; the result is >= both inputs component-wise. */
    fun record(reported: TaskBudgetUsage): TaskBudgetUsage = TaskBudgetUsage(
        steps = maxOf(steps, reported.steps),
        tokens = maxOf(tokens, reported.tokens),
        elapsed = maxOf(elapsed, reported.elapsed),
    )
}

/** Which budget cap a run exceeded. */
enum class TaskBudgetCap { Steps, Tokens, WallTime }

/** A cap breach: which cap, and the usage snapshot that exceeded it (surfaced to the parent). */
data class TaskBudgetBreach(
    val cap: TaskBudgetCap,
    val usage: TaskBudgetUsage,
)

/**
 * A spawnable agent type, derived from a spawnable assistant (stable [id] = assistant UUID
 * string in v1; the separate [assistantId] keeps non-UUID preset ids possible later without a
 * schema change).
 */
data class AgentTypeSpec(
    val id: String,
    val assistantId: Uuid,
    val displayName: String,
    val description: String = "",
    val defaultBudget: TaskBudget = TaskBudget(),
    val toolPolicy: TaskToolPolicy = TaskToolPolicy(),
)

/**
 * Child tool policy (maintainer decision #2): ONLY tools on this explicit allowlist forward
 * their approval request to the parent's approval surface; every other approval-gated child
 * tool auto-denies with the reason recorded in the task summary. Allowlist, never heuristics.
 *
 * The conservative default is an EMPTY allowlist — forward nothing — matching today's
 * strip-all-approval-tools subagent behavior.
 */
data class TaskToolPolicy(
    val approvalForwardAllowlist: Set<String> = emptySet(),
) {
    fun forwardsApprovalFor(toolName: String): Boolean = toolName in approvalForwardAllowlist
}
