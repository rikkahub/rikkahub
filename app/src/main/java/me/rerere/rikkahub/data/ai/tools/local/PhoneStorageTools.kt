package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.io.File
import java.util.UUID
import java.util.Base64

private const val SAF_TIMEOUT_MS = 300_000L
private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 500
private const val DEFAULT_READ_BYTES = 64 * 1024
private const val MAX_READ_BYTES = 1024 * 1024

private fun result(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

private fun error(code: String, detail: String) = result(buildJsonObject {
    put("error", code)
    put("detail", detail)
})

private fun JsonObject.pathArg(): String? =
    this["path"]?.jsonPrimitive?.contentOrNull
        ?: this["content_uri"]?.jsonPrimitive?.contentOrNull

private fun requireContentUri(raw: String?): String? =
    raw?.takeIf { ContentUriSafetyGuard.check(it) == null }

private fun entry(doc: DocumentFile) = buildJsonObject {
    put("path", doc.uri.toString())
    put("name", doc.name.orEmpty())
    put("is_directory", doc.isDirectory)
    put("size_bytes", if (doc.isDirectory) 0L else doc.length())
    put("modified_at_ms", doc.lastModified())
    doc.type?.let { put("mime", it) }
}

private fun resolveDocument(context: Context, raw: String): DocumentFile? =
    ContentUriResolver.resolve(context, raw)?.takeIf { it.exists() }

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = "List device storage volumes and their mount state. File access still requires an explicit SAF directory grant.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val storage = context.getSystemService(StorageManager::class.java)
            ?: return@Tool error("unavailable", "StorageManager is unavailable.")
        result(buildJsonObject {
            put("volumes", buildJsonArray {
                storage.storageVolumes.forEach { volume ->
                    add(buildJsonObject {
                        put("label", volume.getDescription(context).orEmpty())
                        put("uuid", volume.uuid.orEmpty())
                        put("primary", volume.isPrimary)
                        put("removable", volume.isRemovable)
                        put("mounted", volume.state == android.os.Environment.MEDIA_MOUNTED)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            volume.directory?.let { dir ->
                                put("path", dir.absolutePath)
                                put("free_bytes", dir.freeSpace)
                                put("total_bytes", dir.totalSpace)
                            }
                        }
                    })
                }
            })
        })
    },
)

fun listGrantedDirectoriesTool(grantStore: StorageVolumeGrantStore): Tool = Tool(
    name = "list_granted_directories",
    description = "List directories the user has explicitly granted to the agent through Android's system picker.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val grants = grantStore.reconcile()
        result(buildJsonObject {
            put("directories", buildJsonArray {
                grants.forEach { grant ->
                    add(buildJsonObject {
                        put("content_uri", grant.contentUri)
                        put("display_name", grant.displayName)
                        put("authority", grant.authority)
                    })
                }
            })
        })
    },
)

fun grantDirectoryAccessTool(
    context: Context,
    grantStore: StorageVolumeGrantStore,
    buffer: SafPickerResultBuffer,
): Tool = Tool(
    name = "grant_directory_access",
    description = "Open the Android directory picker. The user must approve a directory before file tools can access it.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("initial_uri", buildJsonObject { put("type", "string") })
        })
    },
    needsApproval = { true },
    execute = { input ->
        val initial = input.jsonObject["initial_uri"]?.jsonPrimitive?.contentOrNull
        val id = UUID.randomUUID().toString()
        val deferred = buffer.register(id)
        val intent = Intent(context, ToolHostActivity::class.java).apply {
            putExtra(ToolHostActivity.EXTRA_REQUEST_ID, id)
            putExtra(ToolHostActivity.EXTRA_INITIAL_URI, initial)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        when (val picked = withTimeoutOrNull(SAF_TIMEOUT_MS) { deferred.await() }) {
            is SafPickerResult.Granted -> {
                val uri = android.net.Uri.parse(picked.contentUri)
                val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                    .getOrNull()
                    ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                    ?: picked.contentUri
                grantStore.add(
                    StorageVolumeGrantStore.Grant(
                        contentUri = picked.contentUri,
                        displayName = name,
                        authority = uri.authority.orEmpty(),
                    )
                )
                result(buildJsonObject {
                    put("granted", true)
                    put("content_uri", picked.contentUri)
                    put("display_name", name)
                })
            }
            is SafPickerResult.Error -> error("picker_failed", picked.message)
            else -> result(buildJsonObject { put("granted", false) })
        }
    },
)

