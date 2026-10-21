package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.crop.ImageCropDialog
import me.rerere.ui.toast.ToastType
import java.io.File

/**
 * 返回一个打开裁剪对话框的函数。裁好的图片先写成临时的 JPEG 交给 [onCroppedImageReady]，回调结束后删除；
 * 不管裁剪完成还是取消，最后都会调用 [onCleanup]。
 *
 * @param aspectRatio 固定的宽高比（宽 / 高），为空时可以自由裁剪
 */
@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Float? = null,
): (Uri) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    // 写文件期间对话框还开着，防止连点确认存出两张
    var saving by remember { mutableStateOf(false) }

    fun finish() {
        source = null
        onCleanup?.invoke()
    }

    source?.let { uri ->
        ImageCropDialog(
            image = uri,
            aspectRatio = aspectRatio,
            onDismiss = ::finish,
            onConfirm = { bitmap ->
                if (saving) return@ImageCropDialog
                saving = true
                scope.launch {
                    val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        withContext(Dispatchers.IO) {
                            outputFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        }
                        onCroppedImageReady(outputFile.toUri())
                    }.onFailure { error ->
                        Logging.log("CropLauncher", "crop failed: ${error.message} | ${error.stackTraceToString()}")
                        toaster.show(
                            "Failed to crop image: ${error.message ?: "unknown error"}",
                            type = ToastType.Error
                        )
                    }
                    outputFile.delete()
                    saving = false
                    finish()
                }
            },
        )
    }

    return { source = it }
}
