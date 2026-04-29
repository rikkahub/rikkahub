package me.rerere.rikkahub.ui.components.cyberpunk

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.presets.NeonCyan

/**
 * 硬朗霓虹发光效果 Modifier
 * 直角边框 + 锐利光晕，无圆角
 */
fun Modifier.neonGlow(
    color: Color = NeonCyan,
    radius: Dp = 8.dp,
    strokeWidth: Dp = 1.5.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawNeonStroke(color, radius, strokeWidth)
    }
)

/**
 * 霓虹边框绘制 - 直角版本
 */
private fun ContentDrawScope.drawNeonStroke(
    color: Color,
    radius: Dp,
    strokeWidth: Dp
) {
    // 外发光层
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            this.style = PaintingStyle.Stroke
            this.strokeWidth = strokeWidth.toPx()
        }
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = color.copy(alpha = 0f).toArgb()
        frameworkPaint.setShadowLayer(
            radius.toPx(), 0f, 0f,
            color.copy(alpha = 0.6f).toArgb()
        )
        canvas.drawRect(
            0f, 0f, size.width, size.height,
            paint
        )
    }
    // 实体直角边框
    drawRect(
        color = color,
        size = size,
        style = Stroke(width = strokeWidth.toPx())
    )
}

/**
 * 硬朗角标装饰 Modifier
 * 在四个角绘制 L 形标记
 */
fun Modifier.cornerBrackets(
    color: Color = NeonCyan,
    strokeWidth: Dp = 2.dp,
    bracketLength: Dp = 12.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawCornerBrackets(color, strokeWidth, bracketLength)
    }
)

private fun ContentDrawScope.drawCornerBrackets(
    color: Color,
    strokeWidth: Dp,
    bracketLength: Dp
) {
    val len = bracketLength.toPx()
    val sw = strokeWidth.toPx()
    val w = size.width
    val h = size.height

    // 左上
    drawLine(color, Offset(0f, 0f), Offset(len, 0f), sw)
    drawLine(color, Offset(0f, 0f), Offset(0f, len), sw)
    // 右上
    drawLine(color, Offset(w - len, 0f), Offset(w, 0f), sw)
    drawLine(color, Offset(w, 0f), Offset(w, len), sw)
    // 左下
    drawLine(color, Offset(0f, h - len), Offset(0f, h), sw)
    drawLine(color, Offset(0f, h), Offset(len, h), sw)
    // 右下
    drawLine(color, Offset(w - len, h), Offset(w, h), sw)
    drawLine(color, Offset(w, h - len), Offset(w, h), sw)
}

/**
 * 工业风格边框 - 无发光，纯线条
 */
fun Modifier.industrialBorder(
    color: Color = SteelGray,
    strokeWidth: Dp = 1.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawRect(
            color = color,
            size = size,
            style = Stroke(width = strokeWidth.toPx())
        )
    }
)
