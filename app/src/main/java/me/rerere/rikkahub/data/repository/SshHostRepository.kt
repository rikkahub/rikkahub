package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity

class SshHostRepository(private val dao: SshHostDao) {
    suspend fun getAll(): List<SshHostEntity> = dao.getAll()
    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)
    suspend fun upsert(host: SshHostEntity) = dao.upsert(host)
    suspend fun deleteByName(name: String) = dao.deleteByName(name)
}
