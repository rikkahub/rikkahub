package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TaskRunEntity

/**
 * Row-level access to persisted task runs (SPEC.md M2). Lifecycle invariants (legal transitions,
 * single-active-handle on resume) live in the repository/domain layer, not in queries here.
 */
@Dao
interface TaskRunDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: TaskRunEntity)

    @Query("SELECT * FROM task_runs WHERE id = :id")
    suspend fun getById(id: String): TaskRunEntity?

    /**
     * Attach a background run's tool anchor (the parent `agent`/`task` tool call + transcript
     * node/message) so its detached completion resolves back into the original tool output. Idempotent:
     * re-attaching the SAME (call, node, message) returns 1; ANY conflicting anchor matches zero rows
     * and returns 0. Mirrors ShellRunDAO.attachToolAnchor — all THREE fields must match, so a forked
     * conversation (which regenerates node ids while preserving the copied call/message ids) cannot
     * overwrite the original conversation's persisted anchor.
     */
    @Query(
        "UPDATE task_runs SET tool_call_id = :toolCallId, tool_node_id = :toolNodeId, " +
            "tool_message_id = :toolMessageId WHERE id = :taskId AND " +
            "((tool_call_id IS NULL AND tool_node_id IS NULL AND tool_message_id IS NULL) OR " +
            "(tool_call_id = :toolCallId AND tool_node_id = :toolNodeId AND tool_message_id = :toolMessageId))"
    )
    suspend fun attachToolAnchor(
        taskId: String,
        toolCallId: String,
        toolNodeId: String,
        toolMessageId: String,
    ): Int

    @Query("SELECT * FROM task_runs WHERE id = :id")
    fun getByIdFlow(id: String): Flow<TaskRunEntity?>

    @Query("SELECT * FROM task_runs WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun listByConversationFlow(conversationId: String): Flow<List<TaskRunEntity>>

    /** Recovery scan: rows whose persisted state is in [states] (TaskRunStateTag names). */
    @Query("SELECT * FROM task_runs WHERE latest_state IN (:states)")
    suspend fun listByStates(states: Set<String>): List<TaskRunEntity>

    /**
     * Background-run recovery scan: only `is_background = 1` rows whose persisted state is in [states].
     * A detached background run whose parent turn already ended must be FAIL-CLOSED to a terminal at
     * cold start (no resume), distinct from a foreground run that folds to the resumable Interrupted.
     */
    @Query("SELECT * FROM task_runs WHERE is_background = 1 AND latest_state IN (:states)")
    suspend fun listBackgroundByStates(states: Set<String>): List<TaskRunEntity>

    /**
     * Foreground-run recovery scan: only `is_background = 0` rows in [states]. The generic interrupt
     * scan uses THIS (not [listByStates]) so it can never fold a background row to the resumable
     * Interrupted — background rows are owned exclusively by the fail-close pass ([listBackgroundByStates]).
     * Making the two scans operate on DISJOINT row sets removes any dependency on their ordering or on
     * the fail-close pass succeeding (a thrown fail-close leaves the background row active for the next
     * cold start, never lost to Interrupted).
     */
    @Query("SELECT * FROM task_runs WHERE is_background = 0 AND latest_state IN (:states)")
    suspend fun listForegroundByStates(states: Set<String>): List<TaskRunEntity>

    @Query("DELETE FROM task_runs WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Retention sweep candidates (M6): every row in a terminal/deleted [states] set, with just the
     * columns the sweeper needs to decide (conversation + recency). The newest-N-per-conversation
     * windowing is done in the repository so the same logic is JVM-testable against a DAO fake;
     * this query only narrows the scan to retained states.
     */
    @Query("SELECT id, conversation_id AS conversationId, updated_at AS updatedAt FROM task_runs WHERE latest_state IN (:states)")
    suspend fun listRetainable(states: Set<String>): List<TaskRunRetentionRow>

    /** Delete the rows the retention sweep selected. No-op on an empty list. */
    @Query("DELETE FROM task_runs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /** Cascade with conversation cleanup (retention, M6). */
    @Query("DELETE FROM task_runs WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}

/** Minimal projection the retention sweep reads: which conversation a retained row belongs to and how recent it is. */
data class TaskRunRetentionRow(
    val id: String,
    val conversationId: String,
    val updatedAt: Long,
)
