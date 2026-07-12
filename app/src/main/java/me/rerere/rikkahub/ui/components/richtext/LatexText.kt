package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.takeOrElse
import io.ratex.RaTeXEngine
import io.ratex.compose.RaTeX
import io.ratex.measure

/**
 * 尺寸信息，替代原先 JLatexMath 的 Rect。
 * [depth] 用于 InlineTextContent 基线对齐。
 */
data class LatexMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val depthPx: Float,
)

/**
 * 同步计算公式尺寸，用于 [InlineTextContent] 的 Placeholder 预占位。
 * 内部调用 [RaTeXEngine.parseBlocking] 阻塞解析。
 */
fun assumeLatexSize(
    latex: String,
    fontSizePx: Float,
    displayMode: Boolean = false,
): LatexMetrics? {
    return runCatching {
        val dl = RaTeXEngine.parseBlocking(latex, displayMode = displayMode)
        val m = dl.measure(fontSizePx)
        LatexMetrics(m.widthPx, m.heightPx, m.depthPx)
    }.getOrNull()
}

/**
 * 用 RaTeX 引擎渲染 LaTeX 公式的 Composable。
 *
 * 签名兼容旧版 [LatexText]，新增 [displayMode] 参数供块级/行内选择。
 * 渲染失败时退化到纯文本显示原始 LaTeX。
 */
@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    displayMode: Boolean = false,
) {
    val resolvedColor = if (color == Color.Unspecified) style.color else color
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize }

    val displayList = remember(latex, displayMode, resolvedColor) {
        runCatching {
            RaTeXEngine.parseBlocking(latex, displayMode = displayMode, color = resolvedColor)
        }.getOrNull()
    }

    if (displayList != null) {
        RaTeX(
            displayList = displayList,
            modifier = modifier,
            fontSize = resolvedFontSize,
        )
    } else {
        // 降级：显示原始 LaTeX 文本
        Text(
            text = latex,
            style = style.merge(color = resolvedColor, fontSize = resolvedFontSize),
            modifier = modifier,
        )
    }
}
