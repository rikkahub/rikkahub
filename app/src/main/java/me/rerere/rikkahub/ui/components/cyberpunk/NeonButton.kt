package me.rerere.rikkahub.ui.components.cyberpunk

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.presets.NeonCyan
import me.rerere.rikkahub.ui.theme.presets.NeonPink

/**
 * 硬朗霓虹按钮
 * 直角 + 锐利边框，无圆角
 */
@Composable
fun CyberpunkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NeonCyan,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) color.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(100),
        label = "button_bg"
    )

    Box(
        modifier = modifier
            .background(bgColor)
            .border(
                width = if (isPressed) 2.dp else 1.dp,
                color = if (enabled) color else Color.Gray.copy(alpha = 0.5f)
            )
            .then(
                if (enabled && !isPressed) Modifier.neonGlow(
                    color = color,
                    radius = 4.dp,
                    strokeWidth = 1.dp
                ) else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else Color.Gray.copy(alpha = 0.5f),
            fontWeight = FontWeight.Black,
            letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing
        )
    }
}

/**
 * 小型硬朗按钮
 */
@Composable
fun CyberpunkButtonSmall(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NeonPink
) {
    CyberpunkButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        color = color
    )
}

/**
 * 警告按钮（红色）
 */
@Composable
fun CyberpunkButtonWarning(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CyberpunkButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        color = WarningRed
    )
}
