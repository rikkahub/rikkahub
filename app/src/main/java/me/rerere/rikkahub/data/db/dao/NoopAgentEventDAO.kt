package me.rerere.rikkahub.data.db.dao

import me.rerere.rikkahub.data.db.entity.AgentEventEntity

/**
 * An inert [AgentEventDAO] — every method is a no-op / empty result. It is the default sink for
 * [me.rerere.rikkahub.data.repository.TaskRunRepository] so the many callers that never spawn a
 * BACKGROUND run (and every existing unit test) need not wire a real queue DAO: a foreground/
 * synchronous run never enqueues a completion, so dropping enqueues is semantically correct for them.
 *
 * Production MUST inject the real Room DAO (DataSourceModule wires `agentEventDao = get()`), otherwise
 * background subagent completions would silently never be delivered — mirrors the `NoopTaskRunStore`
 * default precedent in this codebase.
 */
object NoopAgentEventDAO : AgentEventDAO {
    override suspend fun insertIgnore(event: AgentEventEntity) {}
    override suspend fun getById(id: String): AgentEventEntity? = null
    override suspend fun oldestPending(conversationId: String): AgentEventEntity? = null
    override suspend fun listPending(conversationId: String): List<AgentEventEntity> = emptyList()
    override suspend fun conversationsWithPending(): List<String> = emptyList()
    override suspend fun markConsumed(
        id: String,
        syntheticNodeId: String,
        syntheticMessageId: String,
        consumedAt: Long,
    ): Int = 0

    override suspend fun markCancelled(id: String, cancelledAt: Long): Int = 0
    override suspend fun markFailed(id: String, cancelledAt: Long): Int = 0
    override suspend fun nextEnqueueSeq(conversationId: String): Long = 1
    override suspend fun deleteByConversationId(conversationId: String): Int = 0
}
