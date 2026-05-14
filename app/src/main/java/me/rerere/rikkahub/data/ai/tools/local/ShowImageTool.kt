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
 *
 * [modelCanSeeImages] gates what the LLM is told in the result envelope. The
 * `UIMessagePart.Image` is always returned (it's the *user's* display — the in-app bubble
 * or the Telegram photo). But when the active model has no image input modality, the text
 * envelope says so plainly: a text-only model handed `width`/`height`/`success:true` with
 * no caveat treats it as "I looked at it" and confabulates a description. Telling it
 * `visible_to_you: false` + an OCR pointer kills that at the source.
 */
fun showImageTool(
    @Suppress("UNUSED_PARAMETER") context: Context,
    modelCanSeeImages: Boolean = true,
): Tool = Tool(
    name = "show_image",
    description = "Display an image file inline in the chat (Telegram: sends as a real photo). Use when the user wants to see an image at a known path, or after take_photo/take_screenshot to surface the result. Path accepts ~ or absolute. Supported: PNG, JPEG, WebP, GIF, BMP.",
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
        val envelope = buildShowImageEnvelope(
            path = file.absolutePath,
            mime = mime,
            sizeBytes = file.length(),
            width = opts.outWidth,
            height = opts.outHeight,
            modelCanSeeImages = modelCanSeeImages,
        )
        listOf(
            UIMessagePart.Image(url = "file://${file.absolutePath}"),
            UIMessagePart.Text(envelope),
        )
    },
)

/**
 * Builds the `show_image` result envelope (the text part the LLM reads). Extracted as a
 * pure function so it's unit-testable without Android's BitmapFactory.
 *
 * Vision models get the envelope unchanged — `success` + the file metadata — because they
 * see the `UIMessagePart.Image` directly. Text-only models additionally get
 * `visible_to_you: false` and a `note` pointing them at OCR, so they don't confabulate a
 * description from `width`/`height` alone.
 */
internal fun buildShowImageEnvelope(
    path: String,
    mime: String,
    sizeBytes: Long,
    width: Int,
    height: Int,
    modelCanSeeImages: Boolean,
): String = buildJsonObject {
    put("success", true)
    put("path", path)
    put("mime", mime)
    put("size_bytes", sizeBytes)
    put("width", width)
    put("height", height)
    if (!modelCanSeeImages) {
        put("visible_to_you", false)
        put(
            "note",
            "The image is now displayed to the user, but the current model has no " +
                "vision capability — you cannot see its contents. Do not describe or " +
                "guess what the image shows. To read it, OCR or otherwise process the " +
                "file at `path` (e.g. `tesseract <path> stdout` via termux_run_command).",
        )
    }
}.toString()
