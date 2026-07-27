package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import io.ratex.DisplayList

/**
 * 行内公式渲染。
 * [displayList] 为可选预解析 DisplayList，由 Markdown.kt 的 INLINE_MATH 缓存提供。
 */
@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    displayList: DisplayList? = null,
) {
    LatexText(
        latex = latex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        displayMode = false,
        modifier = modifier,
        displayList = displayList,
    )
}

/**
 * 块级公式渲染，带居中 + 水平滚动。
 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    Box(
        modifier = modifier.padding(8.dp)
    ) {
        LatexText(
            latex = latex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
            displayMode = true,
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(
                    rememberScrollState()
                ),
        )
    }
}
