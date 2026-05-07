package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SCREENSHOT_CACHE_DIR = "screenshots"
private const val PICTURES_SUBDIR = "RikkaHub/Screenshots"
private const val PRUNE_OLDER_THAN_MS = 60L * 60L * 1000L  // 1 hour — cache only

private fun pruneOldCacheScreenshots(dir: File) {
    val cutoff = System.currentTimeMillis() - PRUNE_OLDER_THAN_MS
    dir.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

fun takeScreenshotTool(context: Context): Tool = Tool(
    name = "take_screenshot",
    description = """
        Capture a screenshot of the device's current display via the AccessibilityService and
        return it as an image attachment so you can see exactly what is on screen. The PNG is
        saved to the device gallery at Pictures/RikkaHub/Screenshots/ (visible in the user's
        Gallery / Files app and to other tools like list_files), and is also kept briefly in the
        app cache so this tool's output can attach it as a vision input. The tool result's
        'gallery_path' field tells you the absolute on-device path. Secure surfaces (banking
        apps, DRM video, password fields) come back as a graceful error. Rate-limited by the OS
        to ~1 per second.
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
            val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_DIR).apply { mkdirs() }
            pruneOldCacheScreenshots(cacheDir)
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
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
                    val displayName = "Screenshot_${timestamp}.png"

                    // 1) Always write a cache copy — this is the path attached to the LLM as
                    //    inline vision (file:// uri readable by the encoder; reliable across
                    //    Android versions, regardless of MediaStore success).
                    val cacheFile = File(File(context.cacheDir, SCREENSHOT_CACHE_DIR), "screen-$ts.png")
                    try {
                        FileOutputStream(cacheFile).use { os ->
                            res.bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    } catch (t: Throwable) {
                        res.bitmap.recycle()
                        return@withService buildJsonObject {
                            put("error", "write_failed")
                            put("reason", t.message ?: t::class.simpleName ?: "unknown")
                        }
                    }

                    // 2) Save a user-visible copy to Pictures/RikkaHub/Screenshots — visible in
                    //    Gallery, the Files app, and the list_files / find_files tools.
                    val galleryPath: String? = saveToGallery(context, res.bitmap, displayName)
                    res.bitmap.recycle()

                    svc.appendLog(
                        ActionLogEntry(
                            type = "take_screenshot",
                            paramsSummary = "ok ${cacheFile.length() / 1024}KB display=$displayId" +
                                if (galleryPath != null) " gallery=$galleryPath" else " gallery=fail",
                            success = true,
                            timestampMs = ts,
                        )
                    )

                    buildJsonObject {
                        put("success", true)
                        put("file_path", cacheFile.absolutePath)
                        put("gallery_path", galleryPath ?: "(gallery_save_failed)")
                        put("saved_to", "Pictures/$PICTURES_SUBDIR")
                    }
                }
            }
        }

        val parts = mutableListOf<UIMessagePart>()
        outcome.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(outcome.toString()))
        parts
    }
)

/**
 * Persist [bitmap] as a PNG into the device gallery at Pictures/RikkaHub/Screenshots/.
 *
 * Q+ (API 29+): use MediaStore (no permission required for own-app inserts; visible to
 * the user's Gallery app via media indexing).
 *
 * Pre-Q (API 26-28): write directly to the public Pictures directory. WRITE_EXTERNAL_STORAGE
 * is granted from the manifest's pre-Q permission; if it isn't, the write throws and we
 * return null — the LLM still gets the cache copy, only the gallery copy is missing.
 *
 * Returns the absolute on-device path the user can navigate to, or null on any failure.
 */
private fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): String? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$PICTURES_SUBDIR"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }

            val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, finalize, null, null)

            // Resolve the public on-device path for the JSON envelope and ActionLog.
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "Pictures/$PICTURES_SUBDIR/$displayName"
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(pictures, PICTURES_SUBDIR).apply { mkdirs() }
            val out = File(targetDir, displayName)
            FileOutputStream(out).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            // Tell MediaScanner so the gallery picks it up promptly.
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(out.absolutePath), arrayOf("image/png"), null
            )
            out.absolutePath
        }
    }.getOrNull()
}
