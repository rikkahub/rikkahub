package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.bettertext.text.BetterSelectionContainer

private val LocalInSelectionContainer = staticCompositionLocalOf { false }

/**
 * 让 [content] 里的 BetterText 可以长按选择。
 *
 * 已经在一个选择容器里时直接并入外层，比如消息里的代码块，这样代码能和正文连着选；
 * 不在时（如更新日志里的代码块）自己建一个。不要在这里面再套 `BetterSelectionContainer`，
 * 里层的容器会把文字从外层拿走。
 */
@Composable
fun SelectableContent(content: @Composable () -> Unit) {
    if (LocalInSelectionContainer.current) {
        content()
    } else {
        BetterSelectionContainer {
            CompositionLocalProvider(LocalInSelectionContainer provides true, content)
        }
    }
}
