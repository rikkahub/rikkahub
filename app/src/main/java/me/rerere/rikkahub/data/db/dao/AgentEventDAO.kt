package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus

/**
 * Row-level access to the persisted agent-event queue (issue #290). Delivery policy (idle-gating,
 * the at-most-once claim/append/consume transaction, FIFO selection) lives in the store/reducer
 * layer, not in queries here — these are exactly the primitives that layer composes, no wider.
 */
@Dao
interface AgentEventDAO {
    /**
     * Insert a fresh event. IGNORE-on-conflict so a duplicate enqueue (same `dedupe_key` UNIQUE, or
     * same primary key) is a silent no-op — that is what makes [enqueue]-style retries idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: AgentEventEntity)

    @Query("SELECT * FROM agent_events WHERE id = :id")
    suspend fun getById(id: String): AgentEventEntity?

    /**
     * The oldest PENDING event for a conversation by the stable [AgentEventEntity.enqueueSeq]
     * cursor (FIFO_PER_CONVERSATION). NOT ordered by any updated/created timestamp — those are not
     * monotone under clock skew; enqueue_seq is the single source of order.
     */
    @Query(
        "SELECT * FROM agent_events WHERE conversation_id = :conversationId AND status = 'PENDING' " +
            "ORDER BY enqueue_seq ASC LIMIT 1"
    )
    suspend fun oldestPending(conversationId: String): AgentEventEntity?

    /** Every PENDING event for a conversation, oldest first — the replay/inspection scan. */
    @Query(
        "SELECT * FROM agent_events WHERE conversation_id = :conversationId AND status = 'PENDING' " +
            "ORDER BY enqueue_seq ASC"
    )
    suspend fun listPending(conversationId: String): List<AgentEventEntity>

    /** Conversations that still have at least one PENDING event — the startup replay scan. */
    @Query("SELECT DISTINCT conversation_id FROM agent_events WHERE status = 'PENDING'")
    suspend fun conversationsWithPending(): List<String>

    /**
     * The single-claim guard. Flip exactly one PENDING row to CONSUMED, stamping the synthetic
     * message ids the drain just appended. The `AND status = 'PENDING'` predicate is the claim: two
     * concurrent drains (live + startup replay) racing the SAME row serialize through the
     * transaction, and only the one that observed PENDING writes — the loser's UPDATE matches zero
     * rows. The returned affected-row count tells the caller whether THIS drain won the claim.
     */
    @Query(
        "UPDATE agent_events SET status = 'CONSUMED', synthetic_node_id = :syntheticNodeId, " +
            "synthetic_message_id = :syntheticMessageId, consumed_at = :consumedAt " +
            "WHERE id = :id AND status = 'PENDING'"
    )
    suspend fun markConsumed(
        id: String,
        syntheticNodeId: String,
        syntheticMessageId: String,
        consumedAt: Long,
    ): Int

    /** Next FIFO cursor for a conversation: one past the current max, or 1 for an empty queue. */
    @Query("SELECT COALESCE(MAX(enqueue_seq), 0) + 1 FROM agent_events WHERE conversation_id = :conversationId")
    suspend fun nextEnqueueSeq(conversationId: String): Long

    /**
     * Cleanup hook replacing the deferred FK cascade (productDecision #6). Deletes every event of a
     * conversation regardless of status; callable when a conversation is deleted. Returns the
     * affected-row count (mirrors [TaskRunDAO.deleteByConversationId]).
     */
    @Query("DELETE FROM agent_events WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}
