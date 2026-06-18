package me.rerere.rikkahub.data.repository.fakes

import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus

/**
 * In-memory [AgentEventDAO] for JVM tests (issue #290; CI runs no instrumented tests, so the queue
 * is pinned at the store seam against a DAO fake). Row semantics mirror the Room DAO exactly so the
 * property suite exercises the store's own logic, not SQLite's:
 *  - [insertIgnore] is IGNORE-on-conflict for BOTH the primary key AND the UNIQUE `dedupe_key`;
 *  - [oldestPending] returns the smallest-`enqueueSeq` PENDING row (FIFO);
 *  - [markConsumed] is the conditional single-claim: it flips a row only when it is still PENDING
 *    and returns the affected-row count (1 = this caller won, 0 = already consumed/missing).
 *
 * A plain map guarded by a monitor — cross-call atomicity is the transaction runner's job (as it is
 * Room's), not this fake's.
 */
class FakeAgentEventDAO : AgentEventDAO {
    private val lock = Any()
    private val events = LinkedHashMap<String, AgentEventEntity>()

    override suspend fun insertIgnore(event: AgentEventEntity) {
        synchronized(lock) {
            // IGNORE on primary-key conflict OR UNIQUE dedupe_key conflict (the Room constraints).
            if (events.containsKey(event.id)) return
            if (events.values.any { it.dedupeKey == event.dedupeKey }) return
            events[event.id] = event
        }
    }

    override suspend fun getById(id: String): AgentEventEntity? = synchronized(lock) { events[id] }

    override suspend fun oldestPending(conversationId: String): AgentEventEntity? =
        synchronized(lock) {
            events.values
                .filter { it.conversationId == conversationId && it.status == AgentEventStatus.PENDING.name }
                .minByOrNull { it.enqueueSeq }
        }

    override suspend fun listPending(conversationId: String): List<AgentEventEntity> =
        synchronized(lock) {
            events.values
                .filter { it.conversationId == conversationId && it.status == AgentEventStatus.PENDING.name }
                .sortedBy { it.enqueueSeq }
        }

    override suspend fun conversationsWithPending(): List<String> = synchronized(lock) {
        events.values
            .filter { it.status == AgentEventStatus.PENDING.name }
            .map { it.conversationId }
            .distinct()
    }

    override suspend fun markConsumed(
        id: String,
        syntheticNodeId: String,
        syntheticMessageId: String,
        consumedAt: Long,
    ): Int = synchronized(lock) {
        val existing = events[id] ?: return 0
        // The conditional claim: only a still-PENDING row flips. A row already CONSUMED/CANCELLED
        // matches the WHERE's `status = 'PENDING'` clause for zero rows.
        if (existing.status != AgentEventStatus.PENDING.name) return 0
        events[id] = existing.copy(
            status = AgentEventStatus.CONSUMED.name,
            syntheticNodeId = syntheticNodeId,
            syntheticMessageId = syntheticMessageId,
            consumedAt = consumedAt,
            cancelledAt = null,
        )
        1
    }

    override suspend fun markCancelled(
        id: String,
        cancelledAt: Long,
    ): Int = markTerminal(id, AgentEventStatus.CANCELLED.name, cancelledAt)

    override suspend fun markFailed(
        id: String,
        cancelledAt: Long,
    ): Int = markTerminal(id, AgentEventStatus.FAILED.name, cancelledAt)

    private fun markTerminal(id: String, status: String, cancelledAt: Long): Int {
        val existing = events[id] ?: return 0
        if (existing.status != AgentEventStatus.PENDING.name) return 0
        events[id] = existing.copy(
            status = status,
            syntheticNodeId = null,
            syntheticMessageId = null,
            cancelledAt = cancelledAt,
            consumedAt = null,
        )
        return 1
    }

    override suspend fun nextEnqueueSeq(conversationId: String): Long = synchronized(lock) {
        (events.values.filter { it.conversationId == conversationId }.maxOfOrNull { it.enqueueSeq } ?: 0L) + 1L
    }

    override suspend fun deleteByConversationId(conversationId: String): Int = synchronized(lock) {
        val ids = events.values.filter { it.conversationId == conversationId }.map { it.id }
        ids.forEach { events.remove(it) }
        ids.size
    }

    fun snapshot(): StateSnapshot = synchronized(lock) { StateSnapshot(events.toMap()) }

    fun restore(snapshot: StateSnapshot) = synchronized(lock) {
        events.clear()
        events.putAll(snapshot.events)
    }

    data class StateSnapshot(val events: Map<String, AgentEventEntity>)
}
