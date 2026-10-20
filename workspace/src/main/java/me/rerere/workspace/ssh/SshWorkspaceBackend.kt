package me.rerere.workspace.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.shellQuote
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 远程 SSH 主机上的工作区: 文件走 SFTP, 命令走 exec channel。
 *
 * 存储区映射: [WorkspaceStorageArea.FILES] 为远程工作目录, [WorkspaceStorageArea.LINUX] 为远程根目录。
 */
class SshWorkspaceBackend(
    private val pool: SshConnectionPool,
    private val id: String,
    private val config: SshConnectionConfig,
    private val workspaceConfig: WorkspaceConfig = WorkspaceConfig(),
) : WorkspaceBackend {
    override val layout = layoutOf(config.remoteDir)

    private val workspaceDir = layout.workspaceDir

    override fun shell(): String = pool.use(id, config, retry = true) { it.shell }

    override fun listFiles(area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> {
        val dir = resolve(area, path)
        return withSftp(area, retry = true) { sftp ->
            val attrs = sftp.requireStat(dir, path)
            require(attrs.isDir) { "Path is not a directory: $path" }
            sftp.ls(dir.sftpQuote())
                .filter { it.filename != "." && it.filename != ".." }
                .map { entry ->
                    val child = dir.child(entry.filename)
                    // 指向目录的软链按目录展示, 和本地文件浏览器的行为一致
                    val isDirectory = entry.attrs.isDir ||
                        entry.attrs.isLink && sftp.statOrNull(child)?.isDir == true
                    entry.attrs.toEntry(relativePath(area, child), entry.filename, isDirectory)
                }
                .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .take(workspaceConfig.maxListEntries)
        }
    }

    override fun readText(path: String): String {
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = true) { sftp ->
            val size = sftp.requireFile(file, path).size
            require(size <= workspaceConfig.maxReadBytes) { "File is too large to read: $size bytes" }
            // 不用 get 返回的流: 它关闭时要读完未到的回应, 读取被打断后会再次卡住
            ByteArrayOutputStream().also { sftp.get(file.sftpQuote(), it) }.toString(Charsets.UTF_8.name())
        }
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= workspaceConfig.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = false) { sftp ->
            sftp.writeBytes(file, bytes, overwrite, path)
                .toEntry(relativePath(WorkspaceStorageArea.FILES, file), file.fileName())
        }
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        val file = resolve(area, targetPath)
        return inputStream.use { input ->
            withSftp(area, retry = false) { sftp ->
                sftp.mkdirs(file.parentPath())
                val target = if (sftp.statOrNull(file) == null) file else sftp.resolveConflict(file)
                sftp.put(input, target.sftpQuote(), ChannelSftp.OVERWRITE)
                sftp.requireStat(target, targetPath).toEntry(relativePath(area, target), target.fileName())
            }
        }
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long {
        val file = resolve(area, path)
        return withSftp(area, retry = true) { it.requireFile(file, path).size }
    }

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) {
        val file = resolve(area, path)
        withSftp(area, retry = false) { sftp -> sftp.export(file, path, outputStream) }
    }

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val target = resolve(area, path)
        require(target != areaRoot(area)) { "Refusing to delete workspace root" }
        return withSftp(area, retry = false) { sftp ->
            // 用 lstat: 删除指向目录的软链时只删链接本身
            val attrs = try {
                sftp.lstat(target.sftpQuote())
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withSftp false
                throw e.wrap(path)
            }
            if (attrs.isDir && !attrs.isLink) {
                require(recursive) { "Directory delete requires recursive = true" }
                sftp.deleteRecursively(target)
            } else {
                sftp.rm(target.sftpQuote())
            }
            true
        }
    }

    override fun absoluteFileSize(path: String): Long {
        val file = normalizeAbsolute(path)
        return pool.use(id, config, retry = true) { connection ->
            connection.sftp { it.requireFile(file, path).size }
        }
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        val file = normalizeAbsolute(path)
        pool.use(id, config, retry = false) { connection ->
            connection.sftp { it.export(file, path, outputStream) }
        }
    }

    override fun writeAbsoluteText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val file = normalizeAbsolute(path)
        return pool.use(id, config, retry = false) { connection ->
            connection.sftp { sftp ->
                sftp.writeBytes(file, text.toByteArray(Charsets.UTF_8), overwrite, path)
                    .toEntry(file, file.fileName())
            }
        }
    }

    override fun localFile(area: WorkspaceStorageArea, path: String): File? = null

    override fun localAbsoluteFile(path: String): File? = null

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        val workingDir = normalizeAbsolute(layout.absolutePath(WorkspaceStorageArea.FILES, cwd))
        return pool.use(id, config, retry = false) { connection ->
            connection.ensureDir(workspaceDir)
            // 远程登录 shell 不一定是 POSIX shell, 统一交给探测到的 shell 执行;
            // 命令通过位置参数传入, eval "$2" 对命令文本只求值一次
            val remoteCommand = listOf(
                "env",
                "CI=true",
                "NO_COLOR=1",
                "PAGER=cat",
                connection.shell,
                "-l",
                "-c",
                "cd -- \"\$1\" && eval \"\$2\"",
                "rikkahub",
                workingDir,
                command,
            ).joinToString(" ") { it.shellQuote() }
            connection.exec(remoteCommand, timeoutMillis, stdin)
        }
    }

    /** 打开交互式终端: 在工作目录下启动用户的登录 shell */
    fun openShell(columns: Int, rows: Int): SshShell =
        pool.openShell(id, config) { connection, onClose ->
            connection.ensureDir(workspaceDir)
            // 外层交给探测到的 POSIX shell 解析, 再 exec 用户自己的登录 shell ($SHELL 缺失时退回探测结果)
            val remoteCommand = listOf(
                connection.shell,
                "-c",
                "cd -- \"\$1\"; exec \"\${SHELL:-\$0}\" -l",
                connection.shell,
                workspaceDir,
            ).joinToString(" ") { it.shellQuote() }
            connection.openShell(remoteCommand, columns, rows, onClose)
        }

    /** 文件区操作前确保远程工作目录存在, 和本地工作区自动创建目录的行为一致 */
    private fun <T> withSftp(area: WorkspaceStorageArea, retry: Boolean, block: (ChannelSftp) -> T): T =
        pool.use(id, config, retry) { connection ->
            if (area == WorkspaceStorageArea.FILES) connection.ensureDir(workspaceDir)
            connection.sftp(block)
        }

    private fun areaRoot(area: WorkspaceStorageArea): String = when (area) {
        WorkspaceStorageArea.FILES -> workspaceDir
        WorkspaceStorageArea.LINUX -> "/"
    }

    private fun resolve(area: WorkspaceStorageArea, path: String): String {
        val root = areaRoot(area)
        val relative = path.replace('\\', '/').trim().trimStart('/')
        val resolved = normalizeAbsolute(if (relative.isEmpty()) root else "$root/$relative")
        require(root == "/" || resolved == root || resolved.startsWith("$root/")) {
            "Path escapes workspace root: $path"
        }
        return resolved
    }

    private fun relativePath(area: WorkspaceStorageArea, path: String): String =
        path.removePrefix(areaRoot(area)).trimStart('/')

    companion object {
        /** 不需要连接即可得到的目录约定, 供界面展示路径使用 */
        fun layoutOf(remoteDir: String): WorkspaceLayout {
            val workspaceDir = normalizeAbsolute(remoteDir)
            return WorkspaceLayout(
                workspaceDir = workspaceDir,
                writableRoots = listOf(workspaceDir, "/tmp"),
            )
        }
    }

    private fun ChannelSftp.requireStat(path: String, displayPath: String): SftpATTRS =
        try {
            statOrNull(path)
        } catch (e: SftpException) {
            throw e.wrap(displayPath)
        } ?: throw IllegalArgumentException("Path does not exist: $displayPath")

    private fun ChannelSftp.requireFile(path: String, displayPath: String): SftpATTRS {
        val attrs = try {
            statOrNull(path)
        } catch (e: SftpException) {
            throw e.wrap(displayPath)
        } ?: throw IllegalArgumentException("File does not exist: $displayPath")
        require(attrs.isReg) { "Path is not a file: $displayPath" }
        return attrs
    }

    private fun ChannelSftp.export(path: String, displayPath: String, outputStream: OutputStream) {
        requireFile(path, displayPath)
        outputStream.use { get(path.sftpQuote(), it) }
    }

    private fun ChannelSftp.writeBytes(
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
        displayPath: String,
    ): SftpATTRS {
        val existing = statOrNull(path)
        require(existing == null || overwrite) { "File already exists: $displayPath" }
        require(existing == null || !existing.isDir) { "Path is not a file: $displayPath" }
        mkdirs(path.parentPath())
        put(ByteArrayInputStream(bytes), path.sftpQuote(), ChannelSftp.OVERWRITE)
        return requireStat(path, displayPath)
    }

    private fun ChannelSftp.resolveConflict(path: String): String {
        val parent = path.parentPath()
        val name = path.fileName()
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && stem != name) ".$it" else "" }
        var n = 1
        var candidate: String
        do {
            candidate = parent.child("$stem ($n)$ext")
            n++
        } while (statOrNull(candidate) != null)
        return candidate
    }

    private fun ChannelSftp.deleteRecursively(dir: String) {
        ls(dir.sftpQuote())
            .filter { it.filename != "." && it.filename != ".." }
            .forEach { entry ->
                val child = dir.child(entry.filename)
                if (entry.attrs.isDir && !entry.attrs.isLink) {
                    deleteRecursively(child)
                } else {
                    rm(child.sftpQuote())
                }
            }
        rmdir(dir.sftpQuote())
    }

    private fun SftpATTRS.toEntry(path: String, name: String, isDirectory: Boolean = isDir) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isReg) size else 0L,
        // SFTP v3 的 mtime 是无符号 32 位秒数
        updatedAt = (mTime.toLong() and 0xFFFFFFFFL) * 1_000L,
    )

    private fun SftpException.wrap(displayPath: String): IOException = when (id) {
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> IOException("Permission denied: $displayPath", this)
        else -> IOException("SFTP error on $displayPath: $message", this)
    }
}

/** 按字面规范化绝对路径: 去掉 . 和多余的 /, 解析 .., 不跟随软链 */
internal fun normalizeAbsolute(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    require(normalized.startsWith("/")) { "Path must be absolute: $path" }
    require(!normalized.contains('\u0000')) { "Path contains invalid character" }
    val segments = ArrayDeque<String>()
    normalized.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

private fun String.child(name: String): String = if (this == "/") "/$name" else "$this/$name"

private fun String.parentPath(): String = substringBeforeLast('/').ifEmpty { "/" }

private fun String.fileName(): String = substringAfterLast('/').ifEmpty { "/" }
