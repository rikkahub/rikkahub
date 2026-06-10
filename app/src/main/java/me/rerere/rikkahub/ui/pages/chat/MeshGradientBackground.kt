package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部偏白)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 每个光斑用一个独立周期的无限动画,沿正弦/余弦轨迹缓慢漂移。
 *
 * 不依赖 Modifier.blur,全 API 级别可用,性能也更好。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "aurora")

    // 每个光斑一个相位(0..2π),不同时长 → 错落漂移,避免整齐划一
    @Composable
    fun phase(durationMillis: Int, label: String) = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = label,
    )

    val p1 by phase(14_000, "p1")
    val p2 by phase(17_000, "p2")
    val p3 by phase(20_000, "p3")

    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        // 暗色:顶部深蓝,向下渐隐到接近纯黑
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        // 亮色:顶部偏蓝,向下渐隐到白
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    // 光斑配色(蓝 / 青 / 淡蓝)及浓度,亮暗各一套
    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val alphaBlue = if (dark) 0.40f else 0.55f
    val alphaTeal = if (dark) 0.28f else 0.35f
    val alphaLightBlue = if (dark) 0.32f else 0.40f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 光斑半径取屏幕较长边的比例,够大才柔
            val r = maxOf(w, h)

            // 光斑全部聚在顶部,向下渐隐,保留下半屏留白
            // 顶部蓝(主色,横向缓慢漂移)
            drawBlob(
                center = Offset(w * 0.45f + sin(p1) * w * 0.20f, h * 0.02f + cos(p1) * h * 0.05f),
                radius = r * 0.55f,
                color = blobBlue,
                centerAlpha = alphaBlue,
            )
            // 左上青绿点缀
            drawBlob(
                center = Offset(w * 0.12f + sin(p2) * w * 0.12f, h * 0.22f + cos(p2) * h * 0.08f),
                radius = r * 0.40f,
                color = blobTeal,
                centerAlpha = alphaTeal,
            )
            // 右上淡蓝
            drawBlob(
                center = Offset(w * 0.88f + sin(p3) * w * -0.14f, h * 0.08f + cos(p3) * h * 0.06f),
                radius = r * 0.42f,
                color = blobLightBlue,
                centerAlpha = alphaLightBlue,
            )
        }

        content()
    }
}

/** 画一个柔光斑:中心有色,向外渐隐到透明。 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float = 0.75f,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
