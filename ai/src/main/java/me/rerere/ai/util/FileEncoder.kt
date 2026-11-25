package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            // 统一进行压缩处理
            val (encoded, outputMimeType) = file.compressAndEncode(mimeType)
            EncodedImage(
                base64 = if (withPrefix) "data:$outputMimeType;base64,$encoded" else encoded,
                mimeType = outputMimeType
            )
        }

        this.url.startsWith("data:") -> {
            // 从 data URL 提取 mime type
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(base64 = url, mimeType = mimeType)
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

// 主流提供商收到图片后也会缩到 1~2K，再大只会放大请求体：多张原图一起发时 JSON 可达上百 MB 而 OOM (#2365)。
// 只按总像素限制而不限长边，长截图保持原宽度，文字仍然清晰
private const val MAX_IMAGE_PIXELS = 4_000_000L
private const val MAX_IMAGE_DIMENSION = 10_000

private fun File.compressAndEncode(
    mimeType: String,
    maxDimension: Int = MAX_IMAGE_DIMENSION,
    maxPixels: Long = MAX_IMAGE_PIXELS,
    quality: Int = 85
): Pair<String, String> {
    // GIF 保持原样（可能是动图）
    if (mimeType == "image/gif") {
        return Pair(encodeToBase64Streaming(), mimeType)
    }

    // 历史图片每轮请求都会重新编码，命中缓存时跳过解码和 JPEG 重编码
    val cacheKey = "$absolutePath|${lastModified()}|${length()}|$maxDimension|$maxPixels|$quality"
    // 缓存文件可能在命中后、读取前被系统清理，读取失败按未命中处理，继续从原图编码
    ImageEncodeCache.get(cacheKey)
        ?.let { cached -> runCatching { cached.encodeToBase64Streaming() }.getOrNull() }
        ?.let { encoded -> return Pair(encoded, "image/jpeg") }

    val jpegBytes = compressToJpeg(maxDimension = maxDimension, maxPixels = maxPixels, quality = quality)
    ImageEncodeCache.put(cacheKey, jpegBytes)
    return Pair(Base64.encodeToString(jpegBytes, Base64.NO_WRAP), "image/jpeg")
}

private fun File.compressToJpeg(maxDimension: Int, maxPixels: Long, quality: Int): ByteArray {
    // 读取图片尺寸
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)

    val target = calculateImageTargetSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = maxDimension,
        maxPixels = maxPixels
    )
    // 先用 2 的幂次采样省解码内存，但不采到目标以下，剩下的缩放交给 createScaledBitmap
    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        targetWidth = target.width,
        targetHeight = target.height
    )
    options.inJustDecodeBounds = false

    val decoded = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    // 采样后尺寸可能和 outWidth/outHeight 的整除结果差一两像素，以实际解码结果为准
    val bitmap = if (decoded.width > target.width || decoded.height > target.height) {
        Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    } else {
        decoded
    }
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val outputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        check(normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
            "Failed to compress image: $absolutePath"
        }
        outputStream.toByteArray()
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal data class ImageSize(val width: Int, val height: Int)

/** 等比缩放到长边不超过 [maxDimension]、总像素不超过 [maxPixels]，已在限制内时保持原尺寸 */
internal fun calculateImageTargetSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): ImageSize {
    if (width <= 0 || height <= 0) return ImageSize(width, height)

    val pixelScale = sqrt(maxPixels.toDouble() / (width.toLong() * height))
    val dimensionScale = maxDimension.toDouble() / max(width, height)
    val scale = minOf(1.0, pixelScale, dimensionScale)
    if (scale >= 1.0) return ImageSize(width, height)

    return ImageSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1)
    )
}

/** 不让采样后的尺寸小于目标尺寸的最大 2 的幂次采样率 */
internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (width / (inSampleSize * 2) >= targetWidth && height / (inSampleSize * 2) >= targetHeight) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                -> return@runCatching "image/heic"

                "avif", "avis" -> return@runCatching "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
