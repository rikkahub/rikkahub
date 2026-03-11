package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionInfo
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxProtocol
import me.rerere.rikkahub.data.ai.tools.termux.TermuxWorkdirServerManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val TERMUX_ALLOW_EXTERNAL_APPS_COMMAND =
    "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && " +
        "termux-reload-settings"

@Composable
fun SettingTermuxPage() {
    val termuxWorkdirServerManager: TermuxWorkdirServerManager = koinInject()
    val termuxPtySessionManager: TermuxPtySessionManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val termuxWorkdirServerState by termuxWorkdirServerManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var workdirText by remember(settings.termuxWorkdir) {
        mutableStateOf(settings.termuxWorkdir)
    }
    var workdirServerPortText by remember(settings.termuxWorkdirServerPort) {
        mutableStateOf(settings.termuxWorkdirServerPort.toString())
    }
    var timeoutText by remember(settings.termuxTimeoutMs) {
        mutableStateOf(settings.termuxTimeoutMs.toString())
    }
    var ptyServerPortText by remember(settings.termuxPtyServerPort) {
        mutableStateOf(settings.termuxPtyServerPort.toString())
    }
    var ptyYieldTimeText by remember(settings.termuxPtyYieldTimeMs) {
        mutableStateOf(settings.termuxPtyYieldTimeMs.toString())
    }
    var ptyMaxOutputCharsText by remember(settings.termuxPtyMaxOutputChars) {
        mutableStateOf(settings.termuxPtyMaxOutputChars.toString())
    }
    var approvalBlacklistText by remember(settings.termuxApprovalBlacklist) {
        mutableStateOf(settings.termuxApprovalBlacklist)
    }
    var ptySessions by remember { mutableStateOf<List<TermuxPtySessionInfo>>(emptyList()) }
    var ptySessionsLoading by remember { mutableStateOf(false) }
    var ptySessionError by remember { mutableStateOf<String?>(null) }
    var ptyServerRunning by remember { mutableStateOf(false) }
    var ptySessionsChecked by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    val openTermuxFailedText = stringResource(R.string.setting_termux_page_open_termux_failed)
    val ptyRefreshHintText = stringResource(R.string.setting_termux_page_pty_refresh_hint)
    val lifecycleOwner = LocalLifecycleOwner.current

    val termuxRunCommandPermissionState = rememberPermissionState(
        permission = TermuxProtocol.PERMISSION_RUN_COMMAND,
        displayName = { Text(stringResource(R.string.setting_termux_page_run_command_permission_title)) },
        usage = { Text(stringResource(R.string.setting_termux_page_run_command_permission_usage)) },
        required = true,
    )
    PermissionManager(permissionState = termuxRunCommandPermissionState)

    var allFilesAccessGranted by remember { mutableStateOf(isAllFilesAccessGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                allFilesAccessGranted = isAllFilesAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refreshPtySessions() {
        scope.launch {
            ptySessionsLoading = true
            val state = termuxPtySessionManager.listSessions()
            ptySessions = state.sessions
            ptyServerRunning = state.running
            ptySessionError = state.error
            ptySessionsChecked = true
            ptySessionsLoading = false
        }
    }

    fun clearPtySessionSnapshot() {
        ptySessions = emptyList()
        ptySessionError = null
        ptyServerRunning = false
        ptySessionsChecked = false
    }

    DisposableEffect(settings.termuxPtyServerPort) {
        clearPtySessionSnapshot()
        onDispose { }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.setting_termux_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("workdir") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_workdir_title)) },
                        description = { Text(stringResource(R.string.setting_termux_page_workdir_desc)) },
                        content = {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = workdirText,
                                onValueChange = { value ->
                                    workdirText = value
                                    scope.launch {
                                        settingsStore.update { it.copy(termuxWorkdir = value) }
                                    }
                                },
                                singleLine = true,
                            )
                        },
                    )
                }
            }

            item("workdirServer") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_workdir_server_title)) },
                        description = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.setting_termux_page_workdir_server_desc))
                                Text(
                                    stringResource(
                                        R.string.setting_termux_page_current_address,
                                        "http://127.0.0.1:${settings.termuxWorkdirServerPort}/"
                                    )
                                )
                                if (!termuxWorkdirServerState.error.isNullOrBlank()) {
                                    Text(
                                        text = termuxWorkdirServerState.error!!,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        tail = {
                            Switch(
                                checked = settings.termuxWorkdirServerEnabled,
                                enabled = !termuxWorkdirServerState.isLoading,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        termuxWorkdirServerManager.start(
                                            port = settings.termuxWorkdirServerPort,
                                            workdir = settings.termuxWorkdir,
                                        )
                                    } else {
                                        termuxWorkdirServerManager.stop()
                                    }
                                    scope.launch {
                                        settingsStore.update { it.copy(termuxWorkdirServerEnabled = enabled) }
                                    }
                                },
                            )
                        },
                        content = {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = workdirServerPortText,
                                onValueChange = { value ->
                                    workdirServerPortText = value.filter { it.isDigit() }
                                    val port = workdirServerPortText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        scope.launch {
                                            settingsStore.update { it.copy(termuxWorkdirServerPort = port) }
                                        }
                                        if (settings.termuxWorkdirServerEnabled) {
                                            termuxWorkdirServerManager.restart(
                                                port = port,
                                                workdir = settings.termuxWorkdir,
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !termuxWorkdirServerState.isRunning && !termuxWorkdirServerState.isLoading,
                                isError = workdirServerPortText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                label = { Text(stringResource(R.string.setting_termux_page_workdir_server_port_title)) },
                            )
                        },
                    )
                }
            }

            item("background") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_background_title)) },
                        description = { Text(stringResource(R.string.setting_termux_page_background_desc)) },
                        tail = {
                            Switch(
                                checked = settings.termuxRunInBackground,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsStore.update { it.copy(termuxRunInBackground = enabled) }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item("approval") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.assistant_page_local_tools_termux_needs_approval_title)) },
                        description = { Text(stringResource(R.string.assistant_page_local_tools_termux_needs_approval_desc)) },
                        tail = {
                            Switch(
                                checked = settings.termuxNeedsApproval,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsStore.update { it.copy(termuxNeedsApproval = enabled) }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item("approvalBlacklist") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_approval_blacklist_title)) },
                        description = { Text(stringResource(R.string.setting_termux_page_approval_blacklist_desc)) },
                        content = {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = approvalBlacklistText,
                                onValueChange = { value ->
                                    approvalBlacklistText = value
                                    scope.launch {
                                        settingsStore.update { it.copy(termuxApprovalBlacklist = value) }
                                    }
                                },
                                minLines = 3,
                                maxLines = 8,
                            )
                        },
                    )
                }
            }

            item("runCommandPermission") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_run_command_permission_title)) },
                        description = {
                            Text(stringResource(R.string.setting_termux_page_run_command_permission_desc))
                            Text(
                                if (termuxRunCommandPermissionState.allPermissionsGranted) {
                                    stringResource(R.string.setting_termux_page_run_command_permission_granted)
                                } else {
                                    stringResource(R.string.setting_termux_page_run_command_permission_not_granted)
                                }
                            )
                        },
                        tail = {
                            TextButton(
                                onClick = {
                                    termuxRunCommandPermissionState.requestPermissions()
                                },
                            ) {
                                Text(stringResource(R.string.setting_termux_page_run_command_permission_action))
                            }
                        },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item("allFilesAccess") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        FormItem(
                            modifier = Modifier.padding(12.dp),
                            label = { Text(stringResource(R.string.setting_termux_page_all_files_access_title)) },
                            description = {
                                Text(stringResource(R.string.setting_termux_page_all_files_access_desc))
                                Text(
                                    if (allFilesAccessGranted) {
                                        stringResource(R.string.setting_termux_page_all_files_access_granted)
                                    } else {
                                        stringResource(R.string.setting_termux_page_all_files_access_not_granted)
                                    }
                                )
                            },
                            tail = {
                                TextButton(
                                    onClick = {
                                        openAllFilesAccessSettings(context)
                                    }
                                ) {
                                    Text(stringResource(R.string.setting_termux_page_all_files_access_action))
                                }
                            },
                        )
                    }
                }
            }

            item("timeout") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_timeout_title)) },
                        description = { Text(stringResource(R.string.setting_termux_page_timeout_desc)) },
                        content = {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = timeoutText,
                                onValueChange = { value ->
                                    timeoutText = value.filter { it.isDigit() }
                                    val timeoutMs = timeoutText.toLongOrNull()
                                    if (timeoutMs != null && timeoutMs >= 1_000L) {
                                        scope.launch {
                                            settingsStore.update { it.copy(termuxTimeoutMs = timeoutMs) }
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = timeoutText.toLongOrNull()?.let { it < 1_000L } ?: true,
                            )
                        },
                    )
                }
            }

            item("ptyTuning") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    FormItem(
                        modifier = Modifier.padding(12.dp),
                        label = { Text(stringResource(R.string.setting_termux_page_pty_title)) },
                        description = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.setting_termux_page_pty_desc))
                                if (!settings.termuxPtyInteractiveEnabled) {
                                    Text(stringResource(R.string.setting_termux_page_pty_disabled_hint))
                                }
                            }
                        },
                        tail = {
                            Switch(
                                checked = settings.termuxPtyInteractiveEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(termuxPtyInteractiveEnabled = enabled)
                                        }
                                    }
                                    if (!enabled) {
                                        clearPtySessionSnapshot()
                                    }
                                },
                            )
                        },
                        content = {
                            if (settings.termuxPtyInteractiveEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = ptyServerPortText,
                                        onValueChange = { value ->
                                            ptyServerPortText = value.filter { it.isDigit() }
                                            val ptyServerPort = ptyServerPortText.toIntOrNull()
                                            if (
                                                ptyServerPort != null &&
                                                ptyServerPort in 1024..65535 &&
                                                ptyServerPort != settings.termuxPtyServerPort
                                            ) {
                                                scope.launch {
                                                    termuxPtySessionManager.stopServer()
                                                    settingsStore.update {
                                                        it.copy(termuxPtyServerPort = ptyServerPort)
                                                    }
                                                    clearPtySessionSnapshot()
                                                }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        isError = ptyServerPortText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                        label = { Text(stringResource(R.string.setting_termux_page_pty_server_port_title)) },
                                    )
                                    OutlinedTextField(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = ptyYieldTimeText,
                                        onValueChange = { value ->
                                            ptyYieldTimeText = value.filter { it.isDigit() }
                                            val ptyYieldTimeMs = ptyYieldTimeText.toLongOrNull()
                                            if (ptyYieldTimeMs != null && ptyYieldTimeMs >= 0L) {
                                                scope.launch {
                                                    settingsStore.update { it.copy(termuxPtyYieldTimeMs = ptyYieldTimeMs) }
                                                }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        isError = ptyYieldTimeText.toLongOrNull()?.let { it < 0L } ?: true,
                                        label = { Text(stringResource(R.string.setting_termux_page_pty_yield_time_title)) },
                                    )
                                    OutlinedTextField(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = ptyMaxOutputCharsText,
                                        onValueChange = { value ->
                                            ptyMaxOutputCharsText = value.filter { it.isDigit() }
                                            val ptyMaxOutputChars = ptyMaxOutputCharsText.toIntOrNull()
                                            if (ptyMaxOutputChars != null && ptyMaxOutputChars >= 256) {
                                                scope.launch {
                                                    settingsStore.update {
                                                        it.copy(termuxPtyMaxOutputChars = ptyMaxOutputChars)
                                                    }
                                                }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        isError = ptyMaxOutputCharsText.toIntOrNull()?.let { it < 256 } ?: true,
                                        label = {
                                            Text(stringResource(R.string.setting_termux_page_pty_max_output_chars_title))
                                        },
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TextButton(
                                            onClick = {
                                                refreshPtySessions()
                                            },
                                            enabled = !ptySessionsLoading,
                                        ) {
                                            Text(stringResource(R.string.setting_termux_page_pty_refresh_sessions))
                                        }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    ptySessionsLoading = true
                                                    val result = termuxPtySessionManager.closeAllSessions()
                                                    ptySessionError = result.error
                                                    val state = termuxPtySessionManager.listSessions()
                                                    ptySessions = state.sessions
                                                    ptyServerRunning = state.running
                                                    ptySessionError = state.error ?: ptySessionError
                                                    ptySessionsLoading = false
                                                }
                                            },
                                            enabled = !ptySessionsLoading && ptySessions.isNotEmpty(),
                                        ) {
                                            Text(stringResource(R.string.setting_termux_page_pty_close_all_sessions))
                                        }
                                    }
                                    if (ptySessionsLoading) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
                                            Text(stringResource(R.string.setting_termux_page_pty_loading_sessions))
                                        }
                                    }
                                    Text(
                                        stringResource(
                                            R.string.setting_termux_page_current_address,
                                            "http://127.0.0.1:${settings.termuxPtyServerPort}/"
                                        )
                                    )
                                    if (!ptySessionError.isNullOrBlank()) {
                                        Text(
                                            text = ptySessionError!!,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    if (!ptySessionsChecked) {
                                        Text(ptyRefreshHintText)
                                    } else if (ptySessions.isEmpty()) {
                                        Text(
                                            if (ptyServerRunning) {
                                                stringResource(R.string.setting_termux_page_pty_no_sessions)
                                            } else {
                                                stringResource(R.string.setting_termux_page_pty_server_not_running)
                                            }
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ptySessions.forEach { sessionInfo ->
                                                Card(
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    ),
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        Text(
                                                            text = sessionInfo.command,
                                                            style = MaterialTheme.typography.titleSmall,
                                                        )
                                                        Text(
                                                            text = stringResource(
                                                                R.string.setting_termux_page_pty_session_id,
                                                                sessionInfo.id
                                                            ),
                                                            style = MaterialTheme.typography.bodySmall,
                                                        )
                                                        Text(
                                                            text = stringResource(
                                                                R.string.setting_termux_page_pty_session_workdir,
                                                                sessionInfo.workdir
                                                            ),
                                                            style = MaterialTheme.typography.bodySmall,
                                                        )
                                                        Text(
                                                            text = if (sessionInfo.running) {
                                                                stringResource(R.string.setting_termux_page_pty_session_status_running)
                                                            } else {
                                                                stringResource(R.string.setting_termux_page_pty_session_status_finished)
                                                            },
                                                            style = MaterialTheme.typography.bodySmall,
                                                        )
                                                        sessionInfo.pid?.let { pid ->
                                                            Text(
                                                                text = stringResource(
                                                                    R.string.setting_termux_page_pty_session_pid,
                                                                    pid
                                                                ),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                        sessionInfo.exitCode?.let { exitCode ->
                                                            Text(
                                                                text = stringResource(
                                                                    R.string.setting_termux_page_pty_session_exit_code,
                                                                    exitCode
                                                                ),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                        if (sessionInfo.pendingOutputChars > 0) {
                                                            Text(
                                                                text = stringResource(
                                                                    R.string.setting_termux_page_pty_session_buffered_output,
                                                                    sessionInfo.pendingOutputChars
                                                                ),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                        Text(
                                                            text = stringResource(
                                                                R.string.setting_termux_page_pty_session_last_active,
                                                                formatRelativeTime(sessionInfo.lastAccessMs)
                                                            ),
                                                            style = MaterialTheme.typography.bodySmall,
                                                        )
                                                        TextButton(
                                                            onClick = {
                                                                scope.launch {
                                                                    ptySessionsLoading = true
                                                                    val result = termuxPtySessionManager.closeSession(sessionInfo.id)
                                                                    ptySessionError = result.error
                                                                    val state = termuxPtySessionManager.listSessions()
                                                                    ptySessions = state.sessions
                                                                    ptyServerRunning = state.running
                                                                    ptySessionError = state.error ?: ptySessionError
                                                                    ptySessionsLoading = false
                                                                }
                                                            },
                                                            enabled = !ptySessionsLoading,
                                                        ) {
                                                            Text(stringResource(R.string.setting_termux_page_pty_close_session))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item("setup") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.setting_termux_page_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.setting_termux_page_setup_step_1))
                        Text(stringResource(R.string.setting_termux_page_setup_step_2))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = TERMUX_ALLOW_EXTERNAL_APPS_COMMAND,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(TERMUX_ALLOW_EXTERNAL_APPS_COMMAND))
                                    toaster.show(copiedText)
                                },
                            ) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                        TextButton(
                            onClick = {
                                if (!openTermuxApp(context)) {
                                    toaster.show(openTermuxFailedText)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.setting_termux_page_open_termux))
                        }
                        Text(stringResource(R.string.setting_termux_page_setup_step_3))
                        Text(stringResource(R.string.setting_termux_page_setup_step_4))
                    }
                }
            }
        }
    }
}

private fun isAllFilesAccessGranted(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = packageUri
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun openTermuxApp(context: Context): Boolean {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(TermuxProtocol.TERMUX_PACKAGE_NAME)
        ?: return false
    context.startActivity(launchIntent)
    return true
}

private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "-"
    return DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
