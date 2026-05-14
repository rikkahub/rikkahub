package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun arcErr(code: String, vararg extra: Pair<String, String>) =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        extra.forEach { put(it.first, it.second) }
    }.toString()))

// ---------- URI abstraction (file:// or content://) ----------

/** True for an LLM-supplied path that points to a content:// URI. */
private fun isContent(raw: String) = raw.startsWith("content://")

/** Open an input stream for either a file:// path or a content:// URI. */
private fun openIn(context: Context, raw: String): InputStream? = if (isContent(raw)) {
    runCatching { ContentUriResolver.openInput(context, raw) }.getOrNull()
} else {
    val f = File(raw.removePrefix("file://"))
    if (f.exists() && f.isFile) runCatching { f.inputStream() }.getOrNull() else null
}

/** Open an output stream for either a file:// path or a content:// URI. */
private fun openOut(context: Context, raw: String): OutputStream? = if (isContent(raw)) {
    runCatching { ContentUriResolver.openOutput(context, raw) }.getOrNull()
} else {
    val f = File(raw.removePrefix("file://"))
    runCatching { f.parentFile?.mkdirs(); f.outputStream() }.getOrNull()
}

/** A single concrete file to be added to an archive: its entry name + a stream opener. */
private data class ArchiveSource(val entryName: String, val open: () -> InputStream?)

/** Recursively collect file sources from a file:// path (file or directory). */
private fun collectFileSources(raw: String, baseDir: String?): List<ArchiveSource> {
    val root = File(raw.removePrefix("file://"))
    if (!root.exists()) return emptyList()
    val base = baseDir?.removePrefix("file://")?.let { File(it) }
    fun nameOf(f: File): String = when {
        base != null -> f.relativeToOrNull(base)?.path ?: f.name
        root.isDirectory -> "${root.name}/${f.relativeTo(root).path}"
        else -> f.name
    }
    return if (root.isFile) {
        listOf(ArchiveSource(nameOf(root)) { runCatching { root.inputStream() }.getOrNull() })
    } else {
        root.walkTopDown().filter { it.isFile }.map { f ->
            ArchiveSource(nameOf(f)) { runCatching { f.inputStream() }.getOrNull() }
        }.toList()
    }
}

/** Recursively collect file sources from a content:// tree (or single file). */
private fun collectContentSources(context: Context, raw: String): List<ArchiveSource> {
    val doc = ContentUriResolver.resolve(context, raw) ?: return emptyList()
    val out = mutableListOf<ArchiveSource>()
    fun walk(d: DocumentFile, prefix: String) {
        if (d.isFile) {
            val name = if (prefix.isEmpty()) (d.name ?: "file") else "$prefix/${d.name}"
            val uri = d.uri.toString()
            out.add(ArchiveSource(name) {
                runCatching { ContentUriResolver.openInput(context, uri) }.getOrNull()
            })
        } else if (d.isDirectory) {
            val childPrefix = if (prefix.isEmpty()) (d.name ?: "") else "$prefix/${d.name}"
            d.listFiles().forEach { walk(it, childPrefix) }
        }
    }
    walk(doc, "")
    return out
}

// ---------- zip_files ----------

