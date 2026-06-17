package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.util.Locale
import java.util.zip.GZIPInputStream

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    fun install(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        // The rootfs becomes the executable filesystem an LLM-driven shell runs inside, so the
        // download channel must be authenticated: HTTPS only (no plaintext-MITM-swappable rootfs).
        require(URL(url).protocol.equals("https", ignoreCase = true)) {
            "Rootfs url must use HTTPS"
        }
        manager.ensureWorkspace(root)
        val archive = File(manager.tempDir(root), "rootfs.tar.gz")
        download(url, archive, onProgress)
        stageArchive(root, archive, onProgress)
    }

    /**
     * Extract a downloaded rootfs archive into the workspace's linux dir, patch it, and clean up.
     * Split from [install] so the extract/symlink path is unit-testable without a network download
     * (the download channel is HTTPS-gated and not loopback-testable through HttpURLConnection).
     * Deletes [archive] when done.
     */
    internal fun stageArchive(
        root: String,
        archive: File,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        manager.ensureWorkspace(root)
        val stagingDir = File(manager.tempDir(root), "rootfs-staging")
        val linuxDir = manager.linuxDir(root)
        deleteRecursivelyNoFollow(stagingDir, stagingDir)
        stagingDir.mkdirs()
        try {
            extractTarGz(archive, stagingDir, onProgress)
            deleteRecursivelyNoFollow(linuxDir, linuxDir)
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            deleteRecursivelyNoFollow(stagingDir, stagingDir)
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
            // Redirects are followed (instanceFollowRedirects), so re-assert HTTPS on the FINAL URL:
            // an https->http redirect would otherwise downgrade the rootfs fetch to a MITM-swappable
            // channel after the initial scheme check passed.
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "Rootfs download was redirected to a non-HTTPS URL"
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            // Reject an oversized declared length up front, and enforce the same cap on the actual
            // byte stream below — a server that lies about (or omits) Content-Length cannot fill the
            // device disk.
            require(totalBytes == null || totalBytes <= MAX_ROOTFS_BYTES) {
                "Rootfs archive too large: $totalBytes bytes (max $MAX_ROOTFS_BYTES)"
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesRead += read
                        require(bytesRead <= MAX_ROOTFS_BYTES) {
                            "Rootfs archive exceeds the maximum size of $MAX_ROOTFS_BYTES bytes"
                        }
                        output.write(buffer, 0, read)
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarGz(
        archive: File,
        targetDir: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            // The download cap bounds the COMPRESSED archive; gzip can expand ~100x, so a small
            // archive could still fill the disk on extraction. Cap the aggregate EXTRACTED file bytes
            // too (a decompression-bomb guard the download cap alone does not provide).
            var extractedBytes = 0L
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
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
                requireNotRoot(target, targetDir)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        extractedBytes += header.size
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                            "Rootfs expands beyond the maximum extracted size of $MAX_EXTRACTED_BYTES bytes"
                        }
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX are consumed and `continue`d above; OTHER carries data
                    // skipped by the single trailing skip below. Consuming header.size here too would
                    // skip it twice and desync the stream for the next header.
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
        require(!linkName.contains('\u0000')) { "Symlink target contains invalid character" }
        require(!File(linkName).isAbsolute) { "Symlink target must be relative: $linkName" }
        val linkTargetPath = (target.parentFile ?: root).toPath().resolve(linkName).normalize()
        val rootPath = root.canonicalFile.toPath()
        val targetPath = runCatching { linkTargetPath.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { linkTargetPath.toAbsolutePath() }
        require(targetPath == rootPath || targetPath.startsWith(rootPath)) {
            "Symlink escapes rootfs: ${target.name}"
        }
        val linkTarget = (target.parentFile ?: root).toPath().relativize(linkTargetPath).toFile()
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

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
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

    private fun normalizeTarPath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
        require(normalized.isNotBlank() && normalized != ".") { "Rootfs entry path is blank" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun deleteRecursivelyNoFollow(root: File, target: File) {
        if (!target.exists()) return
        val rootPath = root.canonicalFile.toPath()
        Files.walkFileTree(
            target.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    requirePathUnderRoot(dir, rootPath)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    requirePathUnderRoot(file, rootPath)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    exc?.let { throw it }
                    requirePathUnderRoot(dir, rootPath)
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun requirePathUnderRoot(candidate: Path, rootPath: Path) {
        val realPath = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(realPath == rootPath || realPath.startsWith(rootPath)) {
            "Rootfs path escapes target directory: $candidate"
        }
    }

    private fun requireNotRoot(resolved: File, root: File) {
        val resolvedPath = resolved.canonicalFile
        val rootPath = root.canonicalFile
        require(resolvedPath != rootPath) {
            "Rootfs entry resolves to target directory: ${resolved.name}"
        }
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

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        // Generous ceiling for a rootfs tar.gz (real ones: Alpine ~3 MB, Debian/Ubuntu ~30 MB);
        // bounds disk use against a malicious/runaway download.
        internal const val MAX_ROOTFS_BYTES = 2L * 1024 * 1024 * 1024

        // Aggregate cap on EXTRACTED file bytes (a full desktop rootfs is a few GB); guards against a
        // gzip decompression bomb whose compressed size slips under MAX_ROOTFS_BYTES.
        internal const val MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024
    }
}
