package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    /**
     * 从 URL 或本地归档安装 rootfs.
     * [source] 支持 http(s) URL (走下载, 报 DOWNLOADING 进度), 以及 file:// URL 或纯本地路径
     * (走本地读取, 报 UPLOADING 进度).
     */
    fun install(
        root: String,
        source: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(source.isNotBlank()) { "Rootfs source is required" }
        val format = ArchiveFormat.fromUrl(source)
        if (isRemoteUrl(source)) {
            installArchive(root, format, onProgress) { archive ->
                download(source, archive, onProgress)
            }
        } else {
            val localFile = resolveLocalFile(source)
            installArchive(root, format, onProgress) { archive ->
                uploadStream(
                    input = localFile.inputStream(),
                    target = archive,
                    totalBytes = localFile.length().takeIf { it > 0 },
                    onProgress = onProgress,
                )
            }
        }
    }

    /**
     * 从已打开的输入流安装 rootfs (Android SAF content:// 等无法直接拿到 File 的场景).
     * 全程报 UPLOADING 进度; [input] 由本方法负责关闭.
     */
    fun install(
        root: String,
        input: InputStream,
        format: ArchiveFormat,
        totalBytes: Long? = null,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        installArchive(root, format, onProgress) { archive ->
            uploadStream(input, archive, totalBytes, onProgress)
        }
    }

    private fun installArchive(
        root: String,
        format: ArchiveFormat,
        onProgress: (RootfsInstallProgress) -> Unit,
        fetch: (File) -> Unit,
    ) {
        manager.ensureWorkspace(root)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            fetch(archive)
            requireRootfsArchive(archive, format)
            extractTar(archive, stagingDir, format, onProgress)
            linuxDir.deleteRecursively()
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Rootfs download failed: HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    copyWithProgress(
                        input = input,
                        output = output,
                        totalBytes = totalBytes,
                        stage = RootfsInstallStage.DOWNLOADING,
                        onProgress = onProgress,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadStream(
        input: InputStream,
        target: File,
        totalBytes: Long?,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        input.use { source ->
            target.outputStream().use { output ->
                copyWithProgress(
                    input = source,
                    output = output,
                    totalBytes = totalBytes,
                    stage = RootfsInstallStage.UPLOADING,
                    onProgress = onProgress,
                )
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long?,
        stage: RootfsInstallStage,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead = 0L
        var lastReportBytes = 0L
        while (true) {
            checkInterrupted()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            bytesRead += read
            if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                lastReportBytes = bytesRead
                onProgress(
                    RootfsInstallProgress(
                        stage = stage,
                        bytesRead = bytesRead,
                        totalBytes = totalBytes,
                    )
                )
            }
        }
        if (bytesRead == 0L) {
            onProgress(
                RootfsInstallProgress(
                    stage = stage,
                    bytesRead = 0,
                    totalBytes = totalBytes,
                )
            )
        }
    }

    private fun isRemoteUrl(source: String): Boolean {
        val lower = source.trim().lowercase(Locale.US)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun resolveLocalFile(source: String): File {
        val file = when {
            // file:///path 与 file:/path (File.toURI() 的输出) 都按 file URI 解析
            source.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(source)) }
                .getOrElse { File(source.removePrefix("file://")) }

            else -> File(source)
        }
        require(file.isFile) { "Rootfs archive file not found: $source" }
        return file
    }

    /**
     * 校验归档确实是 rootfs: 需包含已知 shell 二进制 (见 [ROOTFS_SHELL_CANDIDATES]).
     * 非 rootfs 归档 (或根本不是可解压的归档) 一律以 "not a valid rootfs archive file" 拒绝.
     */
    private fun requireRootfsArchive(archive: File, format: ArchiveFormat) {
        val hasShell = try {
            archiveContainsShell(archive, format)
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Throwable) {
            // 解压/解析失败 (损坏或根本不是归档) 视为非法 rootfs, 给出统一提示
            false
        }
        require(hasShell) { "not a valid rootfs archive file" }
    }

    internal fun archiveContainsShell(
        archive: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
    ): Boolean {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var pendingName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                when (rawHeader.type) {
                    TarEntryType.LONG_NAME -> {
                        pendingName = normalizeTarPath(
                            input.readExactly(rawHeader.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        )
                        input.skipFully(rawHeader.size.paddingSize())
                    }

                    TarEntryType.PAX -> {
                        val pax = parsePax(input.readExactly(rawHeader.size).toString(Charsets.UTF_8))
                        pendingName = pax["path"]?.let { normalizeTarPath(it) }
                        input.skipFully(rawHeader.size.paddingSize())
                    }

                    // LONG_LINK 只描述链接目标, 不消费 pendingName, 保留给其后的真实条目
                    TarEntryType.LONG_LINK -> {
                        input.skipFully(rawHeader.size.paddedTarSize())
                    }

                    else -> {
                        val name = pendingName ?: rawHeader.name
                        pendingName = null
                        // 命中任一已知 shell 路径即判定为 rootfs
                        if (name in SHELL_ENTRY_NAMES) return true
                        input.skipFully(rawHeader.size.paddedTarSize())
                    }
                }
            }
        }
        return false
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                // pendingName 来自 LONG_NAME/PAX 的原始文本, 应用时统一规范化,
                // 确保下方 blank 跳过对覆盖名同样生效
                val header = rawHeader.copy(
                    name = pendingName?.let { normalizeTarPath(it) } ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                entries++
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            val rootFile = root.canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    // 返回规范化后的相对路径; "./"、"." 等指向根的条目规范化为空串, 由调用方跳过
    // (旧实现对这类条目直接抛错, 导致 tar 里带 "./" 根条目的 rootfs 解压失败)
    private fun normalizeTarPath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        // tar 条目名形式的已知 shell 路径 (无前导 /), 用于安装前校验归档确实是 rootfs
        private val SHELL_ENTRY_NAMES: Set<String> =
            ROOTFS_SHELL_CANDIDATES.map { it.trimStart('/') }.toSet()
    }
}
