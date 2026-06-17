package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted agent-event (issue #290 design proposal): the durable async-injection queue a
 * `ChatService`-owned drain delivers into a conversation at an idle turn-end seam. A row carries
 * everything needed to deliver the event once and to recognise a redelivery as a no-op — it never
 * holds a live coroutine handle, mirroring [TaskRunEntity]'s SUMMARY-ONLY posture.
 *
 * Lifecycle (the proposal's state machine): `PENDING -> CONSUMED` on a successful single-claim
 * drain, or `PENDING -> CANCELLED` when the conversation is torn down. The visible synthetic
 * message is appended only at drain time ([syntheticNodeId]/[syntheticMessageId] filled then), NOT
 * at enqueue — appending mid-turn would corrupt `continueAfterStopHookIfRequested`'s
 * `lastOrNull()` branch (design correction #1).
 *
 * AT_MOST_ONCE rests on two columns: [dedupeKey] (UNIQUE, so a duplicate enqueue is an
 * insert-ignore no-op) and [status] (only one drain may flip `PENDING -> CONSUMED`, enforced by a
 * conditional `WHERE status = 'PENDING'` in [me.rerere.rikkahub.data.db.dao.AgentEventDAO]).
 *
 * No Room `@ForeignKey` to the conversation in v1 (productDecision #6): `ConversationEntity` has
 * no explicit `tableName` and is persisted through a separate path, so a hard cross-seam FK is
 * riskier than the proposal's intent. Cleanup is the explicit
 * [me.rerere.rikkahub.data.db.dao.AgentEventDAO.deleteByConversationId] hook instead — strictly
 * more conservative than coupling the schema to a generated table name.
 */
@Entity(
    tableName = "agent_events",
    indices = [
        // FIFO oldest-pending claim per conversation: filter on (conversation_id, status) and order
        // by enqueue_seq, all from one covering index.
        Index(value = ["conversation_id", "status", "enqueue_seq"]),
        // The cold-start replay scan is conversation-agnostic — `SELECT DISTINCT conversation_id
        // WHERE status = 'PENDING'`. The covering index above leads with conversation_id, so SQLite
        // cannot use it for a status-only predicate and walks the whole table as the queue grows.
        // A status-leading index makes that scan index-covered.
        Index(value = ["status", "conversation_id"]),
        // A duplicate enqueue is a no-op (insert-ignore), so the dedupe key is UNIQUE.
        Index(value = ["dedupe_key"], unique = true),
    ],
)
data class AgentEventEntity(
    /** Stable event id — survives process death and is the delivery-log key in AT_MOST_ONCE tests. */
    @PrimaryKey
    val id: String,
    /** The conversation this event is delivered into. */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** Idempotency key: a duplicate enqueue with the same key is ignored (UNIQUE index). */
    @ColumnInfo("dedupe_key")
    val dedupeKey: String,
    /** Stable per-conversation FIFO cursor; the oldest PENDING (smallest seq) drains first. */
    @ColumnInfo("enqueue_seq")
    val enqueueSeq: Long,
    /** Caller-defined event kind (e.g. a background-shell completion). Opaque to the queue. */
    val kind: String,
    /** Caller-defined JSON payload. Opaque to the queue; the drain only renders it as text. */
    @ColumnInfo("payload_json")
    val payloadJson: String,
    /** Persisted name of an [AgentEventStatus] — the drain/replay scans filter on this column. */
    val status: String,
    /** The synthetic message node id, filled when the event is drained (null while PENDING). */
    @ColumnInfo("synthetic_node_id")
    val syntheticNodeId: String? = null,
    /** The synthetic message id, filled when the event is drained (null while PENDING). */
    @ColumnInfo("synthetic_message_id")
    val syntheticMessageId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("consumed_at")
    val consumedAt: Long? = null,
    @ColumnInfo("cancelled_at")
    val cancelledAt: Long? = null,
)

/**
 * The value domain of [AgentEventEntity.status] — one persisted tag per queue lifecycle state.
 * Persisted by [name]; renaming an entry is a data-format break and is forbidden without a
 * migration (mirroring [TaskRunStateTag]).
 */
enum class AgentEventStatus {
    /** Enqueued and awaiting an idle turn-end drain. */
    PENDING,

    /** Claimed and delivered exactly once (the synthetic message was appended). */
    CONSUMED,

    /** Abandoned without delivery (e.g. the conversation was deleted). */
    CANCELLED,
    ;

    companion object {
        fun fromPersistedOrNull(value: String): AgentEventStatus? =
            entries.firstOrNull { it.name == value }
    }
}
