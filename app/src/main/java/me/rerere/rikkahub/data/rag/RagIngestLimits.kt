package me.rerere.rikkahub.data.rag

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

/**
 * Thrown when a RAG ingestion copy exceeds the configured input-size limit.
 * Distinct from generic [Exception] so the view model can surface a clear
 * "too large" message and tests can assert the cap is enforced. Mirrors
 * [me.rerere.rikkahub.data.files.SkillImportLimitException].
 */
internal class RagIngestLimitException(message: String) : Exception(message)

/**
 * Size/count caps and safe-IO primitives for the RAG ingestion pipeline
 * (copy temp file -> extract text -> chunk -> embed). Bounds disk, extracted
 * text, and embedding work, and strips provider-supplied display names of any
 * filesystem influence.
 *
 * Every function here is pure and Android-free so the ingestion guards are
 * unit-testable on the JVM (the kind of test CI runs), exactly like
 * [me.rerere.rikkahub.data.files.SkillImportLimits].
 */
internal object RagIngestLimits {
    const val MAX_INPUT_BYTES = 50L * 1024 * 1024
    const val MAX_EXTRACTED_CHARS = 2_000_000
    const val MAX_CHUNKS = 2_000
    const val BINARY_SNIFF_BYTES = 4096

    private const val CHUNK_SIZE = 8 * 1024

    /**
     * Streams [input] to [output], incrementing a running total per read and
     * throwing [RagIngestLimitException] the moment the total exceeds [max] —
     * before writing the over-cap chunk. This is the oversized-input-safe
     * analogue of [me.rerere.rikkahub.data.files.SkillImportLimits.readBytesLimited]:
     * it never copies past the cap, so a huge (or effectively unbounded) source
     * cannot fill the disk before being rejected. Returns the total bytes copied.
     */
    fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(CHUNK_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > max) {
                throw RagIngestLimitException(
                    "文件超过大小限制（${max / (1024 * 1024)} MB）"
                )
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    /**
     * Builds a temp file under [dir] whose name is `<uuid>.<ext>`, where the
     * extension is derived from [displayName] but stripped to letters/digits and
     * capped — so a provider-supplied display name can never contribute a path
     * separator, `..`, control char, or unbounded length to the temp path. The
     * UUID guarantees uniqueness; the extension is purely cosmetic.
     */
    fun tempFileForDisplayName(dir: File, displayName: String): File {
        val ext = displayName.substringAfterLast('.', "bin")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(16)
            .ifBlank { "bin" }
        return File(dir, "${Uuid.random()}.$ext")
    }

    /**
     * Sanitizes a provider-supplied [name] for use as display-only metadata
     * (e.g. [KnowledgeDocument.fileName]): strips control chars and path
     * separators, collapses `..`, caps length, and defaults blank to "document".
     * This never touches the filesystem — it only bounds what is shown/stored.
     */
    fun sanitizeDisplayName(name: String): String = name
        .replace(Regex("[\\p{Cntrl}/\\\\]+"), "_")
        .replace("..", "_")
        .take(160)
        .ifBlank { "document" }

    /**
     * Heuristic binary sniff over a prefix [bytes]: reject if any NUL byte is
     * present (the strongest binary tell) or if fewer than 80% of bytes are
     * "text-like". Tab/LF/CR and printable ASCII (0x20..0x7E) count as text, as
     * do high bytes (>= 0x80) which carry UTF-8 multibyte content — so genuine
     * non-ASCII text (CJK, accents) is not misclassified as binary. Mirrors the
     * classification in [me.rerere.rikkahub.data.files.FileUtils] but is exposed
     * here for the ingestion path.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var printable = 0
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            if (c == 0x00) return false
            if (c == 0x09 || c == 0x0A || c == 0x0D || c in 0x20..0x7E || c >= 0x80) {
                printable += 1
            }
        }
        return printable.toDouble() / bytes.size >= 0.8
    }

    /**
     * Reads [file] as plain text ONLY if its leading [sniffBytes] look like text;
     * returns null when the prefix sniffs as binary, so a binary file is never
     * decoded into garbage "content". The body is read bounded by [max] via
     * [copyLimited] (returns null on overflow) and decoded UTF-8 with BOM
     * stripping. null thus means "not safe to read as text" (binary or oversized).
     */
    fun safeReadPlainText(
        file: File,
        max: Long = MAX_INPUT_BYTES,
        sniffBytes: Int = BINARY_SNIFF_BYTES,
    ): String? {
        val prefix = file.inputStream().use { input ->
            val buf = ByteArray(sniffBytes)
            var off = 0
            while (off < buf.size) {
                val read = input.read(buf, off, buf.size - off)
                if (read == -1) break
                off += read
            }
            buf.copyOf(off)
        }
        if (!looksLikeText(prefix)) return null

        val bytes = try {
            file.inputStream().use { input ->
                java.io.ByteArrayOutputStream().use { out ->
                    copyLimited(input, out, max)
                    out.toByteArray()
                }
            }
        } catch (_: RagIngestLimitException) {
            return null
        }
        return decodeUtf8(bytes)
    }

    /**
     * Decodes [bytes] as UTF-8, stripping a leading UTF-8 BOM if present.
     * Extracted so [safeReadPlainText] stays focused on IO/sniffing.
     */
    private fun decodeUtf8(bytes: ByteArray): String {
        val hasBom = bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        val start = if (hasBom) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /** True when [textLength] is within the extracted-text cap (rejects above). */
    fun enforceMaxChars(textLength: Int): Boolean = textLength <= MAX_EXTRACTED_CHARS

    /** True when [count] chunks is within the chunk cap (rejects above). */
    fun enforceMaxChunks(count: Int): Boolean = count <= MAX_CHUNKS
}