fun zipFilesTool(context: Context): Tool = Tool(
    name = "zip_files",
    description = """
        Create a .zip archive from a list of files and/or directories. sources and
        destination accept file:// and content:// (USB / SD / Downloads / cloud via SAF
        grant) URIs. compression_level 0-9 (default 6). Returns {success, bytes_written,
        entry_count}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("sources", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("destination", buildJsonObject { put("type", "string") })
                put("base_dir", buildJsonObject { put("type", "string") })
                put("compression_level", buildJsonObject {
                    put("type", "integer"); put("minimum", 0); put("maximum", 9)
                })
            },
            required = listOf("sources", "destination"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val sources = obj["sources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        if (sources.isEmpty()) return@Tool arcErr("sources is required")
        val destination = obj["destination"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool arcErr("destination is required")
        val baseDir = obj["base_dir"]?.jsonPrimitive?.contentOrNull
        val level = (obj["compression_level"]?.jsonPrimitive?.intOrNull ?: 6).coerceIn(0, 9)

        val archiveSources = mutableListOf<ArchiveSource>()
        for (src in sources) {
            val collected = if (isContent(src)) {
                collectContentSources(context, src)
            } else {
                collectFileSources(src, baseDir)
            }
            if (collected.isEmpty()) return@Tool arcErr("source_unreadable", "path" to src)
            archiveSources.addAll(collected)
        }

        val out = openOut(context, destination)
            ?: return@Tool arcErr("destination_unwritable")
        var bytesWritten = 0L
        var entryCount = 0
        return@Tool try {
            ZipOutputStream(out).use { zos ->
                zos.setLevel(level)
                // Zip64 by default so the 65535-entry zip32 ceiling lifts to ~4 billion.
                runCatching { zos.setMethod(ZipOutputStream.DEFLATED) }
                val seen = HashSet<String>()
                for (s in archiveSources) {
                    var name = s.entryName.replace('\\', '/').trimStart('/')
                    if (name.isEmpty()) name = "entry_$entryCount"
                    // De-dupe collisions so the archive stays valid.
                    var unique = name
                    var n = 1
                    while (!seen.add(unique)) {
                        unique = "$name.$n"; n++
                    }
                    val ins = s.open() ?: return@Tool arcErr("source_unreadable", "path" to name)
                    zos.putNextEntry(ZipEntry(unique))
                    ins.use { input2 ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input2.read(buf).also { read = it } >= 0) {
                            zos.write(buf, 0, read)
                            bytesWritten += read
                        }
                    }
                    zos.closeEntry()
                    entryCount++
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("bytes_written", bytesWritten)
                put("entry_count", entryCount)
            }.toString()))
        } catch (e: Throwable) {
            arcErr("zip_failed", "detail" to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    },
)

// ---------- unzip_file ----------

/**
 * Path-traversal check for a zip entry name against a canonical destination root. Shared
 * with the unit test. Returns true when the entry would escape the destination.
 */
internal fun isUnsafeZipEntry(entryName: String): Boolean {
    val normalized = entryName.replace('\\', '/')
    return normalized.startsWith("/") ||
        normalized == ".." ||
        normalized.startsWith("../") ||
        normalized.contains("/../") ||
        normalized.endsWith("/..") ||
        normalized.matches(Regex("^[A-Za-z]:.*"))
}

fun unzipFileTool(context: Context): Tool = Tool(
    name = "unzip_file",
    description = """
        Extract a .zip archive into a directory. source and destination_dir accept file://
        and content:// URIs. Path-traversal entries (../) are rejected. overwrite defaults
        false. Returns {success, entries_extracted, bytes_written}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject { put("type", "string") })
                put("destination_dir", buildJsonObject { put("type", "string") })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("source", "destination_dir"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val source = obj["source"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool arcErr("source is required")
        val destDir = obj["destination_dir"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool arcErr("destination_dir is required")
        val overwrite = obj["overwrite"]?.jsonPrimitive?.let {
            it.contentOrNull?.toBooleanStrictOrNull()
        } ?: false

        val ins = openIn(context, source) ?: return@Tool arcErr("invalid_zip")

        // content:// destinations must be a granted tree directory; file:// destinations
        // a plain directory. Both write per-entry via a small writer closure.
        val contentDestTree: DocumentFile? = if (isContent(destDir)) {
            ContentUriResolver.resolve(context, destDir)
                ?: return@Tool listOf(UIMessagePart.Text(ContentUriResolver.notGrantedEnvelope(destDir)))
        } else null
        val fileDestRoot: File? = if (!isContent(destDir)) {
            File(destDir.removePrefix("file://")).also {
                if (!it.exists()) it.mkdirs()
                if (!it.isDirectory) return@Tool arcErr("destination_unwritable")
            }
        } else null

        var extracted = 0
        var bytesWritten = 0L
        return@Tool try {
            ZipInputStream(BufferedInputStream(ins)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                if (entry == null) return@Tool arcErr("invalid_zip")
                while (entry != null) {
                    val name = entry.name
                    if (isUnsafeZipEntry(name)) {
                        return@Tool arcErr("unsafe_zip_entry", "entry" to name)
                    }
                    if (!entry.isDirectory) {
                        if (fileDestRoot != null) {
                            val target = File(fileDestRoot, name)
                            val canonicalRoot = fileDestRoot.canonicalPath
                            if (!target.canonicalPath.startsWith(canonicalRoot)) {
                                return@Tool arcErr("unsafe_zip_entry", "entry" to name)
                            }
                            if (target.exists() && !overwrite) {
                                return@Tool arcErr("entry_exists", "entry" to name)
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { os ->
                                bytesWritten += copyStream(zis, os)
                            }
                        } else if (contentDestTree != null) {
                            val written = writeEntryToTree(
                                context, contentDestTree, name, overwrite, zis,
                            ) ?: return@Tool arcErr("entry_exists", "entry" to name)
                            bytesWritten += written
                        }
                        extracted++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("entries_extracted", extracted)
                put("bytes_written", bytesWritten)
            }.toString()))
        } catch (e: java.util.zip.ZipException) {
            arcErr("invalid_zip")
        } catch (e: Throwable) {
            arcErr("unzip_failed", "detail" to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    },
)

/** Copy a stream into [os], returning the byte count. */
private fun copyStream(ins: InputStream, os: OutputStream): Long {
    val buf = ByteArray(8192)
    var total = 0L
    var read: Int
    while (ins.read(buf).also { read = it } >= 0) {
        os.write(buf, 0, read)
        total += read
    }
    return total
}

/**
 * Create the nested path [entryName] under [tree] and stream [zis] into it. Returns the
 * byte count, or null when the entry exists and overwrite is false.
 */
private fun writeEntryToTree(
    context: Context,
    tree: DocumentFile,
    entryName: String,
    overwrite: Boolean,
    zis: InputStream,
): Long? {
    val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return 0L
    var dir = tree
    for (i in 0 until parts.size - 1) {
        dir = dir.findFile(parts[i]) ?: dir.createDirectory(parts[i]) ?: return 0L
    }
    val fileName = parts.last()
    val existing = dir.findFile(fileName)
    if (existing != null) {
        if (!overwrite) return null
        existing.delete()
    }
    val created = dir.createFile("application/octet-stream", fileName) ?: return 0L
    val os = context.contentResolver.openOutputStream(created.uri) ?: return 0L
    return os.use { copyStream(zis, it) }
}

// ---------- list_zip_contents ----------

fun listZipContentsTool(context: Context): Tool = Tool(
    name = "list_zip_contents",
    description = """
        List the entries of a .zip archive without extracting. source accepts file:// and
        content:// URIs. Returns {entries: [{name, size, compressed_size, is_dir,
        modified_at_unix_ms, crc32}]}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject { put("type", "string") })
            },
            required = listOf("source"),
        )
    },
    execute = { input ->
        val source = input.jsonObject["source"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool arcErr("source is required")
        return@Tool try {
            if (!isContent(source)) {
                // file:// path — ZipFile reads the central directory, so size /
                // compressed_size / crc are exact.
                val f = File(source.removePrefix("file://"))
                if (!f.exists() || !f.isFile) return@Tool arcErr("invalid_zip")
                val entries = buildJsonArray {
                    java.util.zip.ZipFile(f).use { zf ->
                        for (e in zf.entries()) {
                            addJsonObject {
                                put("name", e.name)
                                put("size", e.size)
                                put("compressed_size", e.compressedSize)
                                put("is_dir", e.isDirectory)
                                put("modified_at_unix_ms", e.time)
                                put("crc32", e.crc)
                            }
                        }
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject { put("entries", entries) }.toString()))
            } else {
                // content:// — only a forward stream is available; size / crc populate
                // after the entry's data has been consumed, so drain each entry first.
                val ins = openIn(context, source) ?: return@Tool arcErr("invalid_zip")
                val entries = buildJsonArray {
                    ZipInputStream(BufferedInputStream(ins)).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        if (entry == null) return@Tool arcErr("invalid_zip")
                        val drain = ByteArray(8192)
                        while (entry != null) {
                            val e = entry
                            while (zis.read(drain) >= 0) { /* consume to populate metadata */ }
                            addJsonObject {
                                put("name", e.name)
                                put("size", e.size)
                                put("compressed_size", e.compressedSize)
                                put("is_dir", e.isDirectory)
                                put("modified_at_unix_ms", e.time)
                                put("crc32", e.crc)
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject { put("entries", entries) }.toString()))
            }
        } catch (e: java.util.zip.ZipException) {
            arcErr("invalid_zip")
        } catch (e: Throwable) {
            arcErr("list_failed", "detail" to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    },
)
