package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import java.util.Base64 as JBase64

/**
 * Phase 25 — content:// branches for the file-manager tools. Routed in from
 * [FileManagerTool.kt] when a path argument starts with `content://`. Each function
 * mirrors the file:// behaviour of its tool but operates on SAF-granted DocumentsProvider
 * trees / single-document URIs via [ContentUriResolver].
 *
 * Security: [ContentUriSafetyGuard] does structural validation; the OS-level SAF grant is
 * the access gate. An ungranted URI surfaces a clean `directory_not_granted` envelope.
 */

private const val CONTENT_DEFAULT_READ = 65536
private const val CONTENT_MAX_READ = 1048576

private fun guardOrNull(raw: String): List<UIMessagePart>? {
    ContentUriSafetyGuard.check(raw)?.let { v ->
        return fmTextPart(fmErrEnvelope(v.code, v.detail))
    }
    return null
}

// ---------- list_files ----------

internal fun listFilesContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    // MediaStore URIs aren't tree-listable.
    if (raw.startsWith("content://media/")) {
        return fmTextPart(buildJsonObject {
            put("error", "listing_not_supported")
            put("detail", "MediaStore URIs are read-only via openInputStream; use a granted Pictures/DCIM tree to list")
        }.toString())
    }
    val doc = ContentUriResolver.resolve(context, raw)
        ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    if (!doc.isDirectory) {
        return fmTextPart(fmErrEnvelope("not_a_directory", "content URI is not a directory: $raw"))
    }
    val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
    val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)
    val collected = mutableListOf<DocumentFile>()
    var truncated = false
    fun collect(d: DocumentFile) {
        for (f in d.listFiles()) {
            if (collected.size >= limit) { truncated = true; return }
            collected.add(f)
            if (recursive && f.isDirectory) collect(f)
        }
    }
    collect(doc)
    return fmTextPart(buildJsonObject {
        put("files", buildJsonArray { collected.forEach { add(documentEntryJson(it)) } })
        put("truncated", truncated)
    }.toString())
}

// ---------- read_file ----------

internal fun readFileContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val maxBytes = (obj["max_bytes"]?.jsonPrimitive?.intOrNull ?: CONTENT_DEFAULT_READ)
        .coerceIn(1, CONTENT_MAX_READ)
    var overflow = false
    val bytes = try {
        val stream = ContentUriResolver.openInput(context, raw)
            ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
        stream.use { s ->
            val b = readUpTo(s, maxBytes)
            // Probe one byte past the budget so `truncated` is honest even when the
            // provider reports no SIZE column (totalSize falls back to bytes.size).
            if (b.size == maxBytes) overflow = s.read() >= 0
            b
        }
    } catch (_: SecurityException) {
        return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    } catch (e: Throwable) {
        return fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
    }
    // total size for the truncation flag
    val totalSize = ContentUriResolver.resolve(context, raw)?.length() ?: bytes.size.toLong()
    val truncated = totalSize > maxBytes || overflow
    val sample = bytes.take(minOf(512, bytes.size))
    val nonPrintable = countNonPrintable(sample)
    val isBinary = sample.isNotEmpty() && (nonPrintable.toDouble() / sample.size) > 0.15
    return if (isBinary) {
        fmTextPart(buildJsonObject {
            put("content_base64", JBase64.getEncoder().encodeToString(bytes))
            put("binary", true)
            put("bytes_read", bytes.size)
            put("truncated", truncated)
        }.toString())
    } else {
        fmTextPart(buildJsonObject {
            put("content", String(bytes, Charsets.UTF_8))
            put("truncated", truncated)
            put("bytes_read", bytes.size)
            put("encoding", "UTF-8")
        }.toString())
    }
}

// ---------- write_binary_file ----------

