package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator

    if (useAppIconStyleLoadingIndicator) {
        OrbitLoadingIndicator(modifier = modifier)
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}

@Composable
private fun OrbitLoadingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lune_loading")
    val outerRotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing)
        ),
        label = "outer_rotation"
    )
    val middleRotation = transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8200, easing = LinearEasing)
        ),
        label = "middle_rotation"
    )
    val innerRotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5600, easing = LinearEasing)
        ),
        label = "inner_rotation"
    )
    val pulse = transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val orbitColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFF111111)
    } else {
        Color.White
    }
    val glowPrimary = Color(0xFF00D4FF)
    val glowSecondary = Color(0xFF6D8BFF)

    Canvas(modifier = modifier) {
        val minSide = size.minDimension
        if (minSide <= 0f) return@Canvas

        val strokeOuter = minSide * 0.12f
        val strokeMiddle = minSide * 0.1f
        val strokeInner = minSide * 0.09f
        val centerDot = minSide * 0.05f

        fun ring(sizeFactor: Float): Pair<Offset, Size> {
            val ringSize = minSide * sizeFactor
            val topLeft = Offset(
                x = center.x - ringSize / 2f,
                y = center.y - ringSize / 2f
            )
            return topLeft to Size(ringSize, ringSize)
        }

        fun drawOrbit(
            topLeft: Offset,
            arcSize: Size,
            startAngle: Float,
            sweepAngle: Float,
            strokeWidth: Float,
            glowColor: Color,
            glowBoost: Float = 1f,
        ) {
            drawArc(
                color = glowColor.copy(alpha = 0.12f * glowBoost),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth * 2.2f, cap = StrokeCap.Round)
            )
            drawArc(
                color = glowColor.copy(alpha = 0.18f * glowBoost),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth * 1.6f, cap = StrokeCap.Round)
            )
            drawArc(
                color = orbitColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        rotate(outerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.86f)
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 16f,
                sweepAngle = 208f,
                strokeWidth = strokeOuter,
                glowColor = glowPrimary,
                glowBoost = 1.15f,
            )
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 272f,
                sweepAngle = 46f,
                strokeWidth = strokeOuter * 0.8f,
                glowColor = glowSecondary,
            )
        }

        rotate(middleRotation.value, center) {
            val (topLeft, arcSize) = ring(0.64f)
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 58f,
                sweepAngle = 152f,
                strokeWidth = strokeMiddle,
                glowColor = glowSecondary,
                glowBoost = 1.1f,
            )
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 238f,
                sweepAngle = 74f,
                strokeWidth = strokeMiddle * 0.85f,
                glowColor = glowPrimary,
            )
        }

        rotate(innerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.42f)
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 34f,
                sweepAngle = 120f,
                strokeWidth = strokeInner,
                glowColor = glowPrimary,
                glowBoost = 1.2f,
            )
            drawOrbit(
                topLeft = topLeft,
                arcSize = arcSize,
                startAngle = 214f,
                sweepAngle = 88f,
                strokeWidth = strokeInner,
                glowColor = glowSecondary,
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowPrimary.copy(alpha = 0.28f * pulse.value),
                    glowSecondary.copy(alpha = 0.16f * pulse.value),
                    Color.Transparent
                ),
                center = center,
                radius = centerDot * 3.4f
            ),
            radius = centerDot * 3.4f
        )
        drawCircle(
            color = orbitColor.copy(alpha = 0.65f + pulse.value * 0.2f),
            radius = centerDot * pulse.value
        )
    }
}
