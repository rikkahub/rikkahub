package me.rerere.rikkahub.data.agentrun

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 24 — DAO for the unified `agent_runs` ledger.
 *
 * Writes are funnelled through [AgentRunRepository], which serialises them through a
 * Mutex so concurrent runs across the five paths don't last-writer-win each other's
 * status fields. The DAO itself stays a thin typed query surface.
 */
@Dao
interface AgentRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: AgentRun)

    @Update
    suspend fun update(row: AgentRun)

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentRun?

    /**
     * Rows that were still in flight (`queued` / `awaiting_approval` / `running`) and have
     * not been touched since [beforeMs]. Used by [AgentRunBootRecovery].
     */
    @Query(
        "SELECT * FROM agent_runs " +
            "WHERE status IN ('queued', 'awaiting_approval', 'running') " +
            "AND updated_at_ms < :beforeMs"
    )
    suspend fun findStranded(beforeMs: Long): List<AgentRun>

    /** All currently in-flight rows, regardless of age. */
    @Query(
        "SELECT * FROM agent_runs " +
            "WHERE status IN ('queued', 'awaiting_approval', 'running') " +
            "ORDER BY updated_at_ms DESC"
    )
    suspend fun findInFlight(): List<AgentRun>

    @Query("SELECT * FROM agent_runs ORDER BY updated_at_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AgentRun>>

    @Query("SELECT * FROM agent_runs ORDER BY updated_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentRun>

    @Query(
        "SELECT * FROM agent_runs WHERE kind = :kind AND domain_id = :domainId " +
            "ORDER BY updated_at_ms DESC LIMIT :limit"
    )
    suspend fun getByDomainId(kind: String, domainId: String, limit: Int): List<AgentRun>

    @Query("SELECT * FROM agent_runs WHERE parent_run_id = :parentRunId ORDER BY created_at_ms ASC")
    suspend fun getChildren(parentRunId: String): List<AgentRun>

    @Query("SELECT COUNT(*) FROM agent_runs")
    suspend fun count(): Int

    /**
     * Count of recent rows in each terminal/in-flight status since [sinceMs] — used by the
     * Doctor `db.agent_runs` row.
     */
    @Query(
        "SELECT COUNT(*) FROM agent_runs WHERE status = :status AND updated_at_ms >= :sinceMs"
    )
    suspend fun countByStatusSince(status: String, sinceMs: Long): Int

    /**
     * FIFO eviction: delete the oldest TERMINAL rows so the table holds at most [cap] rows.
     * In-flight rows are never evicted — only `succeeded` / `failed` / `cancelled` /
     * `process_lost` rows are candidates, oldest `updated_at_ms` first.
     */
    @Query(
        "DELETE FROM agent_runs WHERE id IN (" +
            "SELECT id FROM agent_runs " +
            "WHERE status IN ('succeeded', 'failed', 'cancelled', 'process_lost') " +
            "ORDER BY updated_at_ms ASC " +
            "LIMIT MAX(0, (SELECT COUNT(*) FROM agent_runs) - :cap)" +
            ")"
    )
    suspend fun purgeOldest(cap: Int)
}
