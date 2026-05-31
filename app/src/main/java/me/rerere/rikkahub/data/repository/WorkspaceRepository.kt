package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val workspace = WorkspaceEntity(
            id = id,
            name = name.ifBlank { "Workspace" },
            root = id,
            shellEnabled = false,
            shellStatus = WorkspaceShellStatus.DISABLED.name,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
            sizeBytes = 0,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.upsert(
            workspace.copy(
                name = name.ifBlank { workspace.name },
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun setShellEnabled(id: String, enabled: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        manager.ensureWorkspace(workspace.root)
        dao.upsert(
            workspace.copy(
                shellEnabled = enabled,
                shellStatus = if (enabled) {
                    if (manager.hasRootfs(workspace.root)) {
                        WorkspaceShellStatus.READY.name
                    } else {
                        WorkspaceShellStatus.BROKEN.name
                    }
                } else {
                    WorkspaceShellStatus.DISABLED.name
                },
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.upsert(
            workspace.copy(
                shellEnabled = true,
                shellStatus = WorkspaceShellStatus.INSTALLING.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return runCatching {
            withContext(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
        }.fold(
            onSuccess = {
                val size = manager.calculateSize(workspace.root)
                dao.upsert(
                    workspace.copy(
                        shellEnabled = true,
                        shellStatus = WorkspaceShellStatus.READY.name,
                        sizeBytes = size,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true
            },
            onFailure = {
                Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", it)
                dao.upsert(
                    workspace.copy(
                        shellEnabled = true,
                        shellStatus = WorkspaceShellStatus.BROKEN.name,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                throw it
            },
        )
    }

    suspend fun refreshSize(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.upsert(
            workspace.copy(
                sizeBytes = manager.calculateSize(workspace.root),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        if (deleted) refreshSize(id)
        return deleted
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.executeCommand(workspace.root, command, cwd)
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        manager.deleteWorkspace(workspace.root)
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == id) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
        return true
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
    }
}
