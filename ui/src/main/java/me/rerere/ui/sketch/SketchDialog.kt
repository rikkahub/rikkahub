package me.rerere.ui.sketch

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Redo
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Undo
import me.rerere.ui.R

/**
 * 全屏的画板：用手指或触控笔画一张草图，确认后得到一张图片。
 *
 * @param image 垫在下面的图片，在它上面圈画标注；为空时是一张白纸
 * @param aspectRatio 白纸的宽高比，为空时占满屏幕。之后还可以在画板里改
 */
@Composable
fun SketchDialog(
    onDismiss: () -> Unit,
    onConfirm: (SketchResult) -> Unit,
    image: Uri? = null,
    aspectRatio: Float? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SketchState(aspectRatio) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val loadFailed = stringResource(R.string.sketch_dialog_image_load_failed)
    suspend fun loadBackground(uri: Uri) {
        val background = loadSketchBackground(context, uri)
        if (background != null) {
            state.useBackground(background)
        } else {
            // 应用内的提示显示在画板这个窗口的下面，看不到，这里用系统的
            Toast.makeText(context, loadFailed, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(image) {
        if (image != null) loadBackground(image)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { loadBackground(uri) }
    }

    // 画了东西之后退出要先确认，在屏幕边缘起笔时容易误触返回手势
    fun close() {
        if (state.isBlank) onDismiss() else confirmDiscard = true
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 对话框有自己的窗口，系统栏图标的深浅要另外跟着画板的底色设置
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val lightContainer = containerColor.luminance() > 0.5f
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightContainer
                    isAppearanceLightNavigationBars = lightContainer
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 只避开系统栏和挖孔，不管键盘：写字时键盘弹出来，画纸的大小不能跟着变
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                SketchTopBar(
                    state = state,
                    onClose = { close() },
                    onChooseImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onConfirm = { onConfirm(state.toResult()) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    SketchCanvas(state = state, modifier = Modifier.fillMaxSize())
                    // 浮在画纸上面而不是挤占它的位置：画纸的尺寸不能因为调色而变
                    if (state.pickingColor) {
                        SketchColorPicker(
                            state = state,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp),
                        )
                    }
                }
                SketchToolbar(state = state)
            }
        }
    }

    if (state.writingAt != null) {
        SketchTextInput(onConfirm = state::write, onDismiss = state::cancelText)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.sketch_dialog_discard_title)) },
            text = { Text(stringResource(R.string.sketch_dialog_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

// 关闭、撤销、重做、清空、选画纸和确认
@Composable
private fun SketchTopBar(
    state: SketchState,
    onClose: () -> Unit,
    onChooseImage: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(android.R.string.cancel))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = state::undo, enabled = state.canUndo) {
            Icon(HugeIcons.Undo, contentDescription = stringResource(R.string.sketch_dialog_undo))
        }
        IconButton(onClick = state::redo, enabled = state.canRedo) {
            Icon(HugeIcons.Redo, contentDescription = stringResource(R.string.sketch_dialog_redo))
        }
        IconButton(onClick = state::clear, enabled = !state.isBlank) {
            Icon(HugeIcons.Delete02, contentDescription = stringResource(R.string.sketch_dialog_clear))
        }
        SketchPaperMenu(state = state, onChooseImage = onChooseImage)
        Spacer(Modifier.weight(1f))
        FilledIconButton(onClick = onConfirm, enabled = !state.isBlank) {
            Icon(HugeIcons.Tick02, contentDescription = stringResource(android.R.string.ok))
        }
    }
}
