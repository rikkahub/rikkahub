package me.rerere.rikkahub.ui.components.ui

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class GridAnimationMode {
    Diagonal, CenterOut, Snake, Matrix, Pulse,
    Wave, WaveVertical, Spiral, Checkerboard,
    Cross, Corners, Rows, Columns, Bounce, Ripple
}

@Composable
fun GridLoading(
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    baseColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    mode: GridAnimationMode = GridAnimationMode.Diagonal,
    speed: Int = 1200, // 毫秒
    glow: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gridLoading")

    Column(
        modifier = modifier
            .size(20.dp)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(3) { rowIndex ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                repeat(3) { colIndex ->
                    val index = rowIndex * 3 + colIndex

                    // 计算每个格子的延迟进度 (0.0f 到 1.0f)
                    val delayFraction = getDelayFraction(rowIndex, colIndex, index, mode)

                    // 核心动画：控制亮度和缩放
                    val animationProgress by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(speed, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "progress_$index"
                    )

                    // 计算当前格子的激活程度 (基于 offset 的正弦波)
                    val currentFraction = (animationProgress + delayFraction) % 1f
                    val intensity = if (currentFraction < 0.5f) {
                        currentFraction * 2f // 0 -> 1
                    } else {
                        1f - (currentFraction - 0.5f) * 2f // 1 -> 0
                    }

                    GridCell(
                        intensity = intensity,
                        activeColor = activeColor,
                        baseColor = baseColor,
                        glow = glow,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
fun RandomGridLoading(
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    baseColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    speed: Int = 1200,
    glow: Boolean = true
) {
    val mode = remember {
        GridAnimationMode.entries.random()
    }
    GridLoading(
        modifier = modifier,
        activeColor = activeColor,
        baseColor = baseColor,
        mode = mode,
        speed = speed,
        glow = glow
    )
}

@Composable
private fun GridCell(
    intensity: Float,
    activeColor: Color,
    baseColor: Color,
    glow: Boolean,
    modifier: Modifier
) {
    // 动态计算颜色
    val color = lerpColor(baseColor, activeColor, intensity)
    val glowRadius = 20f * intensity

    Box(
        modifier = modifier
            .drawBehind {
                if (glow && intensity > 0.1f) {
                    val paint = Paint().asFrameworkPaint().apply {
                        this.color = activeColor.copy(alpha = intensity * 0.6f).toArgb()
                        setShadowLayer(
                            glowRadius, 0f, 0f,
                            activeColor.copy(alpha = intensity).toArgb()
                        )
                    }
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(
                            0f, 0f, size.width, size.height, paint
                        )
                    }
                }
            }
            .background(color)
    )
}

// 颜色插值工具函数
fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    return Color(
        red = start.red + (stop.red - start.red) * fraction,
        green = start.green + (stop.green - start.green) * fraction,
        blue = start.blue + (stop.blue - start.blue) * fraction,
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction
    )
}

// 延迟逻辑函数
fun getDelayFraction(row: Int, col: Int, index: Int, mode: GridAnimationMode): Float {
    return when (mode) {
        GridAnimationMode.Diagonal -> (row + col) / 4f
        GridAnimationMode.CenterOut -> {
            val dist = max(abs(row - 1), abs(col - 1))
            dist / 2f
        }

        GridAnimationMode.Snake -> {
            val snakeOrder = listOf(0, 1, 2, 5, 8, 7, 6, 3, 4)
            snakeOrder.indexOf(index) / 9f
        }

        GridAnimationMode.Matrix -> (index * 0.13f) % 1f
        GridAnimationMode.Pulse -> 0f
        GridAnimationMode.Wave -> col / 2f
        GridAnimationMode.WaveVertical -> row / 2f
        GridAnimationMode.Spiral -> {
            val spiralOrder = listOf(0, 1, 2, 5, 8, 7, 6, 3, 4)
            spiralOrder.indexOf(index) / 8f
        }

        GridAnimationMode.Checkerboard -> ((row + col) % 2) * 0.5f
        GridAnimationMode.Cross -> {
            if (row == 1 || col == 1) 0f else 0.5f
        }

        GridAnimationMode.Corners -> {
            if (row == 1 || col == 1) 0.5f else 0f
        }

        GridAnimationMode.Rows -> row / 2f
        GridAnimationMode.Columns -> col / 2f
        GridAnimationMode.Bounce -> abs(index - 4) / 4f
        GridAnimationMode.Ripple -> {
            val dist = sqrt(((row - 1f) * (row - 1f) + (col - 1f) * (col - 1f)))
            dist / 1.414f
        }
    }
}

@Composable
@Preview(showBackground = true, name = "Light")
@Preview(showBackground = true, name = "Dark", uiMode = UI_MODE_NIGHT_YES)
fun GlowGridLoadingPreview() {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        FlowRow(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GridAnimationMode.entries.fastForEach { mode ->
                GridLoading(mode = mode)
            }
        }
    }
}
