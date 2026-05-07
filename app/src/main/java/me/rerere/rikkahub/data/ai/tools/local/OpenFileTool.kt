package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * `open_file` — fire ACTION_VIEW to open a file in the user's preferred OS viewer
 * (Gallery, PDF reader, audio player, text editor, etc.). The user lands on the
 * file in the destination app and can read / edit / share from there.
 *
 * For files in the agent's private workspace (`~`) or app cache, we route through
 * FileProvider so the destination app gets a content:// URI it can actually read
 * across the sandbox boundary. For public-storage paths (/sdcard/...) we use a
 * direct file:// URI which the OS handles.
 *
 * Approval-gated (in [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.ALWAYS_ASK])
 * because launching arbitrary apps on a user-supplied path is a privilege surface —
 * same reasoning as `launch_app`.
 */
fun openFileTool(context: Context): Tool = Tool(
    name = "open_file",
    description = """
        Open a file in the user's preferred OS viewer (Gallery for images, PDF reader for
        PDFs, music player for audio, etc.). Backgrounds RikkaHub momentarily; the user
        lands on the file in the destination app. Use this when the user wants to
        actually read / edit / share a file rather than just see it inline. Path accepts
        ~ for the workspace (e.g. ~/learnings/ERRORS.md) or any absolute path under
        PathSafetyGuard's allowlist. Optional mime_type forces a specific viewer when the
        file extension is ambiguous.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path or ~/... to the file")
                })
                put("mime_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override — defaults to the OS guess from the file extension")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "missing_path"); put("detail", "path is required") }.toString()
            ))
        }
        val path = AgentWorkspace.expand(rawPath)
        PathSafetyGuard.check(path)?.let { v ->
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(v.code, v.detail)))
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("not_found", "File not found: $rawPath")))
        }
        val mimeOverride = params["mime_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mime = mimeOverride
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"

        // Public-storage paths get a plain file:// URI; private (workspace / cache /
        // app filesDir) need a FileProvider content:// URI so the destination app can
        // actually open them across the sandbox boundary.
        val absolute = file.absolutePath
        val isPublicStorage = absolute.startsWith("/storage/") || absolute.startsWith("/sdcard/")
        val uri = if (isPublicStorage) {
            android.net.Uri.fromFile(file)
        } else {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrElse {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "fileprovider_failed",
                    "Could not expose file via FileProvider: ${it.message ?: it::class.simpleName}",
                )))
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                "no_handler",
                "No installed app can open files of type '$mime': ${it.message ?: it::class.simpleName}",
            )))
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("path", absolute)
            put("mime", mime)
        }.toString()))
    },
)
