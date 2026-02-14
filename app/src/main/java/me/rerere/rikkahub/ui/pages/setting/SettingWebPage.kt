package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Square
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
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
    var accessPasswordText by remember(settings.webServerAccessPassword) {
        mutableStateOf(settings.webServerAccessPassword)
    }
    var passwordVisible by remember {
        mutableStateOf(false)
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    val checked = !serverState.isRunning
                    if (checked) {
                        webServerManager.start(port = settings.webServerPort)
                    } else {
                        webServerManager.stop()
                    }
                    scope.launch {
                        settingsStore.update { it.copy(webServerEnabled = checked) }
                    }
                },
                icon = {
                    if (serverState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (serverState.isRunning) Lucide.Square else Lucide.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (serverState.isRunning) {
                            stringResource(R.string.setting_page_web_server_stop)
                        } else {
                            stringResource(R.string.setting_page_web_server_start)
                        }
                    )
                },
                containerColor = if (serverState.isRunning) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
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
                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_port)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_desc)) },
                    trailingContent = {
                        TextField(
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
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.webServerJwtEnabled,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    settingsStore.update {
                                        it.copy(webServerJwtEnabled = checked)
                                    }
                                }
                            },
                            enabled = settings.webServerJwtEnabled || accessPasswordText.isNotBlank(),
                        )
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_password)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_web_server_password_desc)) },
                    trailingContent = {
                        TextField(
                            value = accessPasswordText,
                            onValueChange = { value ->
                                accessPasswordText = value
                                scope.launch {
                                    settingsStore.update {
                                        it.copy(
                                            webServerAccessPassword = value,
                                            webServerJwtEnabled = it.webServerJwtEnabled && value.isNotBlank()
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Lucide.EyeOff else Lucide.Eye,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            isError = settings.webServerJwtEnabled && accessPasswordText.isBlank(),
                            modifier = Modifier.width(180.dp),
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                    }
                )
            }

            item {
                AnimatedVisibility(visible = serverState.isRunning) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_lan_address)) },
                        supportingContent = {
                            Text("http://${serverState.address ?: "localhost"}:${serverState.port}")
                        }
                    )
                }
            }

            item {
                AnimatedVisibility(visible = serverState.isRunning && serverState.hostname != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_mdns_address)) },
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
                                text = stringResource(R.string.setting_page_web_server_error),
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
