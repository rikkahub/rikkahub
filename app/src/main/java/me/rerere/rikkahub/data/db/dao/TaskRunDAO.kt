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

    @Query("SELECT * FROM task_runs WHERE id = :id")
    fun getByIdFlow(id: String): Flow<TaskRunEntity?>

    @Query("SELECT * FROM task_runs WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun listByConversationFlow(conversationId: String): Flow<List<TaskRunEntity>>

    /** Recovery scan: rows whose persisted state is in [states] (TaskRunStateTag names). */
    @Query("SELECT * FROM task_runs WHERE latest_state IN (:states)")
    suspend fun listByStates(states: Set<String>): List<TaskRunEntity>

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
