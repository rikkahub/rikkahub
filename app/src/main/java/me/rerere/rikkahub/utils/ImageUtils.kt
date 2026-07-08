@file:Suppress("unused")

package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.net.toFile
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.png.PngChunkType
import com.drew.metadata.png.PngDirectory
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

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
     * @param config 解码时的 Bitmap 配置，PNG 需 ARGB_8888 保留透明度，JPEG 可用 RGB_565 省内存
     * @return 压缩后的Bitmap，失败返回null
     */
    fun loadOptimizedBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024,
        config: Bitmap.Config = Bitmap.Config.RGB_565
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
                inPreferredConfig = config
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, loadOptions)
            }

            // 第三步：处理图片旋转（如果需要）
            val oriented = bitmap?.let { correctImageOrientation(context, uri, it) }

            // 第四步：精确缩放到目标尺寸（弥补 inSampleSize 只能 2ⁿ 采样的限制）
            oriented?.let { bmp ->
                val longSide = maxOf(bmp.width, bmp.height)
                if (longSide > maxSize) {
                    val ratio = maxSize.toFloat() / longSide
                    val sw = (bmp.width * ratio).toInt()
                    val sh = (bmp.height * ratio).toInt()
                    Bitmap.createScaledBitmap(bmp, sw, sh, true)
                        .also { if (it != bmp) bmp.recycle() }
                } else bmp
            }
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
     * 判断 uri 指向的图片是否为 HEIF/HEIC 格式
     * 优先使用 ContentResolver 的 MIME 类型，回退到读取文件头魔数嗅探
     */
    fun isHeifImage(context: Context, uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            if (mime.contains("heic") || mime.contains("heif")) return true
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 12) return@use false
                if (header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return@use false
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in HEIF_BRANDS
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 将 HEIF/HEIC 图片解码后重编码为 JPEG 写入 [target]
     * 用于规避部分组件（如 UCrop）对 HEIF（尤其 HDR HEIF）解码兼容性不佳的问题
     *
     * @return 转换成功返回 true，失败（如系统无法解码 HEIF）返回 false
     */
    fun convertHeifToJpeg(
        context: Context,
        uri: Uri,
        target: File,
        maxSize: Int = 4096,
        quality: Int = 95,
    ): Boolean = runCatching {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOptions, maxSize, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, loadOptions)
        } ?: return@runCatching false
        // 将 EXIF 旋转烘焙进像素，输出的 JPEG 不再带方向信息，避免下游二次旋转
        val oriented = correctImageOrientation(context, uri, decoded)
        try {
            target.outputStream().use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            true
        } finally {
            recycleBitmapSafely(oriented)
        }
    }.onFailure {
        it.printStackTrace()
    }.getOrDefault(false)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis",
        "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif",
    )

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
     * 压缩图片到指定长边尺寸，消耗于调用线程（应在 IO 线程执行）
     *
     * JPEG 以 quality 80 重编码以降低体积，
     * PNG 仅降分辨率（保持透明通道），
     * 当压缩结果不小于原体积时保留原文件。
     *
     * @param context Android 上下文
     * @param uri 本地 file:// URI，指向 [FilesManager] 管理的文件
     * @param maxDimension 压缩后最长边的像素值
     * @return 压缩后的文件字节数，失败或未缩小则返回 null
     */
    fun compressImage(context: Context, uri: Uri, maxDimension: Int): Long? {
        return runCatching {
            val info = getImageInfo(context, uri) ?: return@runCatching null
            val mimeType = info.mimeType ?: "image/jpeg"
            val format = when (mimeType) {
                "image/png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            val quality = if (format == Bitmap.CompressFormat.JPEG) 80 else 100
            val config = if (format == Bitmap.CompressFormat.PNG)
                Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

            val bitmap = loadOptimizedBitmap(context, uri, maxDimension, config) ?: return@runCatching null
            val file = uri.toFile()
            val originalSize = file.length()

            val compressed = ByteArrayOutputStream().use { output ->
                bitmap.compress(format, quality, output)
                output.toByteArray()
            }
            bitmap.recycle()
            if (compressed.size >= originalSize) return@runCatching null
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.writeBytes(compressed)
                if (tmp.renameTo(file)) file.length() else null
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }.onFailure { it.printStackTrace() }.getOrNull()
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
        val metadata = context.contentResolver.openInputStream(uri)?.use { ImageMetadataReader.readMetadata(it) }
        if (metadata == null) error("Metadata is null, please check if the image is a character card")
        if (!metadata.containsDirectoryOfType(PngDirectory::class.java)) error("No PNG directory found, please check if the image is a character card")

        val pngDirectory = metadata.getDirectoriesOfType(PngDirectory::class.java)
            .firstOrNull { directory ->
                directory.pngChunkType == PngChunkType.tEXt
                    && directory.getString(PngDirectory.TAG_TEXTUAL_DATA).startsWith("[chara:")
            } ?: error("No tEXt chunk found, please check if the image is a character card")

        val value = pngDirectory.getString(PngDirectory.TAG_TEXTUAL_DATA)

        val regex = Regex("""\[chara:\s*(.+?)]""")
        return Result.success(regex.find(value)?.groupValues?.get(1) ?: error("No character data found"))
    }

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?
    )
}
