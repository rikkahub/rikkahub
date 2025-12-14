package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    Icon(
                        imageVector = Lucide.ArrowUp,
                        contentDescription = "Input",
                        tint = color,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "${usage.totalTokens.formatNumber()} tokens"
                    )
                    Icon(
                        imageVector = Lucide.ArrowDown,
                        contentDescription = "Output",
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "${usage.completionTokens.formatNumber()} tokens"
                    )
                    if(usage.cachedTokens > 0) {
                        Text(
                            text = "(${message.usage?.cachedTokens?.formatNumber() ?: "0"} cached)"
                        )
                    }
                }
            }
        }
    }
}
