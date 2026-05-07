package me.rerere.rikkahub.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R

/**
 * Bottom AI-status stripe. Always visible — even with no actions, the user sees a
 * "AI ready" footer so they know what surface they're on. Tap header to expand into
 * a recent-actions list.
 *
 * Pass 1 surfaces the [BrowserController.recentActionsFlow] feed. Pass 2 will start
 * populating that feed via tool execution. Pass 2 will also add the step counter
 * ("Step 7 of 25 used") that the spec describes — Pass 1 leaves a clean slot for it.
 */
@Composable
fun BrowserAiStripe() {
    val actions by BrowserController.recentActionsFlow().collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    val headlineText = if (actions.isEmpty()) {
        stringResource(R.string.browser_ai_stripe_idle)
    } else {
        stringResource(R.string.browser_ai_stripe_active, actions.first())
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) {
                        stringResource(R.string.browser_ai_stripe_collapse)
                    } else {
                        stringResource(R.string.browser_ai_stripe_expand)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded && actions.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(actions) { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item { Spacer(modifier = Modifier.size(8.dp)) }
                }
            }
        }
    }
}
