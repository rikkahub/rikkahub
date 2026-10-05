package me.rerere.rikkahub.ui.pages.mediacreation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.utils.getActivity
import java.io.File

internal val ImageRole.label: String
    @Composable get() = when (this) {
        ImageRole.FIRST_FRAME -> stringResource(R.string.media_creation_role_first_frame)
        ImageRole.LAST_FRAME -> stringResource(R.string.media_creation_role_last_frame)
        ImageRole.REFERENCE -> stringResource(R.string.media_creation_role_reference)
    }

internal val MediaCreationStatus.label: String
    @Composable get() = when (this) {
        MediaCreationStatus.PREPARING -> stringResource(R.string.media_creation_status_preparing)
        MediaCreationStatus.QUEUED -> stringResource(R.string.media_creation_status_queued)
        MediaCreationStatus.RUNNING -> stringResource(R.string.media_creation_status_running)
        MediaCreationStatus.DOWNLOADING -> stringResource(R.string.media_creation_status_downloading)
        MediaCreationStatus.SUCCEEDED -> stringResource(R.string.media_creation_status_succeeded)
        MediaCreationStatus.FAILED -> stringResource(R.string.media_creation_status_failed)
        MediaCreationStatus.CANCELLED -> stringResource(R.string.media_creation_status_cancelled)
    }

/** 时长的特殊取值：交给模型决定（智能时长）。 */
internal const val AUTO_DURATION = -1

/** 用户改过的参数，按「比例 · 分辨率 · 时长 · 数量」的顺序列出；都没改时为空。 */
@Composable
internal fun MediaCreationParams.summary(kind: MediaKind): List<String> = listOfNotNull(
    aspectRatio,
    resolution,
    durationSeconds?.let {
        if (it == AUTO_DURATION) {
            stringResource(R.string.media_creation_page_summary_auto_duration)
        } else {
            stringResource(R.string.media_creation_page_summary_seconds, it)
        }
    },
    count?.let {
        if (kind == MediaKind.VIDEO) {
            stringResource(R.string.media_creation_page_summary_video_count, it)
        } else {
            stringResource(R.string.media_creation_page_summary_image_count, it)
        }
    },
    generateAudio?.let {
        stringResource(
            if (it) R.string.media_creation_page_summary_audio_on else R.string.media_creation_page_summary_audio_off
        )
    },
    watermark?.let {
        stringResource(
            if (it) {
                R.string.media_creation_page_summary_watermark_on
            } else {
                R.string.media_creation_page_summary_watermark_off
            }
        )
    },
    promptEnhancement?.let {
        stringResource(
            if (it) {
                R.string.media_creation_page_param_prompt_enhancement
            } else {
                R.string.media_creation_page_summary_prompt_enhancement_off
            }
        )
    },
    seed?.let { stringResource(R.string.media_creation_page_summary_seed, it) },
)

/**
 * 各厂商常用的参数取值，只是输入时的快捷选项：模型之间的差异很大，实际能用哪些值以接口为准，
 * 列表里没有的可以手动输入。
 */
internal data class MediaCreationPresets(
    val resolutions: List<String> = emptyList(),
    val aspectRatios: List<String> = emptyList(),
    val durations: List<Int> = emptyList(),
    val counts: List<Int> = emptyList(),
) {
    /** 接口必填的参数没有设置时使用的值：各自的第一个快捷选项。 */
    val defaults: MediaCreationParams
        get() = MediaCreationParams(
            count = counts.firstOrNull(),
            resolution = resolutions.firstOrNull(),
            aspectRatio = aspectRatios.firstOrNull(),
            durationSeconds = durations.firstOrNull(),
        )
}

