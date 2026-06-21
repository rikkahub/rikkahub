package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * Chat loading indicator, gated by the "app-icon style" display toggle:
 *  - ON  → the three-dot "typing…" indicator (replaces the old rabbit app-icon animation).
 *  - OFF → the default Material 3 [ContainedLoadingIndicator].
 *
 * The dots follow the active theme (TypingDotsIndicator defaults to colorScheme.primary).
 */
@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator
    if (useAppIconStyleLoadingIndicator) {
        // The incoming [modifier] is a SQUARE size (e.g. size(28.dp)) meant for the rabbit/M3 indicators;
        // the dot row is wider than tall, so applying it would clip the last dot to a thin sliver. Let the
        // dots self-size, only fixing the height so they vertically match the 28.dp indicators in the row.
        TypingDotsIndicator(modifier = Modifier.height(28.dp))
    } else {
        ContainedLoadingIndicator(modifier = modifier)
    }
}
