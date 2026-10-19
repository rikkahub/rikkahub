package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import me.rerere.ui.toast.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
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
    initialPage: Int = 0,
    // 底部操作栏里额外的按钮，参数是当前页
    actions: @Composable RowScope.(page: Int) -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val filesManager: FilesManager = koinInject()
    val state = rememberZoomablePagerState(initialPage = initialPage) { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index])
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            // 保存完成前一直显示，结束后收起，避免和结果提示叠在一起
                            val saving = toaster.showProgress(resources.getString(R.string.image_preview_saving))
                            runCatching {
                                val imgUrl = images[state.currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                            }.onSuccess {
                                saving.dismiss()
                                toaster.show(message = resources.getString(R.string.image_preview_saved), type = ToastType.Success)
                            }.onFailure {
                                saving.dismiss()
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toString(),
                                    type = ToastType.Error
                                )
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, null, tint = Color.White)
                }
                actions(state.currentPage)
            }
        }
    }
}
