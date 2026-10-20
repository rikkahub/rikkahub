package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshShell
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val sshPool: SshConnectionPool,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            // 文件不在本机的工作区 (如远程) 没有本地目录和 Rootfs
            if (!workspace.capabilities().localFiles) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    /** 用一条临时连接测试 SSH 配置; [id] 不为空时, 表单里留空的凭据沿用该工作区已保存的值 */
    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        return runInterruptible(Dispatchers.IO) {
            sshPool.probe(draft.toSettings(previous).toConnectionConfig())
        }
    }

    /** 新建 ([id] 为 null) 或更新 SSH 工作区 */
    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("Workspace not found: $it") }
        require(existing == null || existing.isSsh()) { "Not an SSH workspace: $id" }
        val finalName = draft.name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        val settings = withContext(Dispatchers.IO) { draft.toSettings(existing?.sshSettings()) }
        val now = System.currentTimeMillis()
        val workspace = existing?.copy(
            name = finalName,
            sshConfig = JsonInstant.encodeToString(settings),
            updatedAt = now,
        ) ?: Uuid.random().toString().let { newId ->
            WorkspaceEntity(
                id = newId,
                name = finalName,
                root = newId,
                // 远程工作区不需要安装 Rootfs, 视为 shell 已就绪
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = now,
                updatedAt = now,
                type = WorkspaceType.SSH.name,
                sshConfig = JsonInstant.encodeToString(settings),
            )
        }
        dao.upsert(workspace)
        withContext(Dispatchers.IO) { sshPool.close(workspace.id) }
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
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
        require(workspace.capabilities().rootfs) { "Rootfs is not available for ${workspace.type} workspaces" }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    /** 工作区在执行环境内的目录约定, 工作区不存在时返回 null */
    suspend fun layout(id: String): WorkspaceLayout? = dao.getById(id)?.let { workspace ->
        // 只需要目录约定, 不解密凭据也不建立连接
        if (workspace.isSsh()) {
            workspace.sshSettings()?.let { SshWorkspaceBackend.layoutOf(it.remoteDir) }
        } else {
            manager.layout
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> {
        val workspace = dao.getById(id) ?: return emptyList()
        return runInterruptible(Dispatchers.IO) { backendOf(workspace).listFiles(area, path) }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = readFromBackend(id) { it.readText(path) }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withBackend(id) { it.writeText(path, text, overwrite) }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceBackend.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = readFromBackend(id) { backend ->
        when (area) {
            WorkspaceStorageArea.FILES -> backend.readText(path)
            WorkspaceStorageArea.LINUX -> {
                val size = backend.fileSize(area, path)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 ($size bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    backend.exportFile(area, path, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withBackend(id) { it.importFile(area, destinationPath, fileName, inputStream) }

    /** 宿主机上可直接访问的文件; 没有本地落点的工作区返回 null */
    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): File? = withBackend(id) { it.localFile(area, path) }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportFile(area, path, outputStream) }

    /** 按环境内绝对路径读取文件大小, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun absoluteFileSize(
        id: String,
        path: String,
    ): Long = readFromBackend(id) { it.absoluteFileSize(path) }

    /** 按环境内绝对路径解析宿主机上的文件, 不存在、不是文件或没有本地落点时返回 null */
    suspend fun resolveAbsoluteFile(
        id: String,
        path: String,
    ): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        runCatching { backendOf(workspace).localAbsoluteFile(path) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    /** 执行命令实际使用的 shell 路径 */
    suspend fun shell(id: String): String = readFromBackend(id) { it.shell() }

    /** 按环境内绝对路径写入文本; 本地工作区在 Rootfs 内执行 shell 写入, 因此和命令一样支持取消 */
    suspend fun writeAbsoluteText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).writeAbsoluteText(path, text, overwrite)
        }
    }

    /** 按环境内绝对路径导出文件内容, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportAbsoluteFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportAbsoluteFile(path, outputStream) }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        backendOf(workspace).deleteFile(area, path, recursive)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).executeCommand(command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            sshPool.close(id)
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

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

    /** 打开 SSH 工作区的交互式 shell, 阻塞到连接和 shell 都建立好, 不能在主线程调用 */
    fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int): SshShell {
        val backend = backendOf(workspace) as? SshWorkspaceBackend
            ?: error("Not an SSH workspace: ${workspace.id}")
        return backend.openShell(columns, rows)
    }

    private fun backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, settings.toConnectionConfig())
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

    private fun SshWorkspaceSettings.toConnectionConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = encryptedPassword?.let(::decryptSecret),
        privateKey = encryptedPrivateKey?.let(::decryptSecret),
        passphrase = encryptedPassphrase?.let(::decryptSecret),
        remoteDir = remoteDir,
        hostKey = hostKey,
    )

    /**
     * 表单转成持久化配置并加密凭据。编辑时留空的凭据沿用 [previous] 的密文;
     * 私钥口令跟随私钥: 换了新私钥就以表单为准, 沿用旧私钥时口令留空也沿用。
     */
    private fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
        val remoteDir = remoteDir.trim().trimEnd('/').ifEmpty { "/" }
        require(remoteDir.startsWith("/")) { "Remote directory must be an absolute path" }
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(port in 1..65535) { "Invalid port: $port" }
        val keepPrivateKey = privateKey.isBlank()
        return SshWorkspaceSettings(
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            remoteDir = remoteDir,
            hostKey = hostKey,
            encryptedPassword = if (authType == SshAuthType.PASSWORD) {
                password.takeIf { it.isNotEmpty() }?.let(SecretCipher::encrypt) ?: previous?.encryptedPassword
            } else {
                null
            },
            encryptedPrivateKey = if (authType == SshAuthType.PRIVATE_KEY) {
                privateKey.takeIf { it.isNotBlank() }?.let { SecretCipher.encrypt(it.trim()) }
                    ?: previous?.encryptedPrivateKey
            } else {
                null
            },
            encryptedPassphrase = when {
                authType != SshAuthType.PRIVATE_KEY -> null
                passphrase.isNotEmpty() -> SecretCipher.encrypt(passphrase)
                keepPrivateKey -> previous?.encryptedPassphrase
                else -> null
            },
        )
    }

    private fun decryptSecret(value: String): String = try {
        SecretCipher.decrypt(value)
    } catch (e: Exception) {
        throw IOException(
            "Saved SSH credentials cannot be decrypted (for example after restoring a backup on another device). " +
                "Re-enter them in the workspace settings.",
            e,
        )
    }

    /**
     * 读操作: 取消时中断线程, 远程读取卡在网络上时也能及时返回。
     * 中途放弃没有副作用; 写操作被打断会留下写了一半的文件, 走 [withBackend] 照常执行完。
     */
    private suspend fun <T> readFromBackend(id: String, block: (WorkspaceBackend) -> T): T {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return try {
            runInterruptible(Dispatchers.IO) { block(backendOf(workspace)) }
        } catch (e: Exception) {
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun <T> withBackend(id: String, block: (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            block(backendOf(workspace))
        }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
