package com.hefengfan.hffchat.ui.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize

/**
 * 为 Composable 添加 Shimmer 加载效果的 Modifier.
 *
 * @param isLoading 是否显示 Shimmer 效果。
 * @param shimmerColor 闪光的亮色部分。
 * @param backgroundColor Shimmer 渐变的背景色（通常是半透明的，以混合原始内容）。
 * @param durationMillis 动画完成一次扫描的时长（毫秒）。
 * @param angle 闪光效果的角度（度）。0 度是从左到右，90 度是从上到下。
 * @param gradientWidthRatio 闪光渐变宽度相对于组件尺寸的比例。例如，0.5f 表示闪光宽度为组件宽度的一半。
 */
@Composable
fun Modifier.shimmer(
    isLoading: Boolean,
    shimmerColor: Color = LocalContentColor.current.copy(alpha = 0.3f), // 较亮的闪光颜色
    backgroundColor: Color = LocalContentColor.current.copy(alpha = 0.9f), // 较暗的背景/基础颜色
    durationMillis: Int = 1200,
    angle: Float = 20f, // 稍微倾斜的角度
    gradientWidthRatio: Float = 0.5f // 闪光宽度为组件宽度的一半
): Modifier = composed { // 使用 composed 以便在 Modifier 内部使用 remember 和 LaunchedEffect 等
    if (!isLoading) {
        // 如果不处于加载状态，则不应用任何效果
        this
    } else {
        // 记住组件的尺寸，以便计算渐变
        var size by remember { mutableStateOf(IntSize.Zero) }
        // 创建无限循环动画
        val transition = rememberInfiniteTransition(label = "ShimmerTransition")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f, // 动画值从 0 到 1
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart // 每次都从头开始
            ),
            label = "ShimmerTranslate"
        )
        // 将角度转换为弧度
        val angleRad = Math.toRadians(angle.toDouble()).toFloat()
        // 计算渐变颜色的列表
        val colors = remember(shimmerColor, backgroundColor) {
            listOf(
                backgroundColor, // 开始的背景色
                shimmerColor,    // 中间的亮色
                backgroundColor  // 结束的背景色
            )
        }
        // 应用绘制效果
        this
            .onGloballyPositioned { layoutCoordinates ->
                // 获取组件的实际尺寸
                size = layoutCoordinates.size
            }
            .graphicsLayer { alpha = 0.99f } // 开启混合
            .drawWithContent { // 使用 drawWithContent 获取绘制上下文
                if (size == IntSize.Zero) {
                    // 如果尺寸未知，先绘制原始内容
                    drawContent()
                    return@drawWithContent
                }
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                // 计算渐变的实际宽度（像素）
                // 我们需要考虑对角线长度，以确保倾斜时能完全覆盖
                val diagonal = kotlin.math.sqrt(width * width + height * height)
                val gradientWidth = diagonal * gradientWidthRatio
                // 计算动画当前位置的偏移量
                // 动画值从 0 到 1，映射到移动距离
                // 总移动距离需要覆盖组件加上渐变宽度，确保完全扫过
                // 我们让它从完全在组件左/上侧开始，移动到完全在右/下侧结束
                val totalDistance = diagonal + gradientWidth
                val currentOffset = translateAnimation.value * totalDistance - gradientWidth
                // 计算渐变的起始点和结束点，考虑角度
                val startX = currentOffset * kotlin.math.cos(angleRad)
                val startY = currentOffset * kotlin.math.sin(angleRad)
                val endX = (currentOffset + gradientWidth) * kotlin.math.cos(angleRad)
                val endY = (currentOffset + gradientWidth) * kotlin.math.sin(angleRad)
                // 创建线性渐变 Brush
                val shimmerBrush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    tileMode = TileMode.Clamp // Clamp 模式确保渐变颜色在边缘处固定
                )
                // 1. 先绘制原始内容
                drawContent()
                // 2. 在原始内容之上绘制一个矩形，使用 Shimmer Brush 和 DstIn 混合模式
                // BlendMode.DstIn: 只保留目标（原始内容）与源（Shimmer渐变）重叠的部分，
                // 并且使用源的 Alpha 值。这使得渐变亮部显示内容，暗部（透明部）隐藏内容。
                drawRect(
                    brush = shimmerBrush,
                    blendMode = BlendMode.DstIn
                )
            }
    }
}
