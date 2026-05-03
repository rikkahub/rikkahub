package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * Writes arbitrary text content to a file in the device's public Downloads folder so the
 * user can find it in their file manager / Files app afterward.
 *
 * Android 10+: uses MediaStore (scoped storage, no permission needed for the app's own writes).
 * Android 26-28: writes directly to Environment.DIRECTORY_DOWNLOADS — note this technically
 * needs WRITE_EXTERNAL_STORAGE on those versions which we do NOT declare; the legacy path
 * exists as a best-effort fallback that will fail gracefully on those few devices.
 */
fun writeTextFileTool(context: Context): Tool = Tool(
    name = "write_text_file",
    description = """
        Save arbitrary text content to a file in the device's public Downloads folder. Use this
        when the user asks you to save notes, jokes, transcripts, lists, scripts, or any other
        text content as a file they can find later. Returns the absolute path of the written file.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Filename including extension (e.g. \"jokes.md\", \"notes.txt\")")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full text content to write to the file")
                })
                put("mime_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional MIME type. Defaults to text/plain; use text/markdown for .md, application/json for .json, etc.")
                })
            },
            required = listOf("filename", "content")
        )
    },
    execute = {
        val params = it.jsonObject
        val rawName = params["filename"]?.jsonPrimitive?.contentOrNull
            ?: error("filename is required")
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: error("content is required")
        val mime = params["mime_type"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?: guessMime(rawName)

        // Sanitize: strip path separators and leading dots so a model-supplied "../../foo"
        // becomes plain "foo" instead of escaping the target directory.
        val filename = rawName.substringAfterLast('/').trimStart('.').ifEmpty {
            "rikkahub_${System.currentTimeMillis()}.txt"
        }

        val bytes = content.toByteArray(Charsets.UTF_8)

        val payload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "failed to create Downloads entry") }.toString()
            ))
            try {
                context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            } catch (e: Throwable) {
                context.contentResolver.delete(uri, null, null)
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("error", "write failed: ${e.message ?: "unknown"}") }.toString()
                ))
            }
            val update = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, update, null, null)
            val path = context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.DATA), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            buildJsonObject {
                put("success", true)
                put("filename", filename)
                put("path", path ?: uri.toString())
                put("uri", uri.toString())
                put("bytes", bytes.size)
                put("saved_to", "Downloads")
            }
        } else {
            // Legacy 26-28 fallback. May fail without WRITE_EXTERNAL_STORAGE; we surface the error.
            try {
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                downloads.mkdirs()
                val out = File(downloads, filename)
                out.writeBytes(bytes)
                buildJsonObject {
                    put("success", true)
                    put("filename", filename)
                    put("path", out.absolutePath)
                    put("bytes", bytes.size)
                    put("saved_to", "Downloads")
                }
            } catch (e: Throwable) {
                buildJsonObject { put("error", "write failed: ${e.message ?: "unknown"}") }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun guessMime(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "csv" -> "text/csv"
    "xml" -> "text/xml"
    "yaml", "yml" -> "application/x-yaml"
    "log" -> "text/plain"
    "" -> "text/plain"
    else -> "text/plain"
}
