package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.sync.cloud.FileDomainSync

class FilesRepository(
    private val dao: ManagedFileDAO,
) {
    @Volatile
    var fileDomainSync: FileDomainSync? = null

    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        dao.insert(file)
        fileDomainSync?.onLocalUpsert(file)
        return file
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
        fileDomainSync?.onLocalUpsert(file)
    }

    suspend fun getById(id: String): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    suspend fun getAll(): List<ManagedFileEntity> = dao.getAll()

    suspend fun listByStatuses(statuses: List<String>, limit: Int): List<ManagedFileEntity> =
        dao.listByStatuses(statuses, limit)

    suspend fun deleteById(id: String): Int {
        val n = dao.deleteById(id)
        if (n > 0) {
            fileDomainSync?.onLocalDelete(id)
        }
        return n
    }

    suspend fun deleteByPath(relativePath: String): Int {
        val existing = dao.getByPath(relativePath)
        val n = dao.deleteByPath(relativePath)
        if (n > 0 && existing != null) {
            fileDomainSync?.onLocalDelete(existing.id)
        }
        return n
    }

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder)

    /** Insert/update without enqueueing cloud mutations (remote apply / upload worker). */
    suspend fun upsertQuiet(file: ManagedFileEntity) {
        dao.insert(file)
    }
}
