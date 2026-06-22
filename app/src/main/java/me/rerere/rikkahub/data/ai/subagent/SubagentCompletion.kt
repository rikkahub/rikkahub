package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import kotlin.uuid.Uuid

/**
 * The completion event a DETACHED background subagent run enqueues when it reaches a terminal
 * [TaskState] — the Task-path analogue of [me.rerere.rikkahub.data.ai.shellrun.ShellCompletion]. It is
 * always a durable agent-event; the drain resolves it back into the original `agent`/`task` tool
 * output when a persisted taskId -> tool anchor exists, and otherwise delivers a synthetic
 * assistant-tool message.
 *
 * [dedupeKey] is the run's [taskId], so AT_MOST_ONCE is inherited from the queue's unique enqueue +
 * claim/consume transaction: a replayed terminal (the detached awaiter's win AND a cold-start recovery
 * scan both mapping the same row) collapses to a single delivery.
 *
 * The payload is opaque to the queue (the drain only renders it as text), so the shape here is the
 * agent-facing contract, not a queue concern.
 */
data class SubagentCompletion(
    val conversationId: Uuid,
    val taskId: Uuid,
    val kind: String,
    val payloadJson: String,
    val dedupeKey: String,
) {
    companion object {
        const val KIND = "subagent.completed"

        /**
         * Build the completion for a terminal background subagent run. PURE so the payload shape is
         * unit-testable without the store or the queue. A non-terminal [state] (only reachable via the
         * cold-start fail-close path) is reported as `FAILED` / "interrupted" — never as success.
         */
        fun of(
            conversationId: Uuid,
            taskId: Uuid,
            state: TaskState,
            steps: Int,
            tokens: Long,
        ): SubagentCompletion {
            val (status, summary, error) = describe(state)
            val payload = buildJsonObject {
                put("taskId", taskId.toString())
                put("status", status)
                if (summary != null) put("summary", summary)
                if (error != null) put("error", error)
                put("steps", steps)
                put("tokens", tokens)
            }
            return SubagentCompletion(
                conversationId = conversationId,
                taskId = taskId,
                kind = KIND,
                payloadJson = payload.toString(),
                dedupeKey = taskId.toString(),
            )
        }

        /** Map a [TaskState] to (status, summary?, error?). Non-terminal => FAILED/"interrupted". */
        private fun describe(state: TaskState): Triple<String, String?, String?> = when (state) {
            is TaskState.Succeeded -> Triple("SUCCEEDED", state.summary, null)
            is TaskState.Failed -> Triple("FAILED", null, state.error)
            is TaskState.Cancelled -> Triple("CANCELLED", null, "cancelled")
            is TaskState.BudgetExhausted ->
                Triple("BUDGET_EXHAUSTED", null, "budget exhausted: ${state.breach.cap}")

            else -> Triple("FAILED", null, "interrupted")
        }
    }

    /**
     * Pure, shared mapping for a durable enqueue row — the same seam the store uses to insert the
     * completion atomically with the terminal state.
     */
    fun asPendingAgentEventEntity(
        eventId: String,
        conversationId: String = this.conversationId.toString(),
        enqueueSeq: Long,
        createdAt: Long,
    ): AgentEventEntity = AgentEventEntity(
        id = eventId,
        conversationId = conversationId,
        dedupeKey = dedupeKey,
        enqueueSeq = enqueueSeq,
        kind = kind,
        payloadJson = payloadJson,
        status = AgentEventStatus.PENDING.name,
        createdAt = createdAt,
    )
}
