package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File
import kotlin.uuid.Uuid

/**
 * 媒体创作用到的本地媒体处理：把相册里的素材导入应用目录、读取尺寸、抽取视频帧。
 * 都是阻塞调用，需要在 IO 线程使用。
 */
object MediaCreationFiles {
    data class MediaInfo(
        val width: Int,
        val height: Int,
        val durationSeconds: Double? = null,
    )

    /**
     * 解码并按 EXIF 摆正后重新编码：各家接口对 HEIC、超大尺寸和 EXIF 方向的处理不一致，统一成它们都接受的格式。
     */
    fun importImage(context: Context, uri: Uri, dir: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        // 长边缩到 MAX_IMAGE_SIZE 以内，避免超大照片解码时占满内存
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIZE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取图片")
        val bitmap = ImageUtils.correctImageOrientation(context, uri, decoded)
        try {
            // 带透明通道的图片保留 PNG，其余用 JPEG 控制体积
            val (format, extension) = if (bitmap.hasAlpha()) {
                Bitmap.CompressFormat.PNG to "png"
            } else {
                Bitmap.CompressFormat.JPEG to "jpg"
            }
            dir.mkdirs()
            return File(dir, "${Uuid.random()}.$extension").also { file ->
                file.outputStream().use { bitmap.compress(format, JPEG_QUALITY, it) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun importVideo(context: Context, uri: Uri, dir: File): File {
        val extension = when (context.contentResolver.getType(uri)?.lowercase()) {
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            else -> "mp4"
        }
        dir.mkdirs()
        val file = File(dir, "${Uuid.random()}.$extension")
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取视频")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    fun imageInfo(file: File): MediaInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MediaInfo(width = bounds.outWidth, height = bounds.outHeight)
    }

    fun videoInfo(file: File): MediaInfo? = file.withRetriever { retriever ->
        val width = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return@withRetriever null
        val height = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return@withRetriever null
        val rotated = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in listOf(90, 270)
        MediaInfo(
            width = if (rotated) height else width,
            height = if (rotated) width else height,
            durationSeconds = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { it / 1000.0 },
        )
    }

    /**
     * 把视频的第一帧或最后一帧存成 JPEG，抽不出来时返回 false。
     */
    fun extractFrame(video: File, target: File, last: Boolean): Boolean = video.withRetriever { retriever ->
        val bitmap = if (last) retriever.lastFrame() else retriever.getFrameAtTime(0)
        bitmap ?: return@withRetriever false
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    } ?: false

    /** 视频第一帧的缩略图，给没有封面文件的视频素材显示用。 */
    fun videoThumbnail(file: File, size: Int = 256): Bitmap? = file.withRetriever { retriever ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
        } else {
            retriever.getFrameAtTime(0)
        }
    }

    private fun MediaMetadataRetriever.lastFrame(): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val frameCount = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            if (frameCount != null && frameCount > 0) {
                runCatching { getFrameAtIndex(frameCount - 1) }.getOrNull()?.let { return it }
            }
        }
        val durationUs = (metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return null) * 1000L
        return getFrameAtTime(durationUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: getFrameAtTime(durationUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
    }

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

    private fun <T> File.withRetriever(block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absolutePath)
            block(retriever)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 还没下载完的结果文件带这个后缀，下载完成后改名去掉。 */
    const val PARTIAL_SUFFIX = ".part"

    private const val MAX_IMAGE_SIZE = 4096
    private const val JPEG_QUALITY = 95
}
