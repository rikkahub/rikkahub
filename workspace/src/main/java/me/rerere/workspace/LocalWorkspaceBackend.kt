package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 本机上的工作区: 文件落在 [WorkspaceManager] 管理的目录下, 命令在 PRoot Rootfs 内执行 */
class LocalWorkspaceBackend(
    private val manager: WorkspaceManager,
    private val root: String,
    private val shellCompatibilityMode: Boolean = false,
) : WorkspaceBackend {
    override val layout: WorkspaceLayout
        get() = manager.layout

    override fun shell(): String = manager.rootfsShell(root)

    override fun listFiles(area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> {
        manager.ensureWorkspace(root)
        return manager.listFiles(root, path, area)
    }

    override fun readText(path: String): String {
        manager.ensureWorkspace(root)
        return manager.readText(root, path)
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        manager.ensureWorkspace(root)
        return manager.writeText(root, path, text, overwrite)
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        manager.ensureWorkspace(root)
        return manager.importFile(root, destinationPath, area, fileName, inputStream)
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long =
        manager.fileSize(root, path, area)

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) =
        manager.exportFile(root, path, area, outputStream)

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        manager.deleteFile(root, path, recursive, area)

    override fun absoluteFileSize(path: String): Long {
        manager.ensureWorkspace(root)
        return manager.rootfsFileSize(root, path)
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        manager.ensureWorkspace(root)
        manager.exportRootfsFile(root, path, outputStream)
    }

    override fun localFile(area: WorkspaceStorageArea, path: String): File {
        manager.ensureWorkspace(root)
        return manager.resolveFile(root, path, area)
    }

    override fun localAbsoluteFile(path: String): File = manager.resolveRootfsFile(root, path)

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        manager.ensureWorkspace(root)
        return manager.executeCommand(
            root = root,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            shellCompatibilityMode = shellCompatibilityMode,
        )
    }
}