internal fun writeBinaryFileContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val b64 = obj["base64_content"]?.jsonPrimitive?.contentOrNull
        ?: return fmTextPart(fmErrEnvelope("missing_content", "base64_content is required"))
    val bytes = try {
        JBase64.getDecoder().decode(b64)
    } catch (_: IllegalArgumentException) {
        return fmTextPart(fmErrEnvelope("bad_base64", "base64_content is not valid base64"))
    }
    return try {
        val os = ContentUriResolver.openOutput(context, raw)
            ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
        os.use { it.write(bytes) }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("path", raw)
            put("bytes_written", bytes.size)
        }.toString())
    } catch (_: SecurityException) {
        fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    } catch (e: Throwable) {
        fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error writing content URI"))
    }
}

// ---------- delete_file ----------

internal fun deleteFileContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val doc = ContentUriResolver.resolve(context, raw)
        ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
    if (doc.isDirectory && doc.listFiles().isNotEmpty() && !recursive) {
        return fmTextPart(fmErrEnvelope("not_empty", "Directory is not empty. Pass recursive=true."))
    }
    val ok = try {
        doc.delete()
    } catch (e: Throwable) {
        return fmTextPart(fmErrEnvelope("io_error", e.message ?: "delete failed"))
    }
    return if (ok) {
        fmTextPart(buildJsonObject {
            put("success", true)
            put("path", raw)
            put("deleted_count", 1)
        }.toString())
    } else {
        fmTextPart(fmErrEnvelope("delete_failed", "DocumentsProvider refused the delete"))
    }
}

// ---------- create_directory ----------

internal fun createDirectoryContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull
        ?: return fmTextPart(fmErrEnvelope(
            "missing_name",
            "For a content:// parent tree, pass the new directory name in the 'name' argument.",
        ))
    val parent = ContentUriResolver.resolve(context, raw)
        ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    if (!parent.isDirectory) {
        return fmTextPart(fmErrEnvelope("not_a_directory", "content URI parent is not a directory."))
    }
    val existing = parent.findFile(name)
    if (existing != null && existing.isDirectory) {
        return fmTextPart(buildJsonObject {
            put("success", true)
            put("path", existing.uri.toString())
            put("created", false)
        }.toString())
    }
    val created = parent.createDirectory(name)
        ?: return fmTextPart(fmErrEnvelope("create_failed", "DocumentsProvider refused createDirectory"))
    return fmTextPart(buildJsonObject {
        put("success", true)
        put("path", created.uri.toString())
        put("created", true)
    }.toString())
}

// ---------- file_info ----------

internal fun fileInfoContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val doc = ContentUriResolver.resolve(context, raw)
    if (doc == null || !doc.exists()) {
        return fmTextPart(buildJsonObject {
            put("path", raw)
            put("exists", false)
        }.toString())
    }
    return fmTextPart(buildJsonObject {
        put("path", doc.uri.toString())
        put("exists", true)
        put("size_bytes", if (doc.isDirectory) 0L else doc.length())
        put("modified_at_ms", doc.lastModified())
        put("is_directory", doc.isDirectory)
        doc.type?.let { put("mime", it) }
        put("is_content_uri", true)
    }.toString())
}

// ---------- find_files ----------

private const val CONTENT_FIND_VISIT_CAP = 10_000

internal fun findFilesContent(context: Context, raw: String, obj: JsonObject): List<UIMessagePart> {
    guardOrNull(raw)?.let { return it }
    val root = ContentUriResolver.resolve(context, raw)
        ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(raw))
    if (!root.isDirectory) {
        return fmTextPart(fmErrEnvelope("not_a_directory", "content URI root is not a directory."))
    }
    val query = obj["query"]?.jsonPrimitive?.contentOrNull
        ?: return fmTextPart(fmErrEnvelope("missing_query", "query is required"))
    val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: true
    val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)
    val collected = mutableListOf<DocumentFile>()
    var visited = 0
    var truncated = false
    fun walk(d: DocumentFile) {
        for (f in d.listFiles()) {
            if (visited >= CONTENT_FIND_VISIT_CAP || collected.size >= limit) {
                truncated = true; return
            }
            visited++
            if ((f.name ?: "").contains(query, ignoreCase = true)) collected.add(f)
            if (recursive && f.isDirectory) walk(f)
        }
    }
    walk(root)
    return fmTextPart(buildJsonObject {
        put("files", buildJsonArray { collected.forEach { add(documentEntryJson(it)) } })
        put("truncated", truncated)
    }.toString())
}

