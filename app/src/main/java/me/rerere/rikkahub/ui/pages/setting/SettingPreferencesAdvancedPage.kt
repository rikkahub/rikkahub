package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.ext.plus
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Advanced preferences — runtime/agent behaviour knobs that most users never need to touch (kept out of
 * the General page on purpose). Currently: the max number of background subagents allowed to run at once.
 */
@Composable
fun SettingPreferencesAdvancedPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Advanced") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = {
                            Text("Max background subagents", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "How many background subagents can run at the same time. 0 means no " +
                                        "limit. Each runs detached and reports back when it finishes — a " +
                                        "higher cap does more work in parallel but uses more memory and battery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = settings.maxBackgroundSubagents.coerceIn(0, 10).toFloat(),
                                        onValueChange = {
                                            vm.updateSettings(settings.copy(maxBackgroundSubagents = it.toInt()))
                                        },
                                        valueRange = 0f..10f,
                                        steps = 9,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = if (settings.maxBackgroundSubagents <= 0) "∞"
                                        else "${settings.maxBackgroundSubagents}",
                                    )
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text("Max goal iterations", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "How many times a /goal can automatically continue the agent before it " +
                                        "stops. 0 means no limit. The agent also stops as soon as the goal is " +
                                        "met, and a manual stop always ends it immediately.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = settings.maxGoalIterations.coerceIn(0, 50).toFloat(),
                                        onValueChange = {
                                            vm.updateSettings(settings.copy(maxGoalIterations = it.toInt()))
                                        },
                                        valueRange = 0f..50f,
                                        steps = 49,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = if (settings.maxGoalIterations <= 0) "∞"
                                        else "${settings.maxGoalIterations}",
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Security") },
                ) {
                    item(
                        headlineContent = {
                            Text("Untrusted content protection", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                "Tells the model to treat tool output — shell, files, web pages, MCP " +
                                    "results — and recalled memory or knowledge as untrusted data, not " +
                                    "instructions. This defends against prompt injection, where content the " +
                                    "model reads tries to hijack what it does. On by default; turn it off only " +
                                    "for fully trusted, local-only content to trim prompt size.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.enableUntrustedContentFraming,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(enableUntrustedContentFraming = it))
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text("Allow insecure HTTPS", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                "Skip TLS certificate and hostname checks for network requests, so the app " +
                                    "still connects when a provider's certificate is self-signed, expired, or " +
                                    "doesn't match its host — typical of a self-hosted server or a local dev " +
                                    "proxy. Off by default. While on, the connection can be read or modified by " +
                                    "a man-in-the-middle, so enable it only for endpoints you trust and turn it " +
                                    "back off afterward.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.allowInsecureHttps,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(allowInsecureHttps = it))
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Diagnostics") },
                ) {
                    item(
                        headlineContent = {
                            Text("Request log", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                "Record per-request network metadata — URL, method, headers, response " +
                                    "status, and timing — to the in-app Log page so you can diagnose " +
                                    "connection or provider problems. Off by default. Request and response " +
                                    "bodies are never recorded, only this metadata, and credential headers " +
                                    "are redacted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.enableRequestLog,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(enableRequestLog = it))
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
