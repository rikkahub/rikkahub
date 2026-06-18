package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted shell run (issue #291 design proposal): the durable state of a `workspace_shell`
 * command that may auto-background. Like [AgentEventEntity]/[TaskRunEntity] it is SUMMARY-ONLY — it
 * carries the spawn spec, the latest lifecycle state, the output-file pointer, and the terminal
 * result, but never a live [me.rerere.workspace.ShellRunHandle]; the handle lives only in the
 * detached awaiter coroutine on `AppScope` and dies with the process.
 *
 * Lifecycle (the proposal's state machine):
 * `STARTED -> FOREGROUND_WAITING -> {exit-inline | DETACHED} -> BACKGROUND_RUNNING ->
 *  {SUCCEEDED | FAILED | KILLED_SIZE | KILLED_TIMEOUT | INTERRUPTED_PROCESS_DEATH}`.
 *
 * Two invariants rest on the [status] column, both enforced by conditional `WHERE status IN (...)`
 * updates in [me.rerere.rikkahub.data.db.dao.ShellRunDAO] (the same posture as
 * `AgentEventDAO.markConsumed`'s `AND status = 'PENDING'` claim):
 *  - SINGLE_TERMINAL — only one caller may flip a non-terminal row to a terminal status; the
 *    detached awaiter and a cold-start recovery scan racing the same row serialize, and the loser's
 *    update matches zero rows.
 *  - PROCESS_DEATH_IS_INTERRUPTED — cold-start marks every still-running row
 *    [ShellRunStatus.INTERRUPTED_PROCESS_DEATH], never a fabricated SUCCEEDED.
 *
 * No Room `@ForeignKey` to the conversation (same rationale as [AgentEventEntity]): the conversation
 * is persisted through a separate path, so cleanup is the explicit
 * [me.rerere.rikkahub.data.db.dao.ShellRunDAO.deleteByConversationId] hook, not a schema-coupled FK.
 */
@Entity(
    tableName = "shell_runs",
    indices = [
        // Per-conversation cleanup (deleteByConversationId) filters by conversation_id.
        Index(value = ["conversation_id", "status"]),
        // The cold-start recovery scan is conversation-agnostic — `WHERE status IN (...)`. The index
        // above leads with conversation_id, so a status-only predicate full-scans as rows accumulate.
        // A status-leading index keeps that recovery scan off a full table walk.
        Index(value = ["status"]),
    ],
)
data class ShellRunEntity(
    /** Stable run id — survives process death and is the completion event's `dedupeKey`. */
    @PrimaryKey
    @ColumnInfo("task_id")
    val taskId: String,
    /** The conversation whose generation started this run; the completion event is injected here. */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** The workspace this command ran against (diagnostics + retention scoping). */
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    val command: String,
    val cwd: String,
    /** App-private file the redirected stdout/stderr were streamed to (the `workspace_shell_tail` source). */
    @ColumnInfo("output_path")
    val outputPath: String,
    /** Opaque process metadata (pid when the platform exposes it). Diagnostics only; nullable. */
    @ColumnInfo("pid_meta")
    val pidMeta: String? = null,
    /** Persisted name of a [ShellRunStatus] — the recovery scan + the terminal CAS filter on this. */
    val status: String,
    /** The process exit code once terminal; null while running or when killed before exit. */
    @ColumnInfo("exit_code")
    val exitCode: Int? = null,
    /** Bytes the command produced to [outputPath] at the terminal write. */
    @ColumnInfo("byte_count")
    val byteCount: Long = 0,
    /** Persisted name of a [me.rerere.workspace.ShellKillReason] when killed; null otherwise. */
    @ColumnInfo("kill_reason")
    val killReason: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long? = null,
    @ColumnInfo("detached_at")
    val detachedAt: Long? = null,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
    @ColumnInfo("tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo("tool_node_id")
    val toolNodeId: String? = null,
    @ColumnInfo("tool_message_id")
    val toolMessageId: String? = null,
)

/**
 * The value domain of [ShellRunEntity.status] — one persisted tag per lifecycle state. Persisted by
 * [name]; renaming an entry is a data-format break and is forbidden without a migration (mirroring
 * [AgentEventStatus]/[TaskRunStateTag]).
 */
enum class ShellRunStatus(val isTerminal: Boolean = false) {
    /** A row was created; the process is being started. */
    STARTED,

    /** The foreground wait is in progress (await up to `detachAfterSeconds`). */
    FOREGROUND_WAITING,

    /** `detachAfterSeconds` (or a user stop) elapsed before exit; the run was backgrounded. */
    DETACHED,

    /** The detached awaiter is running on `AppScope`, waiting for the process to exit. */
    BACKGROUND_RUNNING,

    /** Exited 0 / completed cleanly. */
    SUCCEEDED(isTerminal = true),

    /** Exited non-zero. */
    FAILED(isTerminal = true),

    /** Killed by the output-file size watchdog. */
    KILLED_SIZE(isTerminal = true),

    /** Killed by the hard timeout. */
    KILLED_TIMEOUT(isTerminal = true),

    /** Cut off by process death; cold-start recovery marks running rows this and never SUCCEEDED. */
    INTERRUPTED_PROCESS_DEATH(isTerminal = true),
    ;

    companion object {
        /**
         * The non-terminal states the cold-start recovery scan folds to
         * [INTERRUPTED_PROCESS_DEATH]: every state that claims the run is still in flight. These are
         * exactly the states the terminal CAS guards against (a terminal write loses if the row is
         * already terminal).
         */
        val RUNNING: Set<ShellRunStatus> =
            setOf(STARTED, FOREGROUND_WAITING, DETACHED, BACKGROUND_RUNNING)

        fun fromPersistedOrNull(value: String): ShellRunStatus? =
            entries.firstOrNull { it.name == value }
    }
}
