package me.rerere.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ui.sketch.upright
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "ImageCropImage"

// 屏幕上显示用的图的长边：原图很大时单独缩一份来画，GPU 纹理有尺寸上限
private const val PREVIEW_SIZE = 2048

internal class CropImage(val bitmap: Bitmap, val preview: ImageBitmap)

/**
 * 读出要裁剪的图片：按 EXIF 摆正，长边缩到 [maxSize] 以内。读不出来时返回 null。
 */
internal suspend fun loadCropImage(context: Context, uri: Uri, maxSize: Int): CropImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { decodeWithImageDecoder(context, uri, maxSize) }.getOrElse {
                    Log.w(TAG, "ImageDecoder failed for $uri, falling back to BitmapFactory", it)
                    decodeWithBitmapFactory(context, uri, maxSize)
                }
            } else {
                decodeWithBitmapFactory(context, uri, maxSize)
            }
            // HDR 的 HEIF 会解成 10 位或者半精度的格式，统一转成 8 位，后面才能按 JPEG 存
            val bitmap = if (decoded.config != Bitmap.Config.ARGB_8888) {
                decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
            } else {
                decoded
            }
            val longest = max(bitmap.width, bitmap.height)
            val preview = if (longest > PREVIEW_SIZE) {
                val ratio = PREVIEW_SIZE.toFloat() / longest
                bitmap.scale(
                    (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                    (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                )
            } else {
                bitmap
            }
            CropImage(bitmap, preview.asImageBitmap())
        }.onFailure {
            Log.e(TAG, "Failed to load $uri", it)
        }.getOrNull()
    }

// ImageDecoder 自己会按 EXIF 摆正，也能解 HEIF
@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        // 要在 CPU 上裁剪和编码，不能用硬件位图
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val longest = max(width, height)
        if (longest > maxSize) {
            val ratio = maxSize.toFloat() / longest
            decoder.setTargetSize(
                (width * ratio).roundToInt().coerceAtLeast(1),
                (height * ratio).roundToInt().coerceAtLeast(1),
            )
        }
    }
}

private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maxSize) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Failed to decode $uri")
    return decoded.upright(context, uri)
}
