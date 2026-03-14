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

        rotate(outerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.86f)
            drawArc(
                color = orbitColor,
                startAngle = 16f,
                sweepAngle = 208f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
            )
            drawArc(
                color = orbitColor,
                startAngle = 272f,
                sweepAngle = 46f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeOuter * 0.8f, cap = StrokeCap.Round)
            )
        }

        rotate(middleRotation.value, center) {
            val (topLeft, arcSize) = ring(0.64f)
            drawArc(
                color = orbitColor,
                startAngle = 58f,
                sweepAngle = 152f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeMiddle, cap = StrokeCap.Round)
            )
            drawArc(
                color = orbitColor,
                startAngle = 238f,
                sweepAngle = 74f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeMiddle * 0.85f, cap = StrokeCap.Round)
            )
        }

        rotate(innerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.42f)
            drawArc(
                color = orbitColor,
                startAngle = 34f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeInner, cap = StrokeCap.Round)
            )
            drawArc(
                color = orbitColor,
                startAngle = 214f,
                sweepAngle = 88f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeInner, cap = StrokeCap.Round)
            )
        }

        drawCircle(
            color = orbitColor.copy(alpha = 0.65f + pulse.value * 0.2f),
            radius = centerDot * pulse.value
        )
    }
}
