package me.rerere.rikkahub.data.ai.shellrun

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import kotlin.uuid.Uuid

/**
 * The #290 completion event a detached shell run enqueues when it reaches a terminal status (issue
 * #291). It is ALWAYS a NEW synthetic agent-event, never a mutation of the historical tool part:
 * `Tool.execute` gets only args (no `toolCallId`), so the original `workspace_shell` tool call cannot
 * be correlated — the same ABI gap the design proposal calls out.
 *
 * [dedupeKey] is the run's [taskId], so AT_MOST_ONCE_COMPLETION is inherited from #290's unique
 * enqueue + claim/consume transaction: a replayed terminal (the detached awaiter's win and a
 * cold-start recovery scan both mapping the same row) collapses to a single delivery.
 *
 * The payload is opaque to the queue — the drain only renders it as text — so the shape here is the
 * agent-facing contract, not a queue concern.
 */
data class ShellCompletion(
    val conversationId: Uuid,
    val taskId: Uuid,
    val kind: String,
    val payloadJson: String,
    val dedupeKey: String,
) {
    companion object {
        const val KIND = "workspace_shell.completed"

        /**
         * Build the completion for a terminal shell run. PURE so the payload shape is unit-testable
         * without the store or the queue.
         *
         * @param status the terminal [ShellRunStatus] the run reached.
         * @param exitCode the process exit code, or null when the run was killed before exit / died.
         * @param outputRef the app-private output file path the agent reads via `workspace_shell_tail`.
         * @param tail a best-effort trailing snippet of the output (so the model has context inline).
         * @param byteCount bytes the command produced.
         */
        fun of(
            conversationId: Uuid,
            taskId: Uuid,
            status: ShellRunStatus,
            exitCode: Int?,
            outputRef: String,
            tail: String,
            byteCount: Long,
        ): ShellCompletion {
            val payload = buildJsonObject {
                put("taskId", taskId.toString())
                put("status", status.name)
                if (exitCode != null) put("exitCode", exitCode)
                put("outputRef", outputRef)
                put("tail", tail)
                put("byteCount", byteCount)
            }
            return ShellCompletion(
                conversationId = conversationId,
                taskId = taskId,
                kind = KIND,
                payloadJson = payload.toString(),
                dedupeKey = taskId.toString(),
            )
        }
    }

    /**
     * Pure, shared mapping for durable #290 enqueue rows. Reuse this seam from store-level
     * insertion and any other completion-to-event seam to keep `kind/payload/dedupeKey` atomic.
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