fun listFilesTool(): Tool = Tool(
    name = "list_files",
    description = "List files under an explicitly SAF-granted content:// directory.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put("recursive", buildJsonObject { put("type", "boolean") })
        put("limit", buildJsonObject { put("type", "integer") })
    }, required = listOf("path")) },
    execute = { input ->
        val raw = input.jsonObject.pathArg()
        val uri = requireContentUri(raw) ?: return@Tool error("directory_not_granted", "Use grant_directory_access first and pass its content_uri.")
        val root = resolveDocument(fmContext(), uri) ?: return@Tool error("directory_not_granted", ContentUriResolver.notGranted(uri))
        if (!root.isDirectory) return@Tool error("not_a_directory", "Path is not a directory.")
        val recursive = input.jsonObject["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val files = mutableListOf<DocumentFile>()
        fun collect(dir: DocumentFile) {
            dir.listFiles().forEach { file ->
                if (files.size >= limit) return
                files += file
                if (recursive && file.isDirectory) collect(file)
            }
        }
        collect(root)
        result(buildJsonObject {
            put("files", buildJsonArray { files.forEach { add(entry(it)) } })
            put("truncated", files.size >= limit)
        })
    },
)

fun readFileTool(): Tool = Tool(
    name = "read_file",
    description = "Read an explicitly SAF-granted file. Text is UTF-8; binary content is returned as base64.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put("max_bytes", buildJsonObject { put("type", "integer") })
    }, required = listOf("path")) },
    execute = { input ->
        val raw = input.jsonObject.pathArg()
        val uri = requireContentUri(raw) ?: return@Tool error("directory_not_granted", "Pass a content:// URI returned by list_files or grant_directory_access.")
        val max = (input.jsonObject["max_bytes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        val bytes = runCatching {
            fmContext().contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val bufferBytes = ByteArray(8192)
                var total = 0
                while (total < max) {
                    val count = stream.read(bufferBytes, 0, minOf(bufferBytes.size, max - total))
                    if (count < 0) break
                    out.write(bufferBytes, 0, count)
                    total += count
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@Tool error("read_failed", "The provider refused the read or the URI is no longer granted.")
        val binary = bytes.any { it.toInt() and 0xff < 9 && it.toInt() and 0xff !in listOf(0, 9) }
        result(buildJsonObject {
            put("path", uri)
            put("truncated", bytes.size >= max)
            put("bytes_read", bytes.size)
            if (binary) put("content_base64", Base64.getEncoder().encodeToString(bytes))
            else put("content", bytes.toString(Charsets.UTF_8))
        })
    },
)

private fun writeBytesTool(name: String, binary: Boolean): Tool = Tool(
    name = name,
    description = "Write a file through an explicitly granted SAF URI.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put(if (binary) "base64_content" else "content", buildJsonObject { put("type", "string") })
    }, required = listOf("path")) },
    needsApproval = { true },
    execute = { input ->
        val raw = input.jsonObject.pathArg()
        val uri = requireContentUri(raw) ?: return@Tool error("directory_not_granted", "Pass a content:// URI returned by the system picker.")
        val bytes = if (binary) {
            runCatching { Base64.getDecoder().decode(input.jsonObject["base64_content"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrNull()
                ?: return@Tool error("bad_base64", "base64_content is invalid.")
        } else {
            input.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.toByteArray(Charsets.UTF_8)
                ?: return@Tool error("missing_content", "content is required.")
        }
        val ok = runCatching {
            fmContext().contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { it.write(bytes) }
                ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!ok) return@Tool error("write_failed", "The provider refused the write.")
        result(buildJsonObject { put("success", true); put("path", uri); put("bytes_written", bytes.size) })
    },
)

fun writeTextFileTool(): Tool = writeBytesTool("write_text_file", false)
fun writeBinaryFileTool(): Tool = writeBytesTool("write_binary_file", true)

fun deleteFileTool(): Tool = Tool(
    name = "delete_file",
    description = "Delete a file or empty directory through an explicitly granted SAF URI.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put("recursive", buildJsonObject { put("type", "boolean") })
    }, required = listOf("path")) },
    needsApproval = { true },
    execute = { input ->
        val raw = input.jsonObject.pathArg()
        val uri = requireContentUri(raw) ?: return@Tool error("directory_not_granted", "Pass a granted content:// URI.")
        val doc = resolveDocument(fmContext(), uri) ?: return@Tool error("not_found", "File does not exist or grant was revoked.")
        val recursive = input.jsonObject["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        if (doc.isDirectory && doc.listFiles().isNotEmpty() && !recursive) return@Tool error("not_empty", "Pass recursive=true to delete a non-empty directory.")
        if (!doc.delete()) return@Tool error("delete_failed", "The provider refused the delete.")
        result(buildJsonObject { put("success", true); put("path", uri) })
    },
)

