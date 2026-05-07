package me.rerere.rikkahub.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R

/**
 * Top app bar for [BrowserActivity]. Read-only URL label + history nav arrows + refresh
 * + close + kebab. Uses HugeIcons throughout to match the rest of the app's app-bar
 * iconography (BackButton, TopBar action icons all use HugeIcons.ArrowLeft01 etc.).
 *
 * Pass 1 wires:
 *   - close (finishes the Activity, leaving the AI loop in whatever state it was in —
 *     Pass 2 will surface a proper "stop the AI" path through [BrowserController.stopCurrentTask])
 *   - back / forward (browser history, no-op when not available)
 *   - refresh (reloads the current page)
 *   - kebab → "Stop AI" item — currently invokes [BrowserController.stopCurrentTask]
 *     which in Pass 1 just appends a stop entry to the actions log. Pass 2 will turn
 *     this into a real cancellation signal that aborts the in-flight tool dispatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAddressBar(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStopAi: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                text = url.ifEmpty { "about:blank" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.browser_address_bar_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    imageVector = HugeIcons.ArrowLeft01,
                    contentDescription = stringResource(R.string.browser_address_bar_back),
                )
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.browser_address_bar_forward),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.browser_address_bar_refresh),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.browser_address_bar_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_address_bar_stop_ai)) },
                        onClick = {
                            menuExpanded = false
                            onStopAi()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}
