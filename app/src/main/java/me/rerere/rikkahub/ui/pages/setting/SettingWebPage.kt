package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.web.WebServerManager
import org.koin.compose.koinInject

@Composable
fun SettingWebPage() {
    val webServerManager: WebServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    var portText by remember(settings.webServerPort) {
        mutableStateOf(settings.webServerPort.toString())
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Web Server") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("Port") },
                    supportingContent = { Text("Web server listen port (1024-65535)") },
                    trailingContent = {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { value ->
                                portText = value.filter { it.isDigit() }
                                val port = portText.toIntOrNull()
                                if (port != null && port in 1024..65535) {
                                    scope.launch {
                                        settingsStore.update { it.copy(webServerPort = port) }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                            modifier = Modifier.width(100.dp),
                            enabled = !serverState.isRunning,
                        )
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Web Server") },
                    supportingContent = { Text("Enable the embedded web server") },
                    trailingContent = {
                        if (serverState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Switch(
                                checked = serverState.isRunning,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        webServerManager.start(port = settings.webServerPort)
                                    } else {
                                        webServerManager.stop()
                                    }
                                    scope.launch {
                                        settingsStore.update { it.copy(webServerEnabled = checked) }
                                    }
                                }
                            )
                        }
                    }
                )
            }

            item {
                AnimatedVisibility(visible = serverState.isRunning) {
                    ListItem(
                        headlineContent = { Text("LAN Address") },
                        supportingContent = {
                            Text("http://${serverState.address ?: "localhost"}:${serverState.port}")
                        }
                    )
                }
            }

            item {
                AnimatedVisibility(visible = serverState.isRunning && serverState.hostname != null) {
                    ListItem(
                        headlineContent = { Text("mDNS Address") },
                        supportingContent = {
                            Text("http://${serverState.hostname}:${serverState.port}")
                        }
                    )
                }
            }

            item {
                AnimatedVisibility(visible = serverState.error != null) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "Error",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        supportingContent = {
                            Text(
                                text = serverState.error ?: "",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}
