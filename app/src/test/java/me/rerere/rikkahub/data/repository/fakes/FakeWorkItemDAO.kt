package me.rerere.rikkahub.data.repository.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.WorkItemDAO
import me.rerere.rikkahub.data.db.dao.WorkItemRetentionRow
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.BoardTransactionRunner

/**
 * In-memory [WorkItemDAO] for JVM repository tests (SPEC.md testing strategy: CI runs no
 * instrumented tests, so board invariants are pinned at the repository seam against DAO fakes).
 * Row semantics mirror the Room DAO: insert ABORTs on duplicate id, dependency insert IGNOREs
 * an existing composite key (returns -1), update/delete return affected-row counts.
 *
 * Plain maps guarded by a monitor: every repository access runs through a
 * [BoardTransactionRunner], so cross-call atomicity is the runner's job (as it is Room's),
 * not this fake's.
 */
class FakeWorkItemDAO : WorkItemDAO {
    private val lock = Any()
    private val items = LinkedHashMap<String, WorkItemEntity>()
    private val edges = LinkedHashSet<WorkItemDependencyEntity>()

    override suspend fun insert(item: WorkItemEntity) {
        synchronized(lock) {
            check(items.putIfAbsent(item.id, item) == null) { "duplicate work item id: ${item.id}" }
        }
    }

    override suspend fun update(item: WorkItemEntity): Int = synchronized(lock) {
        if (items.containsKey(item.id)) {
            items[item.id] = item
            1
        } else 0
    }

    override suspend fun getById(id: String): WorkItemEntity? = synchronized(lock) { items[id] }

    override fun listByConversationFlow(conversationId: String): Flow<List<WorkItemEntity>> =
        flow { emit(listByConversation(conversationId)) }

    override suspend fun listByConversation(conversationId: String): List<WorkItemEntity> =
        synchronized(lock) {
            items.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
        }

    override suspend fun listByOwner(ownerHandleId: String): List<WorkItemEntity> =
        synchronized(lock) { items.values.filter { it.ownerHandleId == ownerHandleId } }

    override suspend fun deleteById(id: String): Int = synchronized(lock) {
        if (items.remove(id) != null) 1 else 0
    }

    override suspend fun listRetainable(statuses: Set<String>): List<WorkItemRetentionRow> =
        synchronized(lock) {
            items.values.filter { it.status in statuses }
                .map { WorkItemRetentionRow(id = it.id, conversationId = it.conversationId, updatedAt = it.updatedAt) }
        }

    override suspend fun deleteByIds(ids: List<String>): Int = synchronized(lock) {
        ids.count { items.remove(it) != null }
    }

    override suspend fun deleteDependenciesTouchingAny(ids: List<String>): Int = synchronized(lock) {
        val idSet = ids.toSet()
        val touching = edges.filter { it.blockerId in idSet || it.blockedId in idSet }
        edges.removeAll(touching.toSet())
        touching.size
    }

    override suspend fun deleteByConversationId(conversationId: String): Int = synchronized(lock) {
        val ids = items.values.filter { it.conversationId == conversationId }.map { it.id }
        ids.forEach { items.remove(it) }
        ids.size
    }

    override suspend fun insertDependency(edge: WorkItemDependencyEntity): Long =
        synchronized(lock) { if (edges.add(edge)) 1L else -1L }

    override suspend fun listDependencies(conversationId: String): List<WorkItemDependencyEntity> =
        synchronized(lock) { edges.filter { it.conversationId == conversationId } }

    override suspend fun listBlockersOf(
        conversationId: String,
        blockedId: String,
    ): List<WorkItemDependencyEntity> = synchronized(lock) {
        edges.filter { it.conversationId == conversationId && it.blockedId == blockedId }
    }

    override suspend fun deleteDependency(
        conversationId: String,
        blockerId: String,
        blockedId: String,
    ): Int = synchronized(lock) {
        if (edges.remove(WorkItemDependencyEntity(conversationId, blockerId, blockedId))) 1 else 0
    }

    override suspend fun deleteDependenciesTouching(conversationId: String, itemId: String): Int =
        synchronized(lock) {
            val touching = edges.filter {
                it.conversationId == conversationId && (it.blockerId == itemId || it.blockedId == itemId)
            }
            edges.removeAll(touching.toSet())
            touching.size
        }

    override suspend fun deleteDependenciesByConversationId(conversationId: String): Int =
        synchronized(lock) {
            val matching = edges.filter { it.conversationId == conversationId }
            edges.removeAll(matching.toSet())
            matching.size
        }
}

/**
 * Test [BoardTransactionRunner]: a single [Mutex] serializes transactions, the same
 * one-writer-at-a-time guarantee SQLite gives `Room.withTransaction` in production. Concurrent
 * repository calls therefore interleave at transaction granularity only — exactly the property
 * the claim-atomicity tests exercise.
 */
class FakeBoardTransactions : BoardTransactionRunner {
    private val mutex = Mutex()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
}
