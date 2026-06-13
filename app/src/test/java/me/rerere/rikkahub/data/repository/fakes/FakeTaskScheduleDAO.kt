package me.rerere.rikkahub.data.repository.fakes

import me.rerere.rikkahub.data.db.dao.TaskScheduleDAO
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity

/**
 * In-memory [TaskScheduleDAO] for JVM repository tests (SPEC.md M3 testing strategy: CI runs no
 * instrumented tests, so schedule persistence is pinned at the repository seam against a DAO fake).
 * Row semantics mirror the Room DAO: [insert] aborts on a duplicate primary key, [update] replaces
 * an existing row, the list/scan queries filter and order exactly as the `@Query` strings do, and
 * the delete queries return affected-row counts.
 *
 * A plain map guarded by a monitor — the repository serializes its own read-modify-write via
 * [me.rerere.rikkahub.data.repository.BoardTransactionRunner], so cross-call atomicity is the
 * runner's job (as it is Room's), not this fake's. Mirrors [FakeTaskRunDAO].
 */
class FakeTaskScheduleDAO : TaskScheduleDAO {
    private val lock = Any()
    private val schedules = LinkedHashMap<String, TaskScheduleEntity>()

    override suspend fun insert(schedule: TaskScheduleEntity) = synchronized(lock) {
        // OnConflictStrategy.ABORT: a duplicate primary key is a programming error, not a silent
        // replace. The repository assigns a fresh Uuid per create, so this never fires in practice;
        // surfacing it keeps the fake honest against the Room contract.
        require(schedule.id !in schedules) { "duplicate schedule id: ${schedule.id}" }
        schedules[schedule.id] = schedule
    }

    override suspend fun update(schedule: TaskScheduleEntity) = synchronized(lock) {
        schedules[schedule.id] = schedule
    }

    override suspend fun delete(schedule: TaskScheduleEntity) = synchronized(lock) {
        schedules.remove(schedule.id)
        Unit
    }

    override suspend fun getById(id: String): TaskScheduleEntity? = synchronized(lock) { schedules[id] }

    override suspend fun deleteById(id: String): Int = synchronized(lock) {
        if (schedules.remove(id) != null) 1 else 0
    }

    override suspend fun listByConversation(conversationId: String): List<TaskScheduleEntity> =
        synchronized(lock) {
            schedules.values
                .filter { it.conversationId == conversationId }
                .sortedBy { it.nextFireAt }
        }

    override suspend fun countEnabledByOwner(owner: String): Int = synchronized(lock) {
        schedules.values.count { it.owner == owner && it.enabled }
    }

    override suspend fun listOverdueEnabled(now: Long): List<TaskScheduleEntity> = synchronized(lock) {
        schedules.values
            .filter { it.enabled && it.nextFireAt <= now }
            .sortedBy { it.nextFireAt }
    }

    override suspend fun listEnabledRunning(): List<TaskScheduleEntity> = synchronized(lock) {
        schedules.values
            .filter { it.enabled && it.runningTaskRunId != null }
            .sortedBy { it.nextFireAt }
    }

    override suspend fun deleteByConversationId(conversationId: String): Int = synchronized(lock) {
        val ids = schedules.values.filter { it.conversationId == conversationId }.map { it.id }
        ids.forEach { schedules.remove(it) }
        ids.size
    }
}
