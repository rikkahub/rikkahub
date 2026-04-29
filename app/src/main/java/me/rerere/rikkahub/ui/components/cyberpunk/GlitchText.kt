package me.rerere.rikkahub.ui.components.cyberpunk

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.presets.NeonCyan
import me.rerere.rikkahub.ui.theme.presets.NeonPink
import me.rerere.rikkahub.ui.theme.presets.TextPrimary

/**
 * 硬朗故障文字效果
 * 锐利偏移 + 无平滑过渡，工业感
 */
@Composable
fun GlitchText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    glitchIntensity: Float = 1f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glitch")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glitch_offset"
    )

    Box(modifier = modifier) {
        // 青色偏移层
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = NeonCyan.copy(alpha = 0.8f),
            modifier = Modifier.offset(x = (offsetX * glitchIntensity).dp)
        )
        // 粉色偏移层
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = NeonPink.copy(alpha = 0.8f),
            modifier = Modifier.offset(x = (-offsetX * glitchIntensity).dp)
        )
        // 主文字层
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = TextPrimary
        )
    }
}

/**
 * 静态故障文字（无动画）
 */
@Composable
fun GlitchTextStatic(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    glitchColor1: Color = NeonCyan,
    glitchColor2: Color = NeonPink
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = glitchColor1.copy(alpha = 0.6f),
            modifier = Modifier.offset(x = (-2).dp)
        )
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = glitchColor2.copy(alpha = 0.6f),
            modifier = Modifier.offset(x = 2.dp)
        )
        Text(
            text = text,
            style = style.copy(fontWeight = FontWeight.Black),
            color = TextPrimary
        )
    }
}
