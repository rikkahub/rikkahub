package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
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
 * `show_image` — display an image file inline in the chat (in-app or Telegram).
 *
 * Closes the gap where the LLM could enumerate / read files but couldn't *show* the
 * user a picture from disk. Returns a `UIMessagePart.Image(url = "file://...")` plus a
 * structured envelope with width/height/size for the LLM's own context.
 *
 * In-app chat:  the file:// URL renders inline in the assistant's message bubble.
 * Telegram bot: the existing reply pump in handleIncoming surfaces image parts to
 *               Telegram as photos automatically.
 *
 * Path arg accepts:
 *  - `~/foo.png` (workspace tilde-expanded by [AgentWorkspace])
 *  - `/sdcard/Pictures/...` (any user-visible path)
 *  - any path PathSafetyGuard allows
 *
 * No approval required — same risk profile as `take_screenshot`, which is also
 * auto-approved (the LLM can already see arbitrary screen content; reading a file
 * the file tools can already read isn't a privilege escalation).
 */
fun showImageTool(@Suppress("UNUSED_PARAMETER") context: Context): Tool = Tool(
    name = "show_image",
    description = """
        Display an image file inline in the chat so the user actually sees it. Use this
        when the user asks to see / preview an image at a known path on the device, after
        you've located one via list_files / find_files, or after take_photo / take_screenshot
        wrote a file you want to surface explicitly. Path accepts ~ for the agent workspace
        (e.g. ~/screenshots/x.png) or any absolute path under PathSafetyGuard's allowlist.
        Supported: PNG, JPEG, WebP, GIF, BMP. Returns the image as an attachment plus a
        small JSON envelope with dimensions and size.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path or ~/... to an image file")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val rawPath = input.jsonObject["path"]?.jsonPrimitive?.contentOrNull
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
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
        if (!mime.startsWith("image/")) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                "not_an_image",
                "File extension '$ext' is not a recognised image type — supported: png, jpg, jpeg, webp, gif, bmp",
            )))
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val envelope = buildJsonObject {
            put("success", true)
            put("path", file.absolutePath)
            put("mime", mime)
            put("size_bytes", file.length())
            put("width", opts.outWidth)
            put("height", opts.outHeight)
        }.toString()
        listOf(
            UIMessagePart.Image(url = "file://${file.absolutePath}"),
            UIMessagePart.Text(envelope),
        )
    },
)
