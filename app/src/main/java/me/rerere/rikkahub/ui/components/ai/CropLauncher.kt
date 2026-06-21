package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.launch
import me.rerere.common.android.appTempFolder
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit, onCleanup: (() -> Unit)? = null
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val output = cropOutputUri
        cropOutputUri = null
        if (result.resultCode == android.app.Activity.RESULT_OK && output != null) {
            // Hand the cropped OUTPUT file to the (suspending) consumer and delete it ONLY after the
            // consumer has finished reading it. Deleting synchronously raced the consumer's async copy —
            // the temp file was gone before it was read, so the chat ended up with no attached image.
            // `output` is captured per-operation, so a quick second crop can't make this delete the
            // wrong file.
            scope.launch {
                try {
                    onCroppedImageReady(output)
                } finally {
                    output.toFile().delete()
                }
            }
        } else {
            output?.toFile()?.delete()
        }
        // Source/INPUT cleanup is synchronous (NOT deferred into the coroutine): the consumer reads the
        // cropped output, never the input, so there's no read-race to avoid here — and deferring it would
        // widen a window where a quick second crop overwrites the shared source-file state and this
        // earlier cleanup then deletes the new operation's input.
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        val cropIntent = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096).getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
