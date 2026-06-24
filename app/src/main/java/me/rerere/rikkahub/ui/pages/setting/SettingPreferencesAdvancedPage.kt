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
                        headlineContent = { Text("Max background subagents") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("How many background subagents may run at the same time. 0 = unlimited.")
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
                        headlineContent = { Text("Max goal iterations") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("How many times a /goal may auto-continue the agent before it stops. 0 = unlimited. A user-stop always ends it.")
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
                        headlineContent = { Text("Untrusted content protection") },
                        supportingContent = {
                            Text(
                                "Tell the model to treat tool output (shell, files, web pages, MCP results) " +
                                    "and recalled memory / knowledge as untrusted data, not instructions — a " +
                                    "defense against prompt injection. On by default. Turn off only if you " +
                                    "understand the risk (e.g. to cut prompt noise on trusted, local-only use)."
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
                        headlineContent = { Text("Allow insecure HTTPS") },
                        supportingContent = {
                            Text(
                                "Skip TLS certificate & hostname verification for network requests — the " +
                                    "curl -k / --insecure equivalent. Off by default. Turn on only to reach a " +
                                    "provider with a self-signed or invalid certificate (a self-hosted endpoint " +
                                    "or dev proxy). WARNING: while on, connections are not protected against " +
                                    "man-in-the-middle interception."
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
                        headlineContent = { Text("Request log") },
                        supportingContent = {
                            Text(
                                "Record per-request network metadata (URL, method, headers, status, " +
                                    "timing) for the in-app Log page. Off by default. Request bodies are " +
                                    "never stored — only safe metadata; turn on temporarily to diagnose " +
                                    "connection issues."
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
