package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.ScheduledJobDao
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity

class ScheduledJobRepository(private val dao: ScheduledJobDao) {
    suspend fun getAll(): List<ScheduledJobEntity> = dao.getAll()
    suspend fun getById(id: String): ScheduledJobEntity? = dao.getById(id)
    suspend fun getEnabled(): List<ScheduledJobEntity> = dao.getEnabled()
    suspend fun upsert(job: ScheduledJobEntity) = dao.upsert(job)
    suspend fun update(job: ScheduledJobEntity) = dao.update(job)
    suspend fun deleteById(id: String) = dao.deleteById(id)
    suspend fun listFiltered(tag: String?, mode: String?, enabled: Boolean?): List<ScheduledJobEntity> =
        dao.listFiltered(tag, mode, enabled)
}
