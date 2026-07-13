package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.sync.cloud.SyncMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingCloudSyncPage(
    vm: SettingCloudSyncVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val outboxCount by vm.outboxCount.collectAsStateWithLifecycle()
    val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val config = settings.perryConfig
    val mode = SyncMode.fromStorage(syncState?.syncMode)
    var bootstrapVisible by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Cloud & Sync") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(title = { Text("Status") }) {
                    item(
                        headlineContent = { Text("Connection") },
                        supportingContent = {
                            Text("$connectionStatus · outbox=$outboxCount")
                        },
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                    )
                    if (statusText != null) {
                        item(
                            headlineContent = { Text("Last result") },
                            supportingContent = {
                                Text(
                                    text = statusText!!,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text("Device token") },
                        supportingContent = {
                            Text(
                                if (settings.perryDeviceToken.isBlank()) {
                                    "(empty — register first)"
                                } else {
                                    "saved (${settings.perryDeviceToken.length} chars)"
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("Device ID") },
                        supportingContent = { Text(syncState?.deviceId ?: "(not registered)") },
                    )
                    item(
                        headlineContent = { Text("Cursor") },
                        supportingContent = {
                            Text(
                                "cursor=${syncState?.changeCursor ?: 0} lastSuccess=${syncState?.lastSuccessAt ?: "-"}"
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("Sync mode") }) {
                    item(
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SyncMode.entries.forEach { m ->
                                    FilterChip(
                                        selected = mode == m,
                                        onClick = { vm.setSyncMode(m) },
                                        label = { Text(m.name) },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("Server") }) {
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(
                                    value = config.scheme,
                                    onValueChange = { vm.updateConfig { c -> c.copy(scheme = it) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Scheme (http/https)") },
                                    singleLine = true,
                                    colors = clearFieldColors(),
                                )
                                TextField(
                                    value = config.host,
                                    onValueChange = { vm.updateConfig { c -> c.copy(host = it) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Host") },
                                    singleLine = true,
                                    colors = clearFieldColors(),
                                )
                                TextField(
                                    value = config.port?.toString().orEmpty(),
                                    onValueChange = { text ->
                                        vm.updateConfig { c ->
                                            c.copy(port = text.toIntOrNull())
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Port (optional)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = clearFieldColors(),
                                )
                                TextField(
                                    value = config.basePath,
                                    onValueChange = { vm.updateConfig { c -> c.copy(basePath = it) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Base path (optional)") },
                                    singleLine = true,
                                    colors = clearFieldColors(),
                                )
                                TextField(
                                    value = config.deviceName,
                                    onValueChange = { vm.updateConfig { c -> c.copy(deviceName = it) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Device name") },
                                    singleLine = true,
                                    colors = clearFieldColors(),
                                )
                                Text(
                                    text = runCatching { config.normalizedBaseUrl() }.getOrElse { "(invalid URL)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("Auth") }) {
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(
                                    value = config.bootstrapToken,
                                    onValueChange = {
                                        vm.updateConfig { c -> c.copy(bootstrapToken = it) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Bootstrap token (register only)") },
                                    singleLine = true,
                                    visualTransformation = if (bootstrapVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { bootstrapVisible = !bootstrapVisible }) {
                                            Icon(
                                                if (bootstrapVisible) HugeIcons.ViewOff else HugeIcons.View,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    colors = clearFieldColors(),
                                )
                                TextField(
                                    value = settings.perryDeviceToken,
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Device token") },
                                    singleLine = true,
                                    visualTransformation = if (tokenVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                            Icon(
                                                if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    colors = clearFieldColors(),
                                )
                            }
                        },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Button(
                        onClick = { vm.testConnection() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test connection") }
                    Button(
                        onClick = { vm.registerDevice() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Register this device") }
                    Button(
                        onClick = { vm.syncNow() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sync now") }
                    OutlinedButton(
                        onClick = { vm.resetCursor() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset sync cursor") }
                    OutlinedButton(
                        onClick = { vm.clearCredentials() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear device credentials") }
                }
            }
        }
    }
}

@Composable
private fun clearFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
)
