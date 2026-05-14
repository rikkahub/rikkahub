package me.rerere.rikkahub.data.ai.tools.local

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

private val WALLPAPER_TARGETS = setOf("home", "lock", "both")

/** Structural validation shared with the unit test. Returns null when ok. */
internal fun validateWallpaperArgs(fileUri: String?, target: String): String? = when {
    fileUri.isNullOrBlank() -> "file_uri is required"
    !fileUri.startsWith("file://") -> "file_uri must start with file://"
    target !in WALLPAPER_TARGETS -> "target must be one of home, lock, both"
    else -> null
}

private fun wallpaperErr(msg: String) =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString()))

/**
 * `set_wallpaper` — set the home and/or lock screen wallpaper from a local image file.
 * Decodes the image with BitmapFactory and applies it through WallpaperManager with the
 * appropriate FLAG_SYSTEM / FLAG_LOCK flags.
 */
fun setWallpaperTool(context: Context): Tool = Tool(
    name = "set_wallpaper",
    description = """
        Set the device wallpaper from a local image file (file:// URI). target selects
        which surface to change: "home", "lock", or "both" (default). Returns {success}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("file_uri", buildJsonObject {
                    put("type", "string")
                    put("description", "file:// URI of a readable image")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "home, lock, or both (default both)")
                })
            },
            required = listOf("file_uri"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val fileUri = obj["file_uri"]?.jsonPrimitive?.contentOrNull
        val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "both"
        validateWallpaperArgs(fileUri, target)?.let { return@Tool wallpaperErr(it) }

        val path = fileUri!!.removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return@Tool wallpaperErr("file not found: $path")
        }
        val bitmap = try {
            BitmapFactory.decodeFile(path)
        } catch (_: Throwable) {
            null
        } ?: return@Tool wallpaperErr("file is not a readable image")

        val wm = try {
            WallpaperManager.getInstance(context)
        } catch (_: Throwable) {
            null
        } ?: return@Tool wallpaperErr("feature unavailable")

        return@Tool try {
            val flags = when (target) {
                "home" -> WallpaperManager.FLAG_SYSTEM
                "lock" -> WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            wm.setBitmap(bitmap, null, true, flags)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
            }.toString()))
        } catch (e: Throwable) {
            wallpaperErr("set_failed: ${e.message ?: e::class.simpleName}")
        }
    },
)
