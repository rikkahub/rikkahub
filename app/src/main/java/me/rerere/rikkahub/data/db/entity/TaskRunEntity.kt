package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.rerere.common.json.JsonInstant

/**
 * One persisted task run (SPEC.md M2, maintainer decision #1): SUMMARY-ONLY. This row carries the
 * spawn spec, the latest lifecycle state, cumulative budget counters, an event-summary list and
 * the final answer — never the full child transcript, never a live coroutine handle. Resume
 * re-spawns from [prompt] + the persisted summaries; nothing here allows a transcript replay.
 *
 * All embedded JSON columns ([eventSummaries], [pendingApproval]) decode additively:
 * [TaskRunEventSummary]/[TaskRunPendingApproval] default every non-required field and JsonInstant
 * ignores unknown keys, so payloads written by older or newer app versions both decode
 * (TASK_SERIALIZATION_ADDITIVE).
 */
@Entity(
    tableName = "task_runs",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["latest_state"]),
    ],
)
data class TaskRunEntity(
    /** Stable task id — survives interruption and resume (a resume keeps the SAME id). */
    @PrimaryKey
    val id: String,
    /** The parent conversation whose generation spawned this task. */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** The spawn tool call in the parent transcript that owns this task's live output. */
    @ColumnInfo("parent_tool_call_id")
    val parentToolCallId: String,
    @ColumnInfo("agent_type_id")
    val agentTypeId: String,
    val prompt: String,
    /** Persisted name of a [TaskRunStateTag] — the recovery scan filters on this column. */
    @ColumnInfo("latest_state")
    val latestState: String,
    /** JSON array of [TaskRunEventSummary] — the summary-only event list (decision #1). */
    @ColumnInfo("event_summaries", defaultValue = "[]")
    val eventSummaries: String = "[]",
    /** Monotone sequence of the last applied event; redelivered events below it are stale. */
    @ColumnInfo("event_sequence", defaultValue = "0")
    val eventSequence: Long = 0,
    // Cumulative budget counters (TaskBudgetUsage); monotone, mirrored here so a recovered run
    // resumes against its remaining budget instead of a fresh one.
    @ColumnInfo("usage_steps", defaultValue = "0")
    val usageSteps: Int = 0,
    @ColumnInfo("usage_tokens", defaultValue = "0")
    val usageTokens: Long = 0,
    @ColumnInfo("usage_elapsed_ms", defaultValue = "0")
    val usageElapsedMs: Long = 0,
    @ColumnInfo("final_result")
    val finalResult: String? = null,
    @ColumnInfo("final_error")
    val finalError: String? = null,
    /** JSON [TaskRunPendingApproval] while an allowlisted child approval is waiting; else null. */
    @ColumnInfo("pending_approval")
    val pendingApproval: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    /**
     * True for a DETACHED background run (the parent turn already ended). A terminal transition on a
     * background row durably enqueues a `SubagentCompletion` agent_event in the SAME transaction, so
     * the idle-drain can deliver it; foreground runs never enqueue. Additive default 0 ⇒ auto-migration.
     */
    @ColumnInfo("is_background", defaultValue = "0")
    val isBackground: Boolean = false,
    /**
     * The parent `agent`/`task` tool anchor (call id + transcript node/message), persisted after the
     * spawn output is saved so the background completion resolves back into the original tool output
     * instead of a synthetic message. Null until attached. Nullable ⇒ auto-migration.
     */
    @ColumnInfo("tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo("tool_node_id")
    val toolNodeId: String? = null,
    @ColumnInfo("tool_message_id")
    val toolMessageId: String? = null,
) {
    /**
     * Null when the stored blob cannot be parsed — callers must distinguish "no events yet"
     * (`[]`) from a corrupt column instead of silently treating corruption as an empty history
     * (a resume seeded from a falsely-empty summary would re-run completed side effects).
     */
    fun decodeEventSummaries(): List<TaskRunEventSummary>? = runCatching {
        JsonInstant.decodeFromString<List<TaskRunEventSummary>>(eventSummaries)
    }.getOrNull()

    /** Null when no approval is pending OR the blob is corrupt — both mean "nothing to forward". */
    fun decodePendingApproval(): TaskRunPendingApproval? = pendingApproval?.let { blob ->
        runCatching { JsonInstant.decodeFromString<TaskRunPendingApproval>(blob) }.getOrNull()
    }

    companion object {
        fun encodeEventSummaries(summaries: List<TaskRunEventSummary>): String =
            JsonInstant.encodeToString(summaries)

        fun encodePendingApproval(approval: TaskRunPendingApproval): String =
            JsonInstant.encodeToString(approval)
    }
}

/**
 * One summary-only event in a task run's history. Additive contract: only [sequence] and
 * [summary] are required; every later field MUST carry a default so rows written before the
 * field existed keep decoding (TASK_SERIALIZATION_ADDITIVE).
 */
@Serializable
data class TaskRunEventSummary(
    val sequence: Long,
    val summary: String,
    val timestamp: Long = 0,
    val kind: String = KIND_PROGRESS,
) {
    companion object {
        const val KIND_PROGRESS = "progress"
    }
}

/**
 * The allowlisted child tool call currently waiting on the parent's approval surface,
 * namespaced `taskId/childToolCallId` (decision #2).
 */
@Serializable
data class TaskRunPendingApproval(
    val childToolCallId: String,
    val toolName: String,
)

/**
 * The value domain of [TaskRunEntity.latestState] — one persisted tag per lifecycle state
 * (`me.rerere.ai.runtime.task.TaskState`). Persisted by [name]; renaming an entry is a
 * data-format break and is forbidden without a migration.
 */
enum class TaskRunStateTag(val isTerminal: Boolean = false) {
    CREATED,
    QUEUED,
    STARTING,
    RUNNING,
    WAITING_APPROVAL,
    RESUMING,
    SUCCEEDED(isTerminal = true),
    FAILED(isTerminal = true),
    CANCELLED(isTerminal = true),
    BUDGET_EXHAUSTED(isTerminal = true),

    /** Cut off (process death); resumable, hence NOT terminal (decisions #1/#3). */
    INTERRUPTED,
    ;

    companion object {
        /**
         * The states the startup recovery scan marks [INTERRUPTED]: everything that claims to be
         * in flight. Terminals are final and INTERRUPTED rows stay interrupted (resume is
         * user-explicit, never automatic).
         */
        val ACTIVE: Set<TaskRunStateTag> =
            setOf(CREATED, QUEUED, STARTING, RUNNING, WAITING_APPROVAL, RESUMING)

        fun fromPersistedOrNull(value: String): TaskRunStateTag? =
            entries.firstOrNull { it.name == value }
    }
}
