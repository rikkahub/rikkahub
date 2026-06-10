package me.rerere.rikkahub.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
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
    private val db: AppDatabase,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    fun getByIdFlow(id: String): Flow<WorkspaceEntity?> = dao.getByIdFlow(id)

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

    // I-ATOMIC (issue #197 slice 6a, finding #5): the read-modify-write mutators below read the row
    // then upsert a `.copy(...)` that depends on the read. With the per-tool approval toggles and
    // rename now multi-writer in the management UI, two concurrent writers could interleave the
    // read of one and the write of the other and lose an update. Each mutator wraps its read AND its
    // dependent write in a single `db.withTransaction { ... }` so concurrent writers serialize on
    // the SQLite transaction instead of clobbering each other.
    suspend fun rename(id: String, name: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                name = name.ifBlank { workspace.name },
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun setShellEnabled(id: String, enabled: Boolean): Boolean {
        // hasRootfs / ensureWorkspace touch the PRoot manager (filesystem IO), kept OUTSIDE the
        // transaction; only the row read + status write are atomic.
        val root = (dao.getById(id) ?: return false).root
        manager.ensureWorkspace(root)
        val status = if (enabled) {
            if (manager.hasRootfs(root)) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.BROKEN.name
        } else {
            WorkspaceShellStatus.DISABLED.name
        }
        return db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    shellEnabled = enabled,
                    shellStatus = status,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    toolApprovals = mergeToolApprovalOverride(workspace.toolApprovals, toolName, needsApproval),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val flipped = db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    shellEnabled = true,
                    shellStatus = WorkspaceShellStatus.INSTALLING.name,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }
        if (!flipped) return false
        return runCatching {
            withContext(Dispatchers.IO) {
                rootfsInstaller.install(dao.getById(id)?.root ?: error("Workspace not found: $id"), url, onProgress)
            }
        }.fold(
            onSuccess = {
                applyShellStatus(id, WorkspaceShellStatus.READY.name)
                true
            },
            onFailure = {
                Log.e(TAG, "installRootfs failed: workspace=$id, url=$url", it)
                applyShellStatus(id, WorkspaceShellStatus.BROKEN.name)
                throw it
            },
        )
    }

    private suspend fun applyShellStatus(id: String, status: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                shellEnabled = true,
                shellStatus = status,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
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

/**
 * Merge one tool's approval policy onto the row's existing `tool_approvals` blob, returning the
 * re-encoded JSON. Pure so the lost-update invariant the [WorkspaceRepository.setToolApproval]
 * transaction protects is unit-testable in the :app JVM source set (see WorkspaceToolApprovalMergeTest).
 *
 * I-FAILCLOSED (#197 slice 6a review): a corrupt existing blob (un-decodable) is the SAME
 * security-relevant state the tool consumer ([resolveWorkspaceToolApproval]) treats as fail-CLOSED —
 * `toolApprovalOverrides()` returns null there, forcing approval for every tool. The write path must
 * agree: starting from {} would relax every UNSET tool to its (possibly no-approval) default, silently
 * downgrading the consumer's fail-closed state the moment the user toggles a single switch. So a
 * corrupt blob is repaired to a FAIL-CLOSED baseline — every known tool set to approval-required — and
 * the user's selected override is then applied on top. A well-formed blob (including an explicit {})
 * is merged as-is, preserving every already-set key (the property a stale, non-transactional read
 * would break).
 */
internal fun mergeToolApprovalOverride(
    existingJson: String,
    toolName: String,
    needsApproval: Boolean,
): String {
    val decoded = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(existingJson)
    }.getOrNull()
    // decoded == null distinguishes a corrupt blob from an explicitly-empty {} map: corrupt repairs to
    // the fail-closed baseline (never {}), so unset tools stay approval-required after the merge.
    val base = decoded ?: WorkspaceToolDefaultApprovals.mapValues { true }
    return JsonInstant.encodeToString(base + (toolName to needsApproval))
}
