package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val PICTURES_SUBDIR = "RikkaHub"

fun cameraPhotoTool(context: Context, buffer: CameraResultBuffer): Tool = Tool(
    name = "take_photo",
    description = """
        Open the system camera so the user can take a photo. The captured image is saved to the
        device's Pictures/RikkaHub folder and is returned to you as a visible image attachment
        so you can see what was photographed. The user must explicitly take the photo.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        if (!PermissionHelper.hasRuntime(context, listOf(Manifest.permission.CAMERA))) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "permission CAMERA not granted") }.toString()
                )
            )
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "RikkaHub_${timestamp}_${UUID.randomUUID().toString().take(8)}.jpg"

        val useMediaStore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val mediaStoreUri: Uri?
        val cacheFile: File?
        val outputUri: Uri

        if (useMediaStore) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$PICTURES_SUBDIR"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val inserted = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
            if (inserted == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "failed to create MediaStore entry") }.toString()
                    )
                )
            }
            mediaStoreUri = inserted
            cacheFile = null
            outputUri = inserted
        } else {
            val cacheDir = File(context.cacheDir, "photos").apply { mkdirs() }
            val tmp = File(cacheDir, displayName)
            mediaStoreUri = null
            cacheFile = tmp
            outputUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tmp
            )
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)

        val intent = Intent(context, ToolHostActivity::class.java).apply {
            putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_CAMERA)
            putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(ToolHostActivity.EXTRA_OUTPUT_URI, outputUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        context.startActivity(intent)

        val captureUri = withTimeoutOrNull(300_000L) { deferred.await() }
        if (captureUri == null) {
            buffer.complete(requestId, null)
            // Roll back the pending MediaStore row so it doesn't show up as a 0-byte file.
            mediaStoreUri?.let { context.contentResolver.delete(it, null, null) }
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "user_cancelled") }.toString()
                )
            )
        }

        // Finalize the MediaStore row (clear IS_PENDING) so the gallery sees the photo.
        if (useMediaStore && mediaStoreUri != null) {
            val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(mediaStoreUri, update, null, null)
        }

        // Copy the bytes into our own cache so the AI's encodeBase64 (which only understands
        // file://, data:, http) can always read it. MediaStore content URIs and DATA-column
        // file paths are unreliable on Android 11+ scoped storage.
        val llmCacheDir = File(context.cacheDir, "photos").apply { mkdirs() }
        val llmCacheFile = File(llmCacheDir, displayName)
        val sourceUri: Uri = mediaStoreUri ?: outputUri
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                llmCacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                // Fallback: if the source URI can't be opened but we have the cache file path
                // (legacy 26-28 path), the file is already where we want it.
                if (cacheFile != null && cacheFile.exists()) {
                    cacheFile.copyTo(llmCacheFile, overwrite = true)
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: fall through with whatever we have. Dimensions may fail; tool still
            // returns a sensible JSON envelope.
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(llmCacheFile.absolutePath, opts)

        val galleryPath: String? = if (useMediaStore && mediaStoreUri != null) {
            context.contentResolver.query(
                mediaStoreUri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else {
            cacheFile?.absolutePath
        }

        val payload = buildJsonObject {
            put("success", true)
            put("gallery_path", galleryPath ?: "(unknown)")
            put("width", opts.outWidth)
            put("height", opts.outHeight)
            put("saved_to", "Pictures/$PICTURES_SUBDIR")
        }

        // Image first so it precedes the text in the LLM's view; both are part of the tool output.
        listOf(
            UIMessagePart.Image(url = "file://${llmCacheFile.absolutePath}"),
            UIMessagePart.Text(payload.toString()),
        )
    }
)
