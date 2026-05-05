package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.ScheduledJobRunDao
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

class ScheduledJobRunRepository(private val dao: ScheduledJobRunDao) {
    suspend fun getRecent(jobId: String, limit: Int) = dao.getRecent(jobId, limit)
    suspend fun getStranded(stalenessMs: Long) = dao.getStranded(stalenessMs)
    suspend fun insert(row: ScheduledJobRunEntity) = dao.insert(row)
    suspend fun update(row: ScheduledJobRunEntity) = dao.update(row)
    suspend fun trim(jobId: String, keep: Int = 100) = dao.trim(jobId, keep)
    suspend fun deleteAllForJob(jobId: String) = dao.deleteAllForJob(jobId)
}
