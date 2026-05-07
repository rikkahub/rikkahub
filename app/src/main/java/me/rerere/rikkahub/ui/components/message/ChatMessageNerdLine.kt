package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            val usage = message.usage
            if (settings.showTokenUsage && usage != null) {
                Text(
                    text = buildStatsText(message),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

private fun buildStatsText(message: UIMessage): String {
    val usage = message.usage ?: return ""
    val input = buildString {
        append("↑")
        append(compactTokenCount(usage.promptTokens))
        if (usage.cachedTokens > 0) {
            append("(c")
            append(compactTokenCount(usage.cachedTokens))
            append(")")
        }
    }
    val parts = mutableListOf(input, "↓${compactTokenCount(usage.completionTokens)}")

    val finishedAt = message.finishedAt
    if (finishedAt != null) {
        val duration = Duration.between(
            message.createdAt.toJavaLocalDateTime(),
            finishedAt.toJavaLocalDateTime()
        )
        val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
        val seconds = (duration.toMillis() / 1000f).toFixed(1)
        parts += "${tps.toFixed(1)}/s"
        parts += "${seconds}s"
    }

    return parts.joinToString(" ")
}

private fun compactTokenCount(value: Number): String {
    val number = value.toLong()
    val absolute = kotlin.math.abs(number.toDouble())
    val suffix = when {
        absolute >= 1_000_000_000 -> "b"
        absolute >= 1_000_000 -> "m"
        absolute >= 1_000 -> "k"
        else -> return number.toString()
    }
    val divisor = when (suffix) {
        "b" -> 1_000_000_000.0
        "m" -> 1_000_000.0
        else -> 1_000.0
    }
    val roundedTenths = kotlin.math.round(number / divisor * 10).toLong()
    val whole = roundedTenths / 10
    val decimal = kotlin.math.abs(roundedTenths % 10)
    val formatted = if (decimal == 0L) whole.toString() else "$whole.$decimal"
    return "$formatted$suffix"
}
