package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/**
 * Three-dot "typing…" loading indicator. The dots DON'T move — a brightness peak travels
 * dot0 → dot1 → dot2 and wraps. Implemented as one continuous cosine wave per dot (offset by index),
 * so the highlight is evenly spaced AND seamless across the cycle boundary — no dot (least of all the
 * last) hitches or pauses. Follows the active theme (defaults to colorScheme.primary).
 */
@Composable
fun TypingDotsIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotCount: Int = 3,
    dotSize: Dp = 8.dp,
    dotSpacing: Dp = 5.dp,
    dimAlpha: Float = 0.25f,
    cycleMillis: Int = 1100,
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )

    val dim = color.copy(alpha = dimAlpha)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(dotCount) { index ->
            val wave = 0.5f + 0.5f * cos(2f * PI.toFloat() * (phase - index.toFloat() / dotCount))
            val fraction = wave * wave // squared → crisper highlight, dimmer trough
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color = lerp(dim, color, fraction), shape = CircleShape),
            )
        }
    }
}