private fun transferTool(name: String, deleteSource: Boolean): Tool = Tool(
    name = name,
    description = "Copy or move a file between SAF-granted document URIs.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("source", buildJsonObject { put("type", "string") })
        put("destination", buildJsonObject { put("type", "string") })
        put("overwrite", buildJsonObject { put("type", "boolean") })
    }, required = listOf("source", "destination")) },
    needsApproval = { true },
    execute = { input ->
        val source = requireContentUri(input.jsonObject["source"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool error("invalid_source", "source must be a granted content:// URI.")
        val destination = requireContentUri(input.jsonObject["destination"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool error("invalid_destination", "destination must be a granted content:// URI.")
        val context = fmContext()
        val inputStream = context.contentResolver.openInputStream(android.net.Uri.parse(source))
            ?: return@Tool error("read_failed", "Source cannot be read.")
        val outputStream = context.contentResolver.openOutputStream(android.net.Uri.parse(destination), "wt")
            ?: return@Tool error("write_failed", "Destination cannot be written.")
        val copied = runCatching {
            inputStream.use { inputBytes -> outputStream.use { outputBytes -> inputBytes.copyTo(outputBytes) } }
            true
        }.getOrDefault(false)
        if (!copied) return@Tool error("transfer_failed", "The provider refused the transfer.")
        if (deleteSource) DocumentFile.fromSingleUri(context, android.net.Uri.parse(source))?.delete()
        result(buildJsonObject { put("success", true); put("source", source); put("destination", destination) })
    },
)

fun moveFileTool(): Tool = transferTool("move_file", true)
fun copyFileTool(): Tool = transferTool("copy_file", false)

fun createDirectoryTool(): Tool = Tool(
    name = "create_directory",
    description = "Create a directory below an explicitly granted SAF directory.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("parent", buildJsonObject { put("type", "string") })
        put("name", buildJsonObject { put("type", "string") })
    }, required = listOf("parent", "name")) },
    needsApproval = { true },
    execute = { input ->
        val parent = requireContentUri(input.jsonObject["parent"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool error("invalid_parent", "parent must be a granted content:// directory.")
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@Tool error("missing_name", "name is required.")
        val dir = resolveDocument(fmContext(), parent) ?: return@Tool error("not_found", "Parent directory is unavailable.")
        if (!dir.isDirectory) return@Tool error("not_a_directory", "parent is not a directory.")
        val created = dir.createDirectory(name) ?: return@Tool error("create_failed", "The provider refused directory creation.")
        result(buildJsonObject { put("success", true); put("path", created.uri.toString()) })
    },
)

fun fileInfoTool(): Tool = Tool(
    name = "file_info",
    description = "Return metadata for a granted SAF document.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("path", buildJsonObject { put("type", "string") }) }, required = listOf("path")) },
    execute = { input ->
        val uri = requireContentUri(input.jsonObject.pathArg()) ?: return@Tool error("invalid_path", "path must be a granted content:// URI.")
        val doc = resolveDocument(fmContext(), uri) ?: return@Tool result(buildJsonObject { put("path", uri); put("exists", false) })
        result(buildJsonObject { put("exists", true); entry(doc).forEach { (key, value) -> put(key, value) } })
    },
)

fun findFilesTool(): Tool = Tool(
    name = "find_files",
    description = "Find files by name under a granted SAF directory.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("root", buildJsonObject { put("type", "string") })
        put("query", buildJsonObject { put("type", "string") })
        put("limit", buildJsonObject { put("type", "integer") })
    }, required = listOf("root", "query")) },
    execute = { input ->
        val rootUri = requireContentUri(input.jsonObject["root"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool error("invalid_root", "root must be a granted content:// directory.")
        val root = resolveDocument(fmContext(), rootUri) ?: return@Tool error("not_found", "root is unavailable.")
        if (!root.isDirectory) return@Tool error("not_a_directory", "root is not a directory.")
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val matches = mutableListOf<DocumentFile>()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { child ->
                if (matches.size >= limit) return
                if (child.name.orEmpty().contains(query, ignoreCase = true)) matches += child
                if (child.isDirectory) walk(child)
            }
        }
        walk(root)
        result(buildJsonObject { put("files", buildJsonArray { matches.forEach { add(entry(it)) } }); put("truncated", matches.size >= limit) })
    },
)
