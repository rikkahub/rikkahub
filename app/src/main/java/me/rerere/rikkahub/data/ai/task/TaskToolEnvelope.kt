package me.rerere.rikkahub.data.ai.task

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.runtime.task.TaskState

/**
 * The wire-format of the `{task:{...}}` tool-output envelope the spawn ("task") tool emits and the
 * live renderer (`TaskToolUI`) parses (SPEC.md M5, review finding #1). Keeping the emit side here —
 * one function, JVM-testable, no Compose/Context — is what makes the renderer reachable in
 * production: before this, the tool returned bare final text and the renderer only ever hit its
 * legacy fallback, so terminal status / budget counters / interrupted-resume were unreachable.
 *
 * The status vocabulary MUST stay in lock-step with `TaskToolUI.parseStatus`; [taskWireStatus]
 * below is the emit half and a unit test pins emit -> parse round-trips for every terminal.
 *
 * This carries TERMINAL state only — a synchronous tool execute cannot emit intermediate
 * envelopes; live status while the child runs is the tracked streaming-seam follow-up gap.
 */
fun buildTaskEnvelope(result: TaskRunResult): JsonObject = buildJsonObject {
    put("task", buildJsonObject {
        put("status", taskWireStatus(result.state))
        summaryOf(result.state, result.text)?.let { put("summary", it) }
        put("budget", buildJsonObject {
            put("steps", result.usage.steps)
            put("maxSteps", result.maxSteps)
            put("tokens", result.usage.tokens)
        })
        (result.state as? TaskState.WaitingApproval)?.let { waiting ->
            put("pendingApproval", buildJsonObject {
                put("toolName", waiting.request.toolName)
                put("childToolCallId", waiting.request.childToolCallId)
            })
        }
    })
}

/**
 * Map a [TaskState] to its stable lower_snake wire status. The strings are the exact ones
 * `TaskToolUI.parseStatus` accepts; drifting either side without the other is a Hyrum's-law break
 * the round-trip test guards.
 */
fun taskWireStatus(state: TaskState): String = when (state) {
    TaskState.Created -> "created"
    TaskState.Queued -> "queued"
    TaskState.Starting -> "starting"
    TaskState.Running -> "running"
    is TaskState.WaitingApproval -> "waiting_approval"
    TaskState.Resuming -> "resuming"
    is TaskState.Succeeded -> "succeeded"
    is TaskState.Failed -> "failed"
    TaskState.Cancelled -> "cancelled"
    is TaskState.BudgetExhausted -> "budget_exhausted"
    is TaskState.Interrupted -> "interrupted"
}

/**
 * The summary a terminal state carries for the renderer: the state's own payload where it has one
 * (Succeeded/Interrupted), else the final text (Failed surfaces the error text, BudgetExhausted the
 * partial answer). Null/blank collapses to no `summary` key so the renderer shows none.
 */
private fun summaryOf(state: TaskState, text: String): String? = when (state) {
    is TaskState.Succeeded -> state.summary
    is TaskState.Interrupted -> state.progressSummary
    else -> text
}.takeIf { it.isNotBlank() }
