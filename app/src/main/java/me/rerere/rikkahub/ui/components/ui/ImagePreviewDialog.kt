package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

import android.util.Log
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val savingText = stringResource(R.string.setting_files_page_saving)
    val savedText = stringResource(R.string.setting_files_page_save_success)
    val saveDescription = stringResource(R.string.setting_files_page_save)
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var saveStatusIsError by remember { mutableStateOf(false) }
    var saveStatusVersion by remember { mutableIntStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(saveStatusVersion) {
        if (saveStatusMessage != null) {
            delay(2000)
            saveStatusMessage = null
            saveStatusIsError = false
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val request = remember(images[index]) {
                        ImageRequest.Builder(context)
                            .data(images[index])
                            .allowHardware(false)
                            .build()
                    }
                    val painter = rememberAsyncImagePainter(request)
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            // Inline status message above the button row
            val statusMsg = saveStatusMessage
            if (statusMsg != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(3f)
                        .padding(bottom = 56.dp, start = 16.dp, end = 16.dp)
                        .background(
                            color = if (saveStatusIsError) Color.Red.copy(alpha = 0.85f) else Color.Green.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = statusMsg,
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    enabled = !isSaving,
                    onClick = {
                        isSaving = true
                        saveStatusMessage = savingText
                        saveStatusIsError = false
                        saveStatusVersion++
                        scope.launch {
                            runCatching {
                                val imgUrl = images[state.currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                                saveStatusMessage = null
                                onDismissRequest()
                                delay(300)
                                toaster.show(message = savedText, type = ToastType.Success)
                            }.onFailure {
                                Log.e("ImagePreviewDialog", "Failed to save image", it)
                                saveStatusMessage = it.message ?: it.toString()
                                saveStatusIsError = true
                            }
                            isSaving = false
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, saveDescription, tint = Color.White)
                }
            }
        }
    }
}
