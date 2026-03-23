package me.rerere.rikkahub.data.sandbox

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.SandboxDAO
import me.rerere.rikkahub.data.db.entity.SandboxEntity
import java.io.File
import kotlin.uuid.Uuid

class SandboxManager(
    private val context: Context,
    private val dao: SandboxDAO,
) {
    private val sandboxesRoot: File
        get() = context.filesDir.resolve("sandboxes").also { it.mkdirs() }

    fun listFlow(): Flow<List<SandboxInfo>> = dao.getAllFlow().map { it.map(SandboxEntity::toInfo) }

    suspend fun getById(id: String): SandboxInfo? = dao.getById(id)?.toInfo()

    suspend fun create(name: String): SandboxInfo {
        val info = SandboxInfo(
            id = Uuid.random().toString(),
            name = name.trim(),
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(info.toEntity())
        return info
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        sandboxesRoot.resolve(id).deleteRecursively()
    }

    fun rootfsDir(id: String): File = sandboxesRoot.resolve(id).resolve("rootfs")

    fun isRootfsInstalled(id: String): Boolean {
        val rootfs = rootfsDir(id)
        return rootfs.isDirectory && rootfs.listFiles()?.isNotEmpty() == true
    }
}

private fun SandboxEntity.toInfo() = SandboxInfo(id = id, name = name, createdAt = createdAt)
private fun SandboxInfo.toEntity() = SandboxEntity(id = id, name = name, createdAt = createdAt)
