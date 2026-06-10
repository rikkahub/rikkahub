package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
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

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        // A corrupt existing blob (null) is discarded here: the user is explicitly setting one
        // tool's policy, so we start from {} and repair the column rather than merge onto a
        // fail-closed all-true map (which would silently flip every other tool to approval-required).
        val overrides = workspace.toolApprovalOverrides().orEmpty() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
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
                dao.upsert(
                    workspace.copy(
                        shellEnabled = true,
                        shellStatus = WorkspaceShellStatus.READY.name,
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

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
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
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // I-ENABLE: enforce the user's shell toggle + rootfs readiness BEFORE dispatching to
        // Dispatchers.IO or touching the manager, so a disabled/not-ready workspace can never run a
        // command. Without this the shellEnabled/shellStatus pair was display-only at this sink.
        if (!isShellRunnable(workspace.shellEnabled, workspace.shellStatus)) {
            return WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shell is not enabled for this workspace",
                timedOut = false,
            )
        }
        // runInterruptible turns coroutine cancellation into a thread interrupt, which unblocks the blocking Process.waitFor and kills the process
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis)
        }
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

/**
 * I-ENABLE (design note security-model-design:197 §4.3): a shell command may run only when the owning
 * workspace is explicitly enabled AND its rootfs is READY. Enforced at the [WorkspaceRepository.executeCommand]
 * sink — the lowest layer that still holds the [WorkspaceEntity] — independent of the tool layer, so a
 * disabled or not-ready shell can never reach the PRoot manager. Pure (no Context/SettingsStore coupling)
 * so it is unit-testable in the :app JVM unit source set.
 */
internal fun isShellRunnable(shellEnabled: Boolean, shellStatus: String): Boolean =
    shellEnabled && shellStatus == WorkspaceShellStatus.READY.name
