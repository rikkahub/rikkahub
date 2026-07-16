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
 * Compose 单边最大允许像素数 (0xFFFFFF)。
 * 超过此值的尺寸会导致 IllegalStateException: "Size out of range".
 */
private const val MAX_COMPOSE_SIZE_PX = 16_777_215f

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
 *
 * 如果 RaTeX 返回的尺寸无效（NaN、Infinity 或超出 Compose 限制），
 * 返回 null。调用者应使用 0 作为安全降级 Placeholder 尺寸。
 */
fun assumeLatexSize(
    latex: String,
    fontSizePx: Float,
    displayMode: Boolean = false,
): LatexMetrics? {
    return runCatching {
        val dl = RaTeXEngine.parseBlocking(latex, displayMode = displayMode)
        val m = dl.measure(fontSizePx)
        if (m.widthPx.isFinite() && m.heightPx.isFinite() && m.depthPx.isFinite()
            && m.widthPx <= MAX_COMPOSE_SIZE_PX
            && m.heightPx <= MAX_COMPOSE_SIZE_PX
            && m.depthPx <= MAX_COMPOSE_SIZE_PX
        ) {
            LatexMetrics(m.widthPx, m.heightPx, m.depthPx)
        } else null
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