internal fun MediaGenerationProviderSetting.presets(kind: MediaKind): MediaCreationPresets = when (this) {
    is MediaGenerationProviderSetting.OpenAI -> MediaCreationPresets(
        resolutions = listOf("1024x1024", "1536x1024", "1024x1536"),
        counts = IMAGE_COUNTS,
    )

    is MediaGenerationProviderSetting.Volcengine -> when (kind) {
        // 各代 Seedream 的档位不同：5.0 pro / flash 是 1K、1.5K、2K，5.0 lite 是 2K、3K、4K，4.5 是 2K、4K
        MediaKind.IMAGE -> MediaCreationPresets(resolutions = listOf("1K", "1.5K", "2K", "3K", "4K"))
        // 智能时长是 Seedance 2.0 起才有的
        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480p", "720p", "1080p"),
            aspectRatios = VIDEO_ASPECT_RATIOS + "adaptive",
            durations = VIDEO_DURATIONS + AUTO_DURATION,
        )
    }

    is MediaGenerationProviderSetting.Aliyun -> when (kind) {
        MediaKind.IMAGE -> MediaCreationPresets(resolutions = listOf("1K", "2K"), counts = IMAGE_COUNTS)
        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480P", "720P", "1080P"),
            aspectRatios = VIDEO_ASPECT_RATIOS,
            durations = VIDEO_DURATIONS + AUTO_DURATION,
        )
    }

    // 分辨率、比例、时长是必填的，排在最前面的作为默认值；768P 是 H3 和 H3-Max 都支持的档位
    is MediaGenerationProviderSetting.MiniMax -> MediaCreationPresets(
        resolutions = listOf("768P", "2K", "480P"),
        aspectRatios = VIDEO_ASPECT_RATIOS + "adaptive",
        durations = VIDEO_DURATIONS,
    )

    // 背后是各家的模型，取值取的是多数模型都接受的那些
    is MediaGenerationProviderSetting.OpenRouter -> when (kind) {
        MediaKind.IMAGE -> MediaCreationPresets(
            resolutions = listOf("1K", "2K", "4K"),
            aspectRatios = listOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16"),
            counts = IMAGE_COUNTS,
        )

        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480p", "720p", "1080p", "4K"),
            aspectRatios = VIDEO_ASPECT_RATIOS,
            durations = listOf(4, 5, 6, 8, 10),
        )
    }
}

private val IMAGE_COUNTS = listOf(1, 2, 3, 4)
private val VIDEO_ASPECT_RATIOS = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
private val VIDEO_DURATIONS = listOf(5, 10)

/**
 * 素材或产出的缩略图。视频显示 [poster]，没有封面文件时从视频里取第一帧。
 */
@Composable
internal fun MediaThumbnail(
    file: File,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    poster: File? = null,
    playIconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!isVideo || poster != null) {
            AsyncImage(
                model = poster ?: file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val thumbnail by produceState<ImageBitmap?>(initialValue = null, file) {
                value = withContext(Dispatchers.IO) { MediaCreationFiles.videoThumbnail(file)?.asImageBitmap() }
            }
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (isVideo) {
            Icon(
                imageVector = HugeIcons.Play,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(playIconSize + 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(6.dp),
            )
        }
    }
}

/**
 * 把生成的图片或视频存进系统相册。
 */
internal suspend fun saveMediaToGallery(context: Context, file: File, mimeType: String) = withContext(Dispatchers.IO) {
    check(file.isFile) { context.getString(R.string.media_creation_page_file_missing) }
    val isVideo = mimeType.startsWith("video/")
    val name = "RikkaHub_${System.currentTimeMillis()}.${file.extension}"
    val directory = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: error(context.getString(R.string.media_creation_page_gallery_write_failed))
        try {
            val output = resolver.openOutputStream(uri)
                ?: error(context.getString(R.string.media_creation_page_gallery_write_failed))
            output.use { target -> file.inputStream().use { it.copyTo(target) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        // Android 9 及以下直接写公共目录，需要存储权限
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            context.getActivity()?.let { ActivityCompat.requestPermissions(it, arrayOf(permission), 1) }
            error(context.getString(R.string.media_creation_page_storage_permission_required))
        }
        val target = File(Environment.getExternalStoragePublicDirectory(directory), name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
    }
}

internal fun shareMedia(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
