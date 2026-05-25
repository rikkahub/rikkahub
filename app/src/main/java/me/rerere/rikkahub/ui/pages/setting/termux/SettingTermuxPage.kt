package me.rerere.rikkahub.ui.pages.setting.termux

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Settings -> Termux. Four sections:
 *
 *  1. Status — integration indicators with tap actions (Termux installed, RUN_COMMAND
 *     permission request, Open Termux, verify smoke test).
 *  2. Timeouts — command timeout, per-turn budget (all tools), verify smoke-test timeout.
 *  3. Defaults & limits — working directory, stdout/stderr caps, apt-wrap toggle.
 *  4. Help — expandable setup instructions for allow-external-apps=true.
 */
@Composable
fun SettingTermuxPage(
    vm: SettingTermuxViewModel = koinViewModel(),
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val config by vm.config.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // State-backed so the UI refreshes after permission grants or verify actions.
    var integrationState by remember { mutableStateOf(TermuxIntegration.State.NOT_INSTALLED) }
    var lastVerifiedOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        integrationState = TermuxIntegration.state(ctx)
        lastVerifiedOk = TermuxIntegration.lastVerifiedOkAtMs > 0L
    }

    // Runtime-permission launcher for RUN_COMMAND, mirroring the PermissionedSwitch flow
    // on the assistant Local-tools page. If Termux is not installed the request no-ops.
    val runCommandPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            integrationState = TermuxIntegration.state(ctx)
            toaster.show(ctx.getString(R.string.setting_termux_toast_permission_granted), type = ToastType.Success)
        } else {
            toaster.show(ctx.getString(R.string.setting_termux_status_permission_missing), type = ToastType.Error)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_termux_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section 1: Status
            CardGroup(
                title = { Text(stringResource(R.string.setting_termux_section_status)) },
            ) {
                val appInstalled = integrationState != TermuxIntegration.State.NOT_INSTALLED
                val hasPermission = integrationState == TermuxIntegration.State.READY

                item(
                    onClick = {
                        // Tap: launch Termux if installed, else open GitHub releases page.
                        if (appInstalled) {
                            runCatching {
                                ctx.startActivity(
                                    ctx.packageManager
                                        .getLaunchIntentForPackage("com.termux")
                                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ?: Intent(Intent.ACTION_VIEW).apply {
                                            data = android.net.Uri.parse("https://github.com/termux/termux-app/releases")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                )
                            }
                        } else {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("https://github.com/termux/termux-app/releases")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_termux_status_app)) },
                    supportingContent = {
                        Text(
                            if (appInstalled) stringResource(R.string.setting_termux_status_app_installed)
                            else stringResource(R.string.setting_termux_status_app_missing)
                        )
                    },
                    leadingContent = {
                        StatusDot(
                            color = if (appInstalled) StatusColor.Green else StatusColor.Red
                        )
                    },
                )
                item(
                    onClick = {
                        // Tap to request the RUN_COMMAND runtime permission. Already-granted
                        // re-requests are a no-op at the system level.
                        val perms = listOf("com.termux.permission.RUN_COMMAND")
                        if (PermissionHelper.hasRuntime(ctx, perms)) {
                            toaster.show(ctx.getString(R.string.setting_termux_toast_permission_granted), type = ToastType.Success)
                        } else {
                            runCommandPermLauncher.launch(perms.toTypedArray())
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_termux_status_permission)) },
                    supportingContent = {
                        Text(
                            if (hasPermission) stringResource(R.string.setting_termux_status_permission_granted)
                            else stringResource(R.string.setting_termux_status_permission_missing)
                        )
                    },
                    leadingContent = {
                        StatusDot(
                            color = if (hasPermission) StatusColor.Green else StatusColor.Red
                        )
                    },
                )
                item(
                    onClick = {
                        // Always-tappable: fire the launch Intent to com.termux.
                        runCatching {
                            ctx.packageManager
                                .getLaunchIntentForPackage("com.termux")
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ?.let { ctx.startActivity(it) }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_termux_status_open)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_status_open_desc)) },
                )
                item(
                    onClick = {
                        // Re-run smoke test on tap.
                        scope.launch {
                            val result = TermuxIntegration.verify(ctx, timeoutMs = config.verifyTimeoutMs)
                            if (result == TermuxIntegration.VerifyResult.Ok) {
                                TermuxIntegration.markVerifiedOk()
                            }
                            lastVerifiedOk = TermuxIntegration.lastVerifiedOkAtMs > 0L
                            integrationState = TermuxIntegration.state(ctx)
                            val msg = when (result) {
                                TermuxIntegration.VerifyResult.Ok -> ctx.getString(R.string.setting_termux_verify_ok)
                                TermuxIntegration.VerifyResult.NotInstalled -> ctx.getString(R.string.setting_termux_verify_not_installed)
                                TermuxIntegration.VerifyResult.NoPermission -> ctx.getString(R.string.setting_termux_verify_no_permission)
                                TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> ctx.getString(R.string.setting_termux_verify_allow_external_apps)
                                is TermuxIntegration.VerifyResult.UnexpectedOutput -> ctx.getString(R.string.setting_termux_verify_unexpected_output)
                                is TermuxIntegration.VerifyResult.OtherError -> ctx.getString(R.string.setting_termux_verify_other_error, result.message)
                            }
                            val type = if (result == TermuxIntegration.VerifyResult.Ok) ToastType.Success else ToastType.Error
                            toaster.show(msg, type = type)
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_termux_status_verify)) },
                    supportingContent = {
                        Text(
                            if (lastVerifiedOk) stringResource(R.string.setting_termux_status_verify_ok)
                            else stringResource(R.string.setting_termux_status_verify_unknown)
                        )
                    },
                    leadingContent = {
                        StatusDot(
                            color = when {
                                lastVerifiedOk -> StatusColor.Green
                                integrationState == TermuxIntegration.State.READY -> StatusColor.Orange
                                else -> StatusColor.Red
                            }
                        )
                    },
                )
            }

            // Section 2: Timeouts
            CardGroup(
                title = { Text(stringResource(R.string.setting_termux_section_timeouts)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_command_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_command_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = config.commandTimeoutMs / 1_000L,
                            unitLabel = stringResource(R.string.setting_termux_unit_seconds),
                            onCommit = vm::setCommandTimeoutSeconds,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_turn_budget)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_turn_budget_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = config.turnBudgetMs / 60_000L,
                            unitLabel = stringResource(R.string.setting_termux_unit_minutes),
                            onCommit = vm::setTurnBudgetMinutes,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_verify_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_verify_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = config.verifyTimeoutMs / 1_000L,
                            unitLabel = stringResource(R.string.setting_termux_unit_seconds),
                            onCommit = vm::setVerifyTimeoutSeconds,
                        )
                    },
                )
            }

            // Section 3: Defaults & limits
            CardGroup(
                title = { Text(stringResource(R.string.setting_termux_section_defaults)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_working_dir)) },
                    supportingContent = {
                        WorkingDirInput(
                            currentValue = config.defaultWorkingDir,
                            onCommit = vm::setDefaultWorkingDir,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_max_stdout)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_max_stdout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = config.maxStdoutBytes.toLong(),
                            unitLabel = stringResource(R.string.setting_termux_unit_bytes),
                            onCommit = { vm.setMaxStdoutBytes(it.toInt()) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_max_stderr)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_max_stderr_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = config.maxStderrBytes.toLong(),
                            unitLabel = stringResource(R.string.setting_termux_unit_bytes),
                            onCommit = { vm.setMaxStderrBytes(it.toInt()) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_apt_wrap)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_apt_wrap_desc)) },
                    trailingContent = {
                        Switch(
                            checked = config.aptWrapEnabled,
                            onCheckedChange = vm::setAptWrapEnabled,
                        )
                    },
                )
            }

            // Section 4: Help
            CardGroup(
                title = { Text(stringResource(R.string.setting_termux_section_help)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_help_allow_external_apps_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_help_allow_external_apps_body)) },
                )
            }
        }
    }
}

