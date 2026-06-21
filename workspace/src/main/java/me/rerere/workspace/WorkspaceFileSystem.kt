package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun mkdir(root: File, path: String): WorkspaceFileEntry {
        require(path.isNotBlank() && path.trim().trimStart('/') != ".") { "Folder path is required" }
        val dir = resolvePath(root, path)
        require(!dir.exists()) { "Path already exists: $path" }
        require(dir.mkdirs()) { "Failed to create folder: $path" }
        return dir.toEntry(root)
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val rootFile = root.canonicalFile
        val leaf = resolveLeafNoFollow(root, path)
        val leafPath = leaf.toPath()
        if (!Files.exists(leafPath, LinkOption.NOFOLLOW_LINKS)) return false
        // Delete a symlink entry as the link itself — never follow it to (and recurse into) its
        // target, which would silently destroy a different, in-root entry the user never selected.
        if (Files.isSymbolicLink(leafPath)) {
            Files.delete(leafPath)
            return true
        }
        requireNotRoot(leaf, rootFile)
        return if (leaf.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            deleteRecursivelyNoFollow(rootFile, leaf)
            true
        } else {
            leaf.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val rootFile = root.canonicalFile
        // Resolve leaves without following them, so moving/overwriting a symlink entry acts on the link
        // itself — never on what it points at. Entries report logical paths (see relativePath), so a
        // listed `link -> real` round-tripped into move must relocate `link`, not `real`.
        val sourceFile = resolveLeafNoFollow(root, source)
        val targetFile = resolveLeafNoFollow(root, target)
        val sourcePath = sourceFile.toPath()
        require(Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) { "Source does not exist: $source" }
        val targetPath = targetFile.toPath()
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            require(overwrite) { "Target already exists: $target" }
            when {
                Files.isSymbolicLink(targetPath) -> Files.delete(targetPath)
                targetFile.isDirectory -> deleteRecursivelyNoFollow(rootFile, targetFile)
                else -> targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { (Files.isRegularFile(it) || Files.isDirectory(it)) && staysWithinRoot(it, root) }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) && staysWithinRoot(it, root) }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun requireNotRoot(resolved: File, root: File) {
        require(resolved.canonicalFile != root.canonicalFile) { "Refusing to operate on workspace root" }
    }

    private fun deleteRecursivelyNoFollow(root: File, target: File): Boolean {
        if (!target.exists()) return false
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
        return true
    }

    // True if a walked entry, with all symlinks resolved, stays within the workspace root. The
    // walk-based readers (glob/grep) surface raw filesystem entries and bypass resolvePath's per-leaf
    // containment, so without this a leaf symlink pointing outside the root would be listed or read
    // and — because entries now report logical (in-root) paths — masked as in-root content. Files.walk
    // never descends symlinked dirs, so only a leaf symlink can escape; non-symlinks are always in-root
    // and skip the extra realpath syscall.
    private fun staysWithinRoot(candidate: Path, root: File): Boolean {
        if (!Files.isSymbolicLink(candidate)) return true
        val real = runCatching { candidate.toRealPath() }.getOrNull() ?: return false
        val rootReal = root.canonicalFile.toPath()
        return real == rootReal || real.startsWith(rootReal)
    }

    private fun requirePathUnderRoot(candidate: Path, rootPath: Path) {
        val realPath = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(realPath == rootPath || realPath.startsWith(rootPath)) {
            "Path escapes workspace root: $candidate"
        }
    }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    // Resolve a path's parent canonically (enforcing workspace-root containment) then attach the
    // final segment WITHOUT canonicalizing it, so a leaf symlink resolves to the link itself rather
    // than its target. Used by delete so removing a symlink entry removes the link, not what it
    // points at. The leaf must be a single safe segment — separators were already split off and
    // `.`/`..`/null are rejected — and the parent is bounded by resolvePath.
    private fun resolveLeafNoFollow(root: File, path: String): File {
        val normalized = path.replace('\\', '/').trim().trim('/').ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }
        require(normalized != ".") { "Refusing to operate on workspace root" }
        val leafName = normalized.substringAfterLast('/')
        require(leafName.isNotEmpty() && leafName != "." && leafName != "..") { "Invalid path: $path" }
        val parentRel = normalized.substringBeforeLast('/', "")
        val parent = resolvePath(root, parentRel)
        return File(parent, leafName)
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    // The entry path is the logical location under the canonical root, NOT the canonicalized target.
    // Canonicalizing here would make a symlink entry report (and operations target) what it points at
    // instead of the link itself — e.g. a `link -> real` listing would show `real`, so deleting the
    // link would wipe `real`. Containment is enforced by resolvePath/deleteRecursivelyNoFollow, not
    // by this display string, so the logical path is both safe and what the user actually sees.
    private fun File.relativePath(root: File): String =
        relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
