@file:Suppress("unused")

package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.text.Charsets.ISO_8859_1

/**
 * 图片处理工具类
 * 提供图片压缩、旋转修正、二维码解析等功能
 */
object ImageUtils {

    /**
     * 优化的图片加载方法，避免OOM
     * 1. 先获取图片尺寸
     * 2. 计算合适的采样率
     * 3. 加载压缩后的图片
     * 4. 处理图片旋转
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 压缩后的Bitmap，失败返回null
     */
    fun loadOptimizedBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): Bitmap? {
        return runCatching {
            // 第一步：获取图片的原始尺寸，不加载到内存
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 计算合适的采样率
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

            // 第二步：使用采样率加载压缩后的图片
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 使用RGB_565减少内存占用
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, loadOptions)
            }

            // 第三步：处理图片旋转（如果需要）
            bitmap?.let { correctImageOrientation(context, uri, it) }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 计算合适的采样率
     *
     * @param options BitmapFactory.Options包含原始图片尺寸信息
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（2的幂）
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的inSampleSize值，该值是2的幂，并且保持高度和宽度都大于请求的高度和宽度
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 修正图片旋转
     * 根据EXIF信息自动旋转图片到正确方向
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param bitmap 原始bitmap
     * @return 旋转后的bitmap
     */
    fun correctImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap // 不需要旋转
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle() // 回收原始bitmap
            }
            rotatedBitmap
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(bitmap)
    }

    /**
     * 从图片中解析二维码
     *
     * @param bitmap 要解析的图片
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)

            result.text
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 从URI加载图片并解析二维码（组合方法）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromUri(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): String? {
        val bitmap = loadOptimizedBitmap(context, uri, maxSize) ?: return null
        return try {
            decodeQRCodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle() // 确保释放内存
        }
    }

    /**
     * 安全地回收Bitmap内存
     *
     * @param bitmap 要回收的bitmap
     */
    fun recycleBitmapSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    /**
     * 获取图片的基本信息（不加载到内存）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return ImageInfo包含宽度、高度、MIME类型等信息
     */
    fun getImageInfo(context: Context, uri: Uri): ImageInfo? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageInfo(
                    width = options.outWidth,
                    height = options.outHeight,
                    mimeType = options.outMimeType
                )
            } else null
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 获取酒馆角色卡中的角色元数据（如果存在）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return Result<String> 包含角色元数据的Result对象
     */
    fun getTavernCharacterMeta(context: Context, uri: Uri): Result<String> = runCatching {
        val pngBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Failed to read image data")
        extractTavernCharacterMetaFromPngBytes(pngBytes)
    }

    internal fun extractTavernCharacterMetaFromPngBytes(bytes: ByteArray): String {
        require(bytes.size >= PNG_SIGNATURE.size) { "Invalid PNG data" }
        require(bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "No PNG signature found, please check if the image is a character card"
        }

        var offset = PNG_SIGNATURE.size
        var legacyEncoded: String? = null
        val textChunks = linkedMapOf<String, String>()

        while (offset + 12 <= bytes.size) {
            val length = readPngInt(bytes, offset)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            require(length >= 0 && dataEnd + 4 <= bytes.size) { "Invalid PNG chunk length" }

            val chunkType = bytes.decodeToString(offset + 4, offset + 8)
            if (chunkType == "tEXt") {
                parsePngTextChunk(bytes.copyOfRange(dataStart, dataEnd))?.let { (keyword, text) ->
                    val normalizedKeyword = keyword.trim().lowercase()
                    if (normalizedKeyword.isNotBlank()) {
                        textChunks[normalizedKeyword] = text
                    }
                    if (legacyEncoded == null) {
                        legacyEncoded = LEGACY_CHARA_REGEX.find(text)?.groupValues?.get(1)
                    }
                }
            }

            offset = dataEnd + 4
            if (chunkType == "IEND") {
                break
            }
        }

        return textChunks["ccv3"]
            ?: textChunks["chara"]
            ?: legacyEncoded
            ?: error("No character data found")
    }

    private fun readPngInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun parsePngTextChunk(bytes: ByteArray): Pair<String, String>? {
        val separatorIndex = bytes.indexOf(0)
        if (separatorIndex <= 0) return null
        val keyword = bytes.decodeToString(endIndex = separatorIndex)
        val text = bytes.decodeToString(
            startIndex = separatorIndex + 1,
            endIndex = bytes.size,
            charset = ISO_8859_1,
        )
        return keyword to text
    }

    private fun ByteArray.decodeToString(
        startIndex: Int = 0,
        endIndex: Int = size,
        charset: java.nio.charset.Charset = Charsets.US_ASCII,
    ): String {
        return String(this, startIndex, endIndex - startIndex, charset)
    }

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?
    )

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private val LEGACY_CHARA_REGEX = Regex("""\[chara:\s*(.+?)]""", setOf(RegexOption.IGNORE_CASE))
}