private enum class StatusColor { Green, Orange, Red }

@Composable
private fun StatusDot(color: StatusColor) {
    val tint = when (color) {
        StatusColor.Green  -> MaterialTheme.colorScheme.primary
        StatusColor.Orange -> MaterialTheme.colorScheme.tertiary
        StatusColor.Red    -> MaterialTheme.colorScheme.error
    }
    // Simple colored circle. size() is required — without it the Canvas measures 0x0 and
    // nothing renders. Matches the Canvas(Modifier.size(10.dp)) pattern in AssistantLocalToolPage.
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(10.dp)
            .padding(end = 4.dp, top = 2.dp),
    ) {
        drawCircle(color = tint)
    }
}

/**
 * Compact numeric input for a timeout/cap row's trailing slot. Mirrors [TimeoutInput] in
 * [me.rerere.rikkahub.ui.pages.setting.browser.SettingBrowserPage] in shape, but uses
 * .take(6) instead of .take(4) to accommodate the 5-digit stdout/stderr byte caps.
 */
@Composable
private fun TimeoutInput(
    currentValue: Long,
    unitLabel: String,
    onCommit: (Long) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() }.take(6) },
        singleLine = true,
        suffix = { Text(unitLabel, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(148.dp)
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    val parsed = text.toLongOrNull()
                    if (parsed != null && parsed != currentValue) {
                        onCommit(parsed)
                    } else {
                        text = currentValue.toString()
                    }
                }
            },
    )
}

/**
 * Single-line text field for the working directory setting. Commits on focus loss, same
 * pattern as [TimeoutInput]. An empty submit restores the current value.
 */
@Composable
private fun WorkingDirInput(
    currentValue: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    if (text != currentValue && text.isNotBlank()) {
                        onCommit(text)
                    } else {
                        text = currentValue
                    }
                }
            },
    )
}
