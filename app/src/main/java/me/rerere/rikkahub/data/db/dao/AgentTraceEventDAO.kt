package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent

@Dao
interface AgentTraceEventDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AgentTraceEvent)

    @Query("SELECT * FROM agent_trace_events WHERE run_id = :runId ORDER BY sequence ASC")
    fun observeForRun(runId: String): Flow<List<AgentTraceEvent>>

    @Query("SELECT * FROM agent_trace_events WHERE run_id = :runId ORDER BY sequence ASC")
    suspend fun getForRun(runId: String): List<AgentTraceEvent>

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM agent_trace_events WHERE run_id = :runId")
    suspend fun nextSequence(runId: String): Int

    @Query(
        "DELETE FROM agent_trace_events WHERE run_id = :runId AND sequence BETWEEN :firstSequence AND :lastSequence " +
            "AND type NOT IN ('RUN_STARTED', 'RUN_FINISHED')",
    )
    suspend fun deleteRunEventsInRangeExceptAnchors(runId: String, firstSequence: Int, lastSequence: Int): Int

    @Query(
        "DELETE FROM agent_trace_events WHERE created_at < :beforeMillis " +
            "AND type NOT IN ('RUN_STARTED', 'RUN_FINISHED', 'TRACE_TRUNCATED')",
    )
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    @Query(
        "DELETE FROM agent_trace_events WHERE id IN (" +
            "SELECT id FROM agent_trace_events WHERE type NOT IN ('RUN_STARTED', 'RUN_FINISHED', 'TRACE_TRUNCATED') " +
            "ORDER BY created_at ASC, id ASC LIMIT " +
            "(SELECT CASE WHEN COUNT(*) > :keepCount THEN COUNT(*) - :keepCount ELSE 0 END FROM agent_trace_events)" +
            ")",
    )
    suspend fun trimToTotal(keepCount: Int): Int
}
