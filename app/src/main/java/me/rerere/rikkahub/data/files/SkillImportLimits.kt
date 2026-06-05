package me.rerere.rikkahub.data.files

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream

/**
 * Thrown when a skill import exceeds one of the configured size/count limits.
 * Distinct from generic [Exception] so callers can surface a clear message and
 * tests can assert on the limit being enforced.
 */
internal class SkillImportLimitException(message: String) : Exception(message)

/**
 * Size and count caps for the skill import paths (local file, zip, GitHub).
 *
 * The caps are deliberately generous relative to real skills (text markdown plus
 * a few small assets, typically well under 1 MB) while bounding pathological
 * inputs such as zip bombs and runaway GitHub downloads.
 */
internal object SkillImportLimits {
    const val MAX_INPUT_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 10L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 100L * 1024 * 1024
    const val MAX_ENTRY_COUNT = 2000
    const val MAX_GITHUB_DEPTH = 16

    private const val CHUNK_SIZE = 8 * 1024

    /**
     * Reads [input] fully into a byte array, but aborts the moment the running
     * total exceeds [max]. This is the zip-bomb-safe primitive: it never buffers
     * more than [max] (+ one chunk) bytes, so it cannot be made to allocate
     * unbounded memory by an oversized or maliciously-deflated stream.
     */
    fun readBytesLimited(input: InputStream, max: Long, label: String): ByteArray {
        val buffer = ByteArray(CHUNK_SIZE)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > max) {
                throw SkillImportLimitException(
                    "导入失败：$label 超过大小限制（${max / (1024 * 1024)} MB）"
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Guards the per-import accumulation limits (total uncompressed bytes and
     * entry count). Kept pure so the zip and GitHub loops delegate here and are
     * unit-testable without a device.
     */
    fun checkTotalAndCount(currentTotal: Long, currentCount: Int) {
        if (currentCount > MAX_ENTRY_COUNT) {
            throw SkillImportLimitException(
                "导入失败：文件数量超过限制（$MAX_ENTRY_COUNT）"
            )
        }
        if (currentTotal > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            throw SkillImportLimitException(
                "导入失败：总大小超过限制（${MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)} MB）"
            )
        }
    }

    /**
     * Scans [zipInput], enforcing the entry/size/total-uncompressed limits on EVERY
     * non-directory entry before deciding whether to keep it. Counting and reading
     * happen regardless of whether [normalizePath] accepts the entry name, so a bomb
     * hidden under a rejected path (e.g. `../bomb`, `.`, empty) still consumes the
     * budget and is rejected rather than silently inflated by closeEntry().
     *
     * [normalizePath] returns the sanitized path to store under, or null to drop the
     * entry (after it has already been counted and size-checked). Returns the accepted
     * `path -> bytes` entries in encounter order.
     */
    fun scanZipEntries(
        zipInput: ZipInputStream,
        normalizePath: (String) -> String?,
    ): LinkedHashMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalUncompressed = 0L
        while (true) {
            val entry = zipInput.nextEntry ?: break
            // Count and cap EVERY entry, including directories: a zip with millions
            // of directory records and no files must still trip MAX_ENTRY_COUNT.
            entryCount++
            checkTotalAndCount(totalUncompressed, entryCount)
            if (!entry.isDirectory) {
                // readBytesLimited throws on the per-entry cap. We deliberately do NOT
                // call closeEntry() on that path: closeEntry() drains the unconsumed
                // remainder of the entry by inflating it to EOF, which would fully
                // expand a zip bomb even though the size guard already fired. Letting
                // the exception propagate aborts the scan; the caller's use{} closes
                // the whole ZipInputStream and releases the deflater.
                val entryBytes = readBytesLimited(zipInput, MAX_ENTRY_BYTES, entry.name)
                totalUncompressed += entryBytes.size
                checkTotalAndCount(totalUncompressed, entryCount)
                val path = normalizePath(entry.name)
                if (path != null) {
                    files[path] = entryBytes
                }
            }
            zipInput.closeEntry()
        }
        return files
    }

    /**
     * A single entry returned by the GitHub Contents API. [type] preserves the API's
     * tri-state ("file", "dir", or anything else such as "submodule"/"symlink") so the
     * walk can ignore unknown types instead of forcing them down the file path. [path]
     * is the repo-relative path; [downloadUrl] is only meaningful for files.
     */
    data class GitHubEntry(
        val path: String,
        val type: String,
        val downloadUrl: String?,
    )

    /**
     * Walks the GitHub tree rooted at [dirPath], enforcing depth AND a visited-entry
     * count cap. The count is incremented for EVERY entry (file and directory), not
     * just downloaded files, so a repo with massive directory fan-out can't drive an
     * unbounded number of Contents-API requests while the file count never reaches
     * the cap. [fetchDir] performs the (injected) IO for one directory and returns its
     * entries, or null on failure (which aborts the walk and returns false).
     *
     * Appends accepted files (relative-path -> downloadUrl) to [result] in encounter
     * order. Returns false if any fetch failed or any file lacked a download URL.
     */
    fun traverseGitHubTree(
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
        visited: IntArray,
        depth: Int,
        fetchDir: (String) -> List<GitHubEntry>?,
    ): Boolean {
        if (depth > MAX_GITHUB_DEPTH) {
            throw SkillImportLimitException(
                "导入失败：GitHub 目录层级超过限制（$MAX_GITHUB_DEPTH）"
            )
        }
        val entries = fetchDir(dirPath) ?: return false
        for (entry in entries) {
            // Preserve the GitHub Contents API tri-state: only "file" and "dir" are
            // handled. Other types (submodule, symlink) are silently skipped, exactly
            // as the pre-limit code did — a repo containing a submodule (download_url
            // null) must still import, and symlinks must not be downloaded.
            val relativePath = entry.path.removePrefix("$basePath/").removePrefix(basePath)
            when (entry.type) {
                "dir" -> {
                    visited[0]++
                    checkTotalAndCount(currentTotal = 0, currentCount = visited[0])
                    val ok = traverseGitHubTree(entry.path, basePath, result, visited, depth + 1, fetchDir)
                    if (!ok) return false
                }

                "file" -> {
                    visited[0]++
                    checkTotalAndCount(currentTotal = 0, currentCount = visited[0])
                    val downloadUrl = entry.downloadUrl?.takeIf { it.isNotBlank() } ?: return false
                    result.add(relativePath to downloadUrl)
                }
            }
        }
        return true
    }
}