// ---------- move / copy (mixed file:// + content://) ----------

internal fun moveOrCopyContent(
    context: Context,
    rawSrc: String?,
    rawDst: String?,
    obj: JsonObject,
    deleteSrc: Boolean,
): List<UIMessagePart> {
    if (rawSrc.isNullOrBlank()) return fmTextPart(fmErrEnvelope("missing_src", "src is required"))
    if (rawDst.isNullOrBlank()) return fmTextPart(fmErrEnvelope("missing_dst", "dst is required"))
    val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

    // Open the source stream (file:// or content://).
    val srcStream = when {
        rawSrc.startsWith("content://") -> {
            ContentUriSafetyGuard.check(rawSrc)?.let {
                return fmTextPart(fmErrEnvelope(it.code, it.detail))
            }
            try {
                ContentUriResolver.openInput(context, rawSrc)
                    ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(rawSrc))
            } catch (_: SecurityException) {
                return fmTextPart(ContentUriResolver.notGrantedEnvelope(rawSrc))
            }
        }
        else -> {
            val expanded = AgentWorkspace.expand(rawSrc)
            PathSafetyGuard.check(expanded)?.let {
                return fmTextPart(fmErrEnvelope(it.code, it.detail))
            }
            val f = java.io.File(expanded)
            if (!f.exists() || !f.isFile) {
                return fmTextPart(fmErrEnvelope("not_found", "Source does not exist or is not a file: $rawSrc"))
            }
            f.inputStream()
        }
    }

    // Open the destination stream (file:// or content://).
    val dstStream = when {
        rawDst.startsWith("content://") -> {
            ContentUriSafetyGuard.check(rawDst)?.let {
                return fmTextPart(fmErrEnvelope(it.code, it.detail))
            }
            try {
                ContentUriResolver.openOutput(context, rawDst)
                    ?: return fmTextPart(ContentUriResolver.notGrantedEnvelope(rawDst))
            } catch (_: SecurityException) {
                return fmTextPart(ContentUriResolver.notGrantedEnvelope(rawDst))
            }
        }
        else -> {
            val expanded = AgentWorkspace.expand(rawDst)
            PathSafetyGuard.check(expanded)?.let {
                return fmTextPart(fmErrEnvelope(it.code, it.detail))
            }
            val f = java.io.File(expanded)
            if (f.exists() && !overwrite) {
                return fmTextPart(fmErrEnvelope("destination_exists", "Destination exists. Pass overwrite=true."))
            }
            f.parentFile?.mkdirs()
            f.outputStream()
        }
    }

    var bytesCopied = 0L
    try {
        srcStream.use { ins ->
            dstStream.use { os ->
                val buf = ByteArray(8192)
                var read: Int
                while (ins.read(buf).also { read = it } >= 0) {
                    os.write(buf, 0, read)
                    bytesCopied += read
                }
            }
        }
    } catch (e: Throwable) {
        return fmTextPart(fmErrEnvelope("io_error", e.message ?: "stream copy failed"))
    }

    if (deleteSrc) {
        if (rawSrc.startsWith("content://")) {
            runCatching { ContentUriResolver.resolve(context, rawSrc)?.delete() }
        } else {
            runCatching { java.io.File(AgentWorkspace.expand(rawSrc)).delete() }
        }
    }

    return fmTextPart(buildJsonObject {
        put("success", true)
        put("from", rawSrc)
        put("to", rawDst)
        put("bytes_copied", bytesCopied)
    }.toString())
}
