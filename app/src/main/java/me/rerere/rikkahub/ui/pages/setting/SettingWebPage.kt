package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

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
                    headlineContent = { Text("Web Server") },
                    supportingContent = { Text("Enable the embedded web server") },
                    trailingContent = {
                        Switch(
                            checked = serverState.isRunning,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    webServerManager.start()
                                } else {
                                    webServerManager.stop()
                                }
                                scope.launch {
                                    settingsStore.update { it.copy(webServerEnabled = checked) }
                                }
                            }
                        )
                    }
                )
            }

            item {
                AnimatedVisibility(visible = serverState.isRunning) {
                    ListItem(
                        headlineContent = { Text("LAN Address") },
                        supportingContent = {
                            Text("http://${serverState.hostname ?: "localhost"}:${serverState.port}")
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
