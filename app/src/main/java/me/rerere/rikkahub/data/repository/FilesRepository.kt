package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

class FilesRepository(
    private val dao: ManagedFileDAO,
) {
    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        val id = dao.insert(file)
        return file.copy(id = id)
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    suspend fun listByFolder(folder: String): List<ManagedFileEntity> = dao.listByFolder(folder)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)
}
