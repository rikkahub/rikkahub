package me.rerere.rikkahub.ui.components.cyberpunk

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.presets.DeepBlack
import me.rerere.rikkahub.ui.theme.presets.GridLine
import me.rerere.rikkahub.ui.theme.presets.NeonCyan
import me.rerere.rikkahub.ui.theme.presets.NeonGreen
import me.rerere.rikkahub.ui.theme.presets.NeonPink
import me.rerere.rikkahub.ui.theme.presets.PanelBlack
import me.rerere.rikkahub.ui.theme.presets.TextPrimary
import me.rerere.rikkahub.ui.theme.presets.TextSecondary

/**
 * HUD 风格顶部栏 - 硬朗版本
 */
@Composable
fun HudTopBar(
    title: String,
    modifier: Modifier = Modifier,
    statusIndicator: @Composable () -> Unit = { StatusIndicatorOnline() }
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelBlack.copy(alpha = 0.95f))
            .border(width = 1.dp, color = NeonCyan.copy(alpha = 0.5f))
            .cornerBrackets(color = NeonCyan.copy(alpha = 0.6f), bracketLength = 8.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GlitchTextStatic(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                statusIndicator()
            }
        }
    }
}

/**
 * 在线状态指示器 - 方形
 */
@Composable
fun StatusIndicatorOnline(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_alpha"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(NeonGreen)
                .alpha(alpha)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "ONLINE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = NeonGreen,
                fontWeight = FontWeight.Black
            )
        )
    }
}

/**
 * 离线状态指示器 - 方形
 */
@Composable
fun StatusIndicatorOffline(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(NeonPink)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "OFFLINE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = NeonPink,
                fontWeight = FontWeight.Black
            )
        )
    }
}

/**
 * HUD 数据面板 - 硬朗版本
 */
@Composable
fun HudDataPanel(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    color: Color = NeonCyan
) {
    CyberpunkPanel(
        modifier = modifier,
        borderColor = color.copy(alpha = 0.3f),
        showBrackets = true
    ) {
        Column {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontWeight = FontWeight.Black
                )
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        fontWeight = FontWeight.Black
                    )
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    )
                }
            }
        }
    }
}

/**
 * HUD 分割线 - 硬朗
 */
@Composable
fun HudDivider(
    modifier: Modifier = Modifier,
    color: Color = GridLine
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .size(1.dp)
                .background(color)
        )
    }
}

/**
 * 警告面板
 */
@Composable
fun WarningPanel(
    text: String,
    modifier: Modifier = Modifier
) {
    CyberpunkPanel(
        modifier = modifier,
        borderColor = WarningRed.copy(alpha = 0.5f),
        backgroundColor = WarningRed.copy(alpha = 0.05f),
        showBrackets = true
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(WarningRed)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = WarningRed,
                    fontWeight = FontWeight.Black
                )
            )
        }
    }
}

/**
 * 系统日志条目
 */
@Composable
fun LogEntry(
    timestamp: String,
    level: String,
    message: String,
    modifier: Modifier = Modifier
) {
    val levelColor = when (level.uppercase()) {
        "ERROR" -> WarningRed
        "WARN" -> AlertOrange
        "INFO" -> NeonCyan
        "DEBUG" -> TextSecondary
        else -> TextSecondary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            ),
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = "[$level]",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = levelColor,
                fontWeight = FontWeight.Black
            ),
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
            )
        )
    }
}
