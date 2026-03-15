package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator

    if (useAppIconStyleLoadingIndicator) {
        VortexLoadingIndicator(modifier = modifier)
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}

@Composable
private fun VortexLoadingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vortex_loading")
    val globalRotation = transition.animateFloat(
        initialValue = 0f,
        // Keep the head moving into the open arc so the trail follows behind it.
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing)
        ),
        label = "global_rotation"
    )
    val corePulse = transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )
    val glowPulse = transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    val isLightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val primaryHead = if (isLightBackground) Color(0xFF0E82AB) else Color(0xFFF3FCFF)
    val primaryTail = if (isLightBackground) Color(0xFF5CC7EA) else Color(0xFF8FD9FF)
    val secondaryHead = if (isLightBackground) Color(0xFF3469DB) else Color(0xFFE5F6FF)
    val secondaryTail = if (isLightBackground) Color(0xFF96BCFF) else Color(0xFFAAD8FF)
    val coreColor = if (isLightBackground) Color(0xFF1490C4) else Color(0xFFFFFFFF)
    val coreGlow = if (isLightBackground) Color(0x334EB9FF) else Color(0x3D8FD8FF)

    Canvas(modifier = modifier) {
        val minSide = size.minDimension
        if (minSide <= 0f) return@Canvas

        val trailCenter = center
        val maxRadius = minSide * 0.42f
        val coreRadius = minSide * 0.052f

        fun trailBrush(
            sweepAngle: Float,
            headColor: Color,
            tailColor: Color,
        ): Brush {
            val sweepFraction = (sweepAngle / 360f).coerceIn(0.08f, 0.95f)
            return Brush.sweepGradient(
                colorStops = arrayOf(
                    0f to headColor,
                    sweepFraction * 0.16f to tailColor.copy(alpha = 0.92f),
                    sweepFraction * 0.48f to tailColor.copy(alpha = 0.4f),
                    sweepFraction * 0.82f to tailColor.copy(alpha = 0.08f),
                    sweepFraction to Color.Transparent,
                    1f to Color.Transparent,
                ),
                center = trailCenter
            )
        }

        fun drawRing(
            radius: Float,
            strokeWidth: Float,
            sweepAngle: Float,
            ringOffsetAngle: Float,
            headColor: Color,
            tailColor: Color,
        ) {
            val arcTopLeft = Offset(trailCenter.x - radius, trailCenter.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            for (arm in 0 until 3) {
                val armRotation = globalRotation.value + ringOffsetAngle + arm * 120f
                rotate(armRotation, trailCenter) {
                    drawArc(
                        brush = trailBrush(
                            sweepAngle = sweepAngle,
                            headColor = headColor,
                            tailColor = tailColor,
                        ),
                        startAngle = 0f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    drawCircle(
                        color = headColor.copy(alpha = 0.98f),
                        center = Offset(trailCenter.x + radius, trailCenter.y),
                        radius = strokeWidth * 0.46f
                    )
                }
            }
        }

        drawRing(
            radius = maxRadius * 0.3f,
            strokeWidth = minSide * 0.045f,
            sweepAngle = 90f,
            ringOffsetAngle = 0f,
            headColor = primaryHead,
            tailColor = primaryTail,
        )
        drawRing(
            radius = maxRadius * 0.53f,
            strokeWidth = minSide * 0.04f,
            sweepAngle = 70f,
            ringOffsetAngle = -30f,
            headColor = secondaryHead,
            tailColor = secondaryTail,
        )
        drawRing(
            radius = maxRadius * 0.76f,
            strokeWidth = minSide * 0.035f,
            sweepAngle = 45f,
            ringOffsetAngle = -60f,
            headColor = primaryHead,
            tailColor = primaryTail,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreGlow.copy(alpha = 0.75f * glowPulse.value),
                    Color.Transparent
                ),
                center = trailCenter,
                radius = coreRadius * 3.8f
            ),
            center = trailCenter,
            radius = coreRadius * 3.8f
        )
        drawLine(
            color = coreColor.copy(alpha = 0.28f * glowPulse.value),
            start = Offset(trailCenter.x - coreRadius * 2f, trailCenter.y),
            end = Offset(trailCenter.x + coreRadius * 2f, trailCenter.y),
            strokeWidth = coreRadius * 0.32f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = coreColor.copy(alpha = 0.2f * glowPulse.value),
            start = Offset(trailCenter.x, trailCenter.y - coreRadius * 2f),
            end = Offset(trailCenter.x, trailCenter.y + coreRadius * 2f),
            strokeWidth = coreRadius * 0.26f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = coreColor.copy(alpha = 0.96f),
            center = trailCenter,
            radius = coreRadius * corePulse.value
        )
    }
}
