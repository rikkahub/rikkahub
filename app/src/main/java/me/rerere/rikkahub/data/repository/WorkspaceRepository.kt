package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import me.rerere.rikkahub.data.sync.cloud.CloudWorkspaceDto
import me.rerere.rikkahub.data.sync.cloud.WorkspaceCreateRequest
import me.rerere.rikkahub.data.sync.cloud.WorkspaceExecuteRequest
import me.rerere.rikkahub.data.sync.cloud.WorkspaceFileEntryDto
import me.rerere.rikkahub.data.sync.cloud.WorkspaceMoveRequest
import me.rerere.rikkahub.data.sync.cloud.WorkspaceUpdateRequest
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val settingsStore: SettingsStore,
    private val cloudSyncRepository: CloudSyncRepository,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow().onStart {
        runCatching { refreshRemote() }
            .onFailure { Log.w(TAG, "cloud workspace refresh failed", it) }
    }

    suspend fun checkIntegrity() {
        runCatching { refreshRemote() }
            .onFailure { Log.w(TAG, "initial cloud workspace refresh failed", it) }
    }

    suspend fun refreshRemote(): List<WorkspaceEntity> {
        val entities = client().listWorkspaces().items.map { it.toEntity() }
        dao.replaceAll(entities)
        return entities
    }

    suspend fun getById(id: String): WorkspaceEntity? {
        val local = dao.getById(id)
        return runCatching {
            client().getWorkspace(id).toEntity().also { dao.upsert(it) }
        }.getOrElse { local }
    }

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val finalName = name.trim().ifBlank { "Workspace" }
        val entity = client().createWorkspace(
            WorkspaceCreateRequest(id = id, name = finalName)
        ).toEntity()
        dao.upsert(entity)
        return entity
    }

    suspend fun rename(id: String, name: String): Boolean {
        val current = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { current.name }
        val entity = client().updateWorkspace(
            id,
            WorkspaceUpdateRequest(name = finalName),
        ).toEntity()
        dao.upsert(entity)
        return true
    }

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        val workspaces = runCatching { refreshRemote() }.getOrElse { dao.getAll() }
        return workspaces.any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        val updated = client().updateWorkspace(
            id,
            WorkspaceUpdateRequest(toolApprovals = overrides),
        ).toEntity()
        dao.upsert(updated)
        return true
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = client()
        .listWorkspaceFiles(id, absolutePath(area, path))
        .items
        .map { it.toEntry(area) }

    suspend fun readText(id: String, path: String): String =
        readBytes(id, WorkspaceStorageArea.FILES, path)
            .toString(Charsets.UTF_8)

    suspend fun readBytes(id: String, area: WorkspaceStorageArea, path: String): ByteArray =
        client().getWorkspaceFileContent(id, absolutePath(area, path))

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = client().putWorkspaceFileContent(
        id,
        absolutePath(WorkspaceStorageArea.FILES, path),
        text.toByteArray(Charsets.UTF_8),
        overwrite,
    ).toEntry(WorkspaceStorageArea.FILES)

    suspend fun writeBytes(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
    ): WorkspaceFileEntry = client().putWorkspaceFileContent(
        id,
        absolutePath(area, path),
        bytes,
        overwrite,
    ).toEntry(area)

    suspend fun createDirectory(id: String, area: WorkspaceStorageArea, path: String) {
        val absolute = absolutePath(area, path)
        val result = executeCommand(id, "mkdir -- ${shellQuote(absolute)}")
        check(result.exitCode == 0) { result.stderr.ifBlank { "Failed to create directory" } }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val path = listOf(destinationPath.trim('/'), fileName)
            .filter(String::isNotBlank)
            .joinToString("/")
        return client().putWorkspaceFileContent(
            id,
            absolutePath(area, path),
            inputStream.use { it.readBytes() },
            overwrite = true,
        ).toEntry(area)
    }

    suspend fun fileSize(id: String, area: WorkspaceStorageArea, path: String): Long =
        client().statWorkspaceFile(id, absolutePath(area, path)).sizeBytes

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) {
        val bytes = client().getWorkspaceFileContent(id, absolutePath(area, path))
        outputStream.use { it.write(bytes) }
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = client().deleteWorkspaceFile(
        id,
        absolutePath(area, path),
        recursive,
    ).status == "deleted"

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = client().moveWorkspaceFile(
        id,
        WorkspaceMoveRequest(
            source = absolutePath(WorkspaceStorageArea.FILES, source),
            target = absolutePath(WorkspaceStorageArea.FILES, target),
            overwrite = overwrite,
        ),
    ).toEntry(WorkspaceStorageArea.FILES)

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = 30_000L,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val result = client().executeWorkspaceCommand(
            id,
            WorkspaceExecuteRequest.create(command, cwd, timeoutMillis, stdin),
        )
        return WorkspaceCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            truncated = result.truncated,
        )
    }

    suspend fun delete(id: String): Boolean {
        if (dao.getById(id) == null) return false
        client().deleteWorkspace(id)
        dao.deleteById(id)
        cleanupAssistantReferences(id)
        return true
    }

    private fun client() = cloudSyncRepository.createAuthenticatedClient()
        ?: error("Perry cloud workspace is not configured")

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private fun CloudWorkspaceDto.toEntity(): WorkspaceEntity = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        shellStatus = shellStatus,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs,
        lastAccessAt = lastAccessAtMs,
        toolApprovals = JsonInstant.encodeToString(toolApprovals),
    )

    private fun WorkspaceFileEntryDto.toEntry(area: WorkspaceStorageArea): WorkspaceFileEntry =
        WorkspaceFileEntry(
            path = relativePath(area, path),
            name = name,
            isDirectory = isDirectory,
            sizeBytes = sizeBytes,
            updatedAt = updatedAtMs,
        )

    private fun absolutePath(area: WorkspaceStorageArea, path: String): String {
        val relative = path.trim().trim('/')
        return when (area) {
            WorkspaceStorageArea.FILES -> if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
            WorkspaceStorageArea.LINUX -> if (relative.isEmpty()) "/" else "/$relative"
        }
    }

    private fun relativePath(area: WorkspaceStorageArea, path: String): String = when (area) {
        WorkspaceStorageArea.FILES -> path.removePrefix("/workspace").trimStart('/')
        WorkspaceStorageArea.LINUX -> path.trimStart('/')
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        private const val TAG = "WorkspaceRepository"
    }
}
