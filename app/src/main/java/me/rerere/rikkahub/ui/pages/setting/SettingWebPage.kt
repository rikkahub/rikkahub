package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.StopCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    var portText by remember(settings.webServerPort) {
        mutableStateOf(settings.webServerPort.toString())
    }
    var accessPasswordText by remember(settings.webServerAccessPassword) {
        mutableStateOf(settings.webServerAccessPassword)
    }
    var passwordVisible by remember {
        mutableStateOf(false)
    }

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    fun startWebServer() {
        val intent = Intent(context, WebServerService::class.java).apply {
            action = WebServerService.ACTION_START
            putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
            putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
        }
        context.startForegroundService(intent)
        scope.launch {
            settingsStore.update { it.copy(webServerEnabled = true) }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {
            pendingStart = false
            startWebServer()
        }
    }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        toaster.show(copiedText)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        if (permissionState.allPermissionsGranted) {
                            startWebServer()
                        } else {
                            pendingStart = true
                            permissionState.requestPermissions()
                        }
                    } else {
                        val intent = Intent(context, WebServerService::class.java).apply {
                            action = WebServerService.ACTION_STOP
                        }
                        context.startService(intent)
                        scope.launch {
                            settingsStore.update { it.copy(webServerEnabled = false) }
                        }
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
                            imageVector = if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play,
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
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
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.webServerLocalhostOnly,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(webServerLocalhostOnly = checked)
                                        }
                                    }
                                },
                                // 运行中不允许切换 需重启服务生效
                                enabled = !serverState.isRunning,
                            )
                        },
                    )
                    item(
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
                        },
                    )
                    item(
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
                                            imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
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
                        },
                    )
                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            val lanUrl = "http://${serverState.address ?: "localhost"}:$port"
                            item(
                                onClick = { copyUrl(lanUrl) },
                                headlineContent = { Text(stringResource(R.string.setting_page_web_server_lan_address)) },
                                supportingContent = { Text(lanUrl) },
                            )

                            if (serverState.hostname != null) {
                                val mdnsUrl = "http://${serverState.hostname}:$port"
                                item(
                                    onClick = { copyUrl(mdnsUrl) },
                                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_mdns_address)) },
                                    supportingContent = { Text(mdnsUrl) },
                                )
                            }
                        }

                        val localUrl = "http://localhost:$port"
                        item(
                            onClick = { copyUrl(localUrl) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server_local_address)) },
                            supportingContent = { Text(localUrl) },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (serverState.error != null) {
                        item(
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
                            },
                        )
                    }
                }
            }
        }
    }
}
