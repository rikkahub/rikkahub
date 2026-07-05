package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.voiceagent.VoiceSessionDebugDisplay
import me.rerere.rikkahub.voiceagent.debugLines

@Composable
internal fun VoiceSessionDebugBanner(
    display: VoiceSessionDebugDisplay,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            display.debugLines().forEach { line ->
                Text(
                    text = line.label,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = line.value,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
