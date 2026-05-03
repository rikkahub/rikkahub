package me.rerere.rikkahub.data.ai.tools.local

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun downloadTool(context: Context): Tool = Tool(
    name = "download_file",
    description = """
        Queue a file download via Android's DownloadManager. Files land in the public Downloads
        directory. Returns immediately with a download_id; the actual download proceeds in the
        background.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL of the file to download")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filename to save as (defaults to last URL path segment)")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull
            ?: error("url is required")
        val filenameParam = params["filename"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() }

        try {
            val uri = url.toUri()
            val rawName = filenameParam
                ?: uri.lastPathSegment
                ?: "download_${System.currentTimeMillis()}"
            val name = rawName.substringAfterLast('/').trimStart('.').ifEmpty {
                "download_${System.currentTimeMillis()}"
            }
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            val dm = context.getSystemService(DownloadManager::class.java)
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "DownloadManager unavailable") }.toString()
                    )
                )
            val id = dm.enqueue(request)
            val payload = buildJsonObject {
                put("success", true)
                put("download_id", id)
                put("filename", name)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: IllegalArgumentException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", e.message ?: "invalid argument") }.toString()
                )
            )
        } catch (e: SecurityException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", e.message ?: "security error") }.toString()
                )
            )
        }
    }
)
