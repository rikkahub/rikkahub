package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * Writes text content to an arbitrary path on shared storage. Uses the same direct
 * java.io.File path machinery as the rest of the file-manager surface (and the same
 * [PathSafetyGuard] system-path / traversal blocks), so the `path` argument is the
 * authoritative target.
 *
 * Three explicit modes via two booleans:
 *  - `append=false`, `overwrite=false` (DEFAULT): refuses if the file already exists,
 *    so a model-driven save can't silently clobber a user's work.
 *  - `append=false`, `overwrite=true`: truncate-and-write.
 *  - `append=true`: append (creates the file if missing; `overwrite` is ignored).
 *
 * Replaces the prior MediaStore-only implementation that auto-renamed on collision
 * (`note (1).txt`) and ignored sub-paths inside the filename. That behavior surfaced
 * as a bug during file-manager E2E testing — the model wrote to the right NAME but
 * the wrong PATH, breaking every subsequent read/copy/move.
 */
fun writeTextFileTool(context: Context): Tool = Tool(
    name = "write_text_file",
    description = """
        Save text content to a file at the given absolute path. The full path must be
        outside system / other-app sandboxes (the path-safety guard refuses those with a
        structured envelope). Three behaviors via the append + overwrite flags: default
        refuses if the file exists; overwrite=true truncates; append=true appends to
        existing content (creates the file if missing). Creates missing parent directories
        automatically for app workspace paths and common shared-storage user folders.
        Also accepts a content:// URI (USB / SD / Downloads / cloud via SAF grant);
        append is not supported on content:// URIs.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to write. e.g. /sdcard/Download/notes.txt")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full text content to write to the file")
                })
                put("append", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, append to an existing file rather than truncating. Creates the file if missing. Default false.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true and append=false, allow truncating an existing file. Default false (refuses if file exists). Ignored when append=true.")
                })
            },
            required = listOf("path", "content")
        )
    },
    execute = { input ->
        val params: JsonObject = input.jsonObject

        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank()) {
            return@Tool errEnvelope("missing_path", "path is required (absolute path or ~/...)")
        }
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnvelope("missing_content", "content is required")

        // content:// path — route through the SAF resolver. append is unsupported on
        // content:// (no universal seek-to-end), so append on a content:// URI is an error.
        if (ContentUriSafetyGuard.isContentUri(rawPath)) {
            ContentUriSafetyGuard.check(rawPath)?.let { v ->
                return@Tool errEnvelope(v.code, v.detail)
            }
            if (params["append"]?.jsonPrimitive?.booleanOrNull == true) {
                return@Tool errEnvelope("append_unsupported", "append is not supported on content:// URIs")
            }
            return@Tool try {
                val os = ContentUriResolver.openOutput(context, rawPath)
                    ?: return@Tool listOf(UIMessagePart.Text(ContentUriResolver.notGrantedEnvelope(rawPath)))
                val bytes = content.toByteArray(Charsets.UTF_8)
                os.use { it.write(bytes) }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("path", rawPath)
                    put("bytes_written", bytes.size)
                    put("appended", false)
                }.toString()))
            } catch (e: SecurityException) {
                listOf(UIMessagePart.Text(ContentUriResolver.notGrantedEnvelope(rawPath)))
            } catch (e: Throwable) {
                errEnvelope("write_failed", "${e::class.simpleName}: ${e.message ?: "unknown"}")
            }
        }

        val path = AgentWorkspace.expand(rawPath)

        val append = params["append"]?.jsonPrimitive?.booleanOrNull == true
        val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull == true

        PathSafetyGuard.check(path)?.let { v ->
            return@Tool errEnvelope(v.code, v.detail)
        }

        val file = File(path)
        // Auto-mkdir parent for app workspace writes and normal shared-storage user data
        // folders. Keep unexpected absolute paths explicit so mistakes surface clearly.
        if (shouldAutoCreateParent(rawPath, path)) file.parentFile?.mkdirs()

        if (file.exists() && !append && !overwrite) {
            return@Tool errEnvelope(
                "file_exists",
                "file '$path' already exists. Pass overwrite=true to truncate, or append=true to append."
            )
        }
        if (file.exists() && file.isDirectory) {
            return@Tool errEnvelope(
                "is_directory",
                "path '$path' is a directory, not a file"
            )
        }

        // Don't auto-create deep dir trees the user didn't ask for. If the immediate
        // parent doesn't exist, return a structured error pointing at create_directory.
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            return@Tool errEnvelope(
                "parent_missing",
                "parent directory '${parent.path}' does not exist. Call create_directory first."
            )
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val priorSize = if (file.exists()) file.length() else 0L

        return@Tool try {
            if (append) {
                file.appendBytes(bytes)
            } else {
                // Atomic truncate/overwrite: write to a temp file in the SAME directory then
                // rename over the target, so an interrupted write can't leave the file half
                // written (or empty). Same-dir keeps the rename on one filesystem.
                val tmp = File(file.parentFile, "${file.name}.rkwr-${System.nanoTime()}")
                // The temp file must never outlive this block: if writeBytes throws mid-write
                // (disk full, IO error) the partial temp would otherwise be orphaned on disk,
                // so delete it on EVERY failure path before propagating the error.
                try {
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) {
                        // Some filesystems won't rename onto an existing target; delete + retry.
                        file.delete()
                        if (!tmp.renameTo(file)) {
                            tmp.delete()
                            return@Tool errEnvelope("write_failed", "could not place written file at '$path'")
                        }
                    }
                } catch (e: Throwable) {
                    tmp.delete()
                    throw e
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("path", file.absolutePath)
                put("bytes_written", bytes.size)
                put("appended", append)
                put("prior_size", priorSize)
                put("new_size", file.length())
            }.toString()))
        } catch (e: Throwable) {
            errEnvelope("write_failed", "${e::class.simpleName}: ${e.message ?: "unknown"}")
        }
    }
)

private fun errEnvelope(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()))

internal fun shouldAutoCreateParent(rawPath: String, expandedPath: String): Boolean =
    rawPath.startsWith("~/") ||
        expandedPath.startsWith("/sdcard/Documents/RikkaHub/") ||
        expandedPath.startsWith("/sdcard/Download/RikkaHub/") ||
        expandedPath.startsWith("/sdcard/Pictures/RikkaHub/") ||
        expandedPath.startsWith("/storage/emulated/0/Documents/RikkaHub/") ||
        expandedPath.startsWith("/storage/emulated/0/Download/RikkaHub/") ||
        expandedPath.startsWith("/storage/emulated/0/Pictures/RikkaHub/")
