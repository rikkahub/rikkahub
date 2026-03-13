package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    val orbitColors = listOf(
        Color(0xFF00F2FE),
        Color(0xFF4FACFE),
        Color(0xFF4364F7),
        Color(0xFF00C9FF),
        Color(0xFF192846),
    )

    Canvas(modifier = modifier) {
        val minSide = size.minDimension
        if (minSide <= 0f) return@Canvas

        val strokeOuter = minSide * 0.07f
        val strokeMiddle = minSide * 0.055f
        val strokeInner = minSide * 0.045f
        val centerDot = minSide * 0.06f

        fun ring(sizeFactor: Float): Pair<Offset, Size> {
            val ringSize = minSide * sizeFactor
            val topLeft = Offset(
                x = center.x - ringSize / 2f,
                y = center.y - ringSize / 2f
            )
            return topLeft to Size(ringSize, ringSize)
        }

        val outerBrush = Brush.sweepGradient(
            colors = orbitColors,
            center = center
        )
        val middleBrush = Brush.sweepGradient(
            colors = orbitColors.asReversed(),
            center = center
        )
        val innerBrush = Brush.sweepGradient(
            colors = orbitColors.drop(1) + orbitColors.first(),
            center = center
        )

        val faintColor = Color(0xFF4FACFE).copy(alpha = 0.14f)
        listOf(0.88f, 0.64f, 0.42f).forEach { sizeFactor ->
            val (topLeft, arcSize) = ring(sizeFactor)
            drawArc(
                color = faintColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = minSide * 0.018f)
            )
        }

        rotate(outerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.88f)
            drawArc(
                brush = outerBrush,
                startAngle = 12f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
            )
            drawArc(
                brush = outerBrush,
                startAngle = 270f,
                sweepAngle = 55f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeOuter * 0.8f, cap = StrokeCap.Round)
            )
        }

        rotate(middleRotation.value, center) {
            val (topLeft, arcSize) = ring(0.64f)
            drawArc(
                brush = middleBrush,
                startAngle = 60f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeMiddle, cap = StrokeCap.Round)
            )
            drawArc(
                brush = middleBrush,
                startAngle = 245f,
                sweepAngle = 82f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeMiddle * 0.85f, cap = StrokeCap.Round)
            )
        }

        rotate(innerRotation.value, center) {
            val (topLeft, arcSize) = ring(0.42f)
            drawArc(
                brush = innerBrush,
                startAngle = 34f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeInner, cap = StrokeCap.Round)
            )
            drawArc(
                brush = innerBrush,
                startAngle = 210f,
                sweepAngle = 98f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeInner, cap = StrokeCap.Round)
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF00F2FE).copy(alpha = 0.9f * pulse.value),
                    Color(0xFF4FACFE).copy(alpha = 0.45f * pulse.value),
                    Color.Transparent
                ),
                center = center,
                radius = centerDot * 2.5f
            ),
            radius = centerDot * 2.5f
        )
        drawCircle(
            color = Color(0xFF00F2FE).copy(alpha = 0.9f),
            radius = centerDot * pulse.value
        )
    }
}
