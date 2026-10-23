package me.rerere.rikkahub.ui.components.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.app.appHost
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.FxState
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.layout.FxGravity
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val currentContent by rememberUpdatedState(content)
    var window: FxControl? by remember { mutableStateOf(null) }

    LaunchedEffect(window, visibility) {
        window?.setVisible(visibility)
    }

    DisposableEffect(context, tag) {
        val control = FloatingX.install(tag) {
            appHost(context.applicationContext as Application)
            anchor(FxGravity.BOTTOM_START, dx = 20f, dy = 20f)
            animation(FxAnimations.fade())
            compose {
                RikkahubTheme {
                    currentContent()
                }
            }
        }
        window = control
        control.setVisible(visibility)
        onDispose {
            control.cancel()
        }
    }
}

// 同 tag 再次 install 会 cancel 旧的 control（例如同时存在两个 Activity 实例），已 cancel 的不能再操作
private fun FxControl.setVisible(visible: Boolean) {
    if (state == FxState.CANCELLED) return
    if (visible) show() else hide()
}
