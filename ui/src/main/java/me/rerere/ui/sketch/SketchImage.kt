package me.rerere.ui.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.exifinterface.media.ExifInterface
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as BitmapCanvas

private const val TAG = "SketchImage"

// 底图的长边上限，读进来时就缩到这个范围里，导出时按底图的分辨率出图
private const val MAX_IMAGE_SIZE = 2048

// 空白画纸导出图片的长边：和屏幕的分辨率无关，不同设备上画出来的图一样大
private const val BLANK_EXPORT_SIZE = 1536f

private const val JPEG_QUALITY = 92

/**
 * 画板确认后的结果。
 */
class SketchResult internal constructor(
    val bitmap: Bitmap,
    // 画在图片上时存成 JPEG 控制体积；纯线条的草图存成 PNG，边缘才不会糊
    private val photo: Boolean,
) {
    val extension: String get() = if (photo) "jpg" else "png"
    val mimeType: String get() = if (photo) "image/jpeg" else "image/png"

    /** 编码成 [mimeType] 的文件内容。比较慢，不要在主线程上调用。 */
    fun encode(): ByteArray = ByteArrayOutputStream().use {
        if (photo) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        it.toByteArray()
    }
}

/**
 * 把一张图片读成底图：按 EXIF 摆正，太大的缩小。读不出来时返回 null。
 */
internal suspend fun loadSketchBackground(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIZE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Failed to decode $uri")
        decoded.upright(context, uri).asImageBitmap()
    }.onFailure {
        Log.e(TAG, "Failed to load $uri", it)
    }.getOrNull()
}

// 照片的方向常常只记在 EXIF 里，像素本身是横着或者镜像的
private fun Bitmap.upright(context: Context, uri: Uri): Bitmap {
    val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return this
    if (exif.rotationDegrees == 0 && !exif.isFlipped) return this
    // 约定是先水平翻转再旋转
    val matrix = Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        postRotate(exif.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** 把画纸导出成图片。 */
internal fun SketchState.toResult(): SketchResult {
    val background = background
    val scale = if (background != null) {
        background.width / paperSize.width
    } else {
        BLANK_EXPORT_SIZE / paperSize.maxDimension
    }
    val width = (paperSize.width * scale).roundToInt().coerceAtLeast(1)
    val height = (paperSize.height * scale).roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val renderer = CanvasStrokeRenderer.create()
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = BitmapCanvas(bitmap),
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        drawSketch(this@toResult, scale, renderer)
    }
    return SketchResult(bitmap.asAndroidBitmap(), photo = background != null)
}
