package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.io.File
import java.io.FileOutputStream

private const val SCREENSHOT_DIR = "screenshots"
private const val PRUNE_OLDER_THAN_MS = 60L * 60L * 1000L  // 1 hour

private fun pruneOldScreenshots(dir: File) {
    val cutoff = System.currentTimeMillis() - PRUNE_OLDER_THAN_MS
    dir.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) {
            f.delete()
        }
    }
}

fun takeScreenshotTool(context: Context): Tool = Tool(
    name = "take_screenshot",
    description = """
        Capture a screenshot of the device's current display via the AccessibilityService and
        return it as an image attachment so you can see exactly what is on screen. The image
        is delivered to your next turn as a vision input - inspect it before deciding the
        next tap or swipe. Saves a PNG to the app cache. Secure surfaces (banking apps, DRM
        video, password fields) come back as a graceful error. Rate-limited by the OS to ~1
        per second.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id to capture (default 0)")
                })
            }
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull ?: 0

        val outcome = AccessibilityServiceHandle.withService { svc ->
            val cacheDir = File(context.cacheDir, SCREENSHOT_DIR).apply { mkdirs() }
            pruneOldScreenshots(cacheDir)
            val res = svc.captureScreenshot(displayId)
            when (res) {
                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                    svc.appendLog(
                        ActionLogEntry(
                            type = "take_screenshot",
                            paramsSummary = "fail:${res.reason}",
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                    buildJsonObject {
                        put("error", "screenshot_unavailable")
                        put("reason", res.reason)
                    }
                }
                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                    val ts = System.currentTimeMillis()
                    val file = File(cacheDir, "screen-$ts.png")
                    try {
                        FileOutputStream(file).use { os ->
                            res.bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    } finally {
                        res.bitmap.recycle()
                    }
                    svc.appendLog(
                        ActionLogEntry(
                            type = "take_screenshot",
                            paramsSummary = "ok ${file.length() / 1024}KB display=$displayId",
                            success = true,
                            timestampMs = ts,
                        )
                    )
                    buildJsonObject {
                        put("success", true)
                        put("file_path", file.absolutePath)
                    }
                }
            }
        }

        val parts = mutableListOf<UIMessagePart>()
        // If there's a saved file path, attach it as an Image part for the LLM to see.
        outcome.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(outcome.toString()))
        parts
    }
)
