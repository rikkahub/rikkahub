package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Built-in tools section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_existing),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
        }

        // Device info section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_device_info),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_battery_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_battery_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Battery),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Battery, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_audio_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_audio_info_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.AudioInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AudioInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telephony_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telephony_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.TelephonyInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TelephonyInfo, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_PHONE_STATE),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_wifi_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_wifi_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.WifiInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.WifiInfo, it) },
                        requiredRuntimePerms = listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sensors_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sensors_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Sensors),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Sensors, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_storage_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_storage_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.StorageInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.StorageInfo, it) }
                    )
                }
            )
        }

        // Output section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_output),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_toast_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_toast_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Toast),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Toast, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Notification),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Notification, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_share_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_share_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Share),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Share, it) }
                    )
                }
            )
        }

        // Hardware control section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_hardware),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_torch_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_torch_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Torch),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Torch, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_vibrate_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_vibrate_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Vibrate),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Vibrate, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Brightness),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Brightness, it) },
                        requiresWriteSettings = true,
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Volume),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Volume, it) },
                        requiresDndAccess = true,
                    )
                }
            )
        }

        // Personal data section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_personal_data),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Location),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Location, it) },
                        requiredRuntimePerms = listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Contacts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Contacts, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CONTACTS),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CallLog),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CallLog, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CALL_LOG),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SmsInbox),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsInbox, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_SMS),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CameraPhoto),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CameraPhoto, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.CAMERA),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.MicRecorder),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.MicRecorder, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SpeechToText),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SpeechToText, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Fingerprint),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Fingerprint, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.USE_BIOMETRIC),
                    )
                }
            )
        }

        // Media section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_media),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_media_player_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_media_player_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.MediaPlayer),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.MediaPlayer, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_media_scanner_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_media_scanner_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.MediaScanner),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.MediaScanner, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_download_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_download_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Download),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Download, it) }
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_automation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CronJobs),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CronJobs, it) }
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_network),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Ssh),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Ssh, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.TelegramBot),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TelegramBot, it) }
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_screen_automation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenAutomation),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenAutomation, it) },
                        requiresAccessibilityService = true,
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_app_launcher_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_app_launcher_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.AppLauncher),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AppLauncher, it) },
                        // Termux's RUN_COMMAND service is gated behind a dangerous-level
                        // custom permission. Requesting it through the standard runtime flow
                        // pops the system dialog so termux_run_command works without an adb
                        // grant. If Termux is not installed the request silently no-ops.
                        requiredRuntimePerms = listOf("com.termux.permission.RUN_COMMAND"),
                    )
                }
            )
        }
    }
}

@Composable
private fun PermissionedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    requiredRuntimePerms: List<String> = emptyList(),
    requiresWriteSettings: Boolean = false,
    requiresDndAccess: Boolean = false,
    requiresAccessibilityService: Boolean = false,
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val deniedToastFmt = stringResource(R.string.assistant_page_local_tools_perm_denied_toast)

    var showDialog by remember { mutableStateOf(false) }
    var pendingSpecialResume by remember { mutableStateOf(false) }
    // Bumped on ON_RESUME so permissionMissing recomputes when the user returns from settings.
    var resumeTrigger by remember { mutableStateOf(0) }

    // Runtime permission launcher
    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            onCheckedChange(true)
        } else {
            toaster.show(
                message = String.format(deniedToastFmt, denied.joinToString(", ")),
                type = ToastType.Error,
            )
        }
    }

    // Special permission settings launcher
    val specialPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // result is ignored — actual check happens on ON_RESUME
    }

    // Lifecycle observer: handles both special-perm resume and re-evaluating
    // the permissionMissing hint when the user returns from settings.
    // Keyed on lifecycleOwner only — the closure reads other state, so re-installing
    // on every state change would be wasteful (and was a measured perf bug across the
    // ~30 PermissionedSwitch instances rendered on this screen).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTrigger++
                if (pendingSpecialResume) {
                    val granted = when {
                        requiresWriteSettings -> PermissionHelper.hasWriteSettings(ctx)
                        requiresDndAccess -> PermissionHelper.hasDndAccess(ctx)
                        requiresAccessibilityService -> PermissionHelper.hasAccessibilityService(ctx)
                        else -> false
                    }
                    pendingSpecialResume = false
                    if (granted) {
                        onCheckedChange(true)
                    } else {
                        val name = when {
                            requiresWriteSettings -> "WRITE_SETTINGS"
                            requiresDndAccess -> "DND access"
                            requiresAccessibilityService -> "Accessibility service"
                            else -> ""
                        }
                        toaster.show(
                            message = String.format(deniedToastFmt, name),
                            type = ToastType.Error,
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun requestPermission() {
        when {
            requiresAccessibilityService -> {
                if (PermissionHelper.hasAccessibilityService(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresWriteSettings -> {
                if (PermissionHelper.hasWriteSettings(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresDndAccess -> {
                if (PermissionHelper.hasDndAccess(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiredRuntimePerms.isNotEmpty() -> {
                if (PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)) {
                    onCheckedChange(true)
                } else {
                    runtimePermLauncher.launch(requiredRuntimePerms.toTypedArray())
                }
            }

            else -> onCheckedChange(true)
        }
    }

    // Recomputed each ON_RESUME via resumeTrigger so the hint reflects any
    // permissions the user toggled in system settings while we were paused.
    // Key on a stable string derived from the perms list — Compose can't compare
    // raw List<String> for structural equality across recomps, so passing the list
    // directly invalidated this remember on every parent recomp.
    val permsKey = remember(requiredRuntimePerms) { requiredRuntimePerms.joinToString(",") }
    val permissionMissing = remember(checked, resumeTrigger, permsKey, requiresWriteSettings, requiresDndAccess, requiresAccessibilityService) {
        checked && when {
            requiredRuntimePerms.isNotEmpty() -> !PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)
            requiresWriteSettings -> !PermissionHelper.hasWriteSettings(ctx)
            requiresDndAccess -> !PermissionHelper.hasDndAccess(ctx)
            requiresAccessibilityService -> !PermissionHelper.hasAccessibilityService(ctx)
            else -> false
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_title))
            },
            text = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val intent = when {
                        requiresWriteSettings -> PermissionHelper.writeSettingsIntent(ctx)
                        requiresDndAccess -> PermissionHelper.dndAccessIntent(ctx)
                        requiresAccessibilityService -> PermissionHelper.accessibilitySettingsIntent()
                        else -> null
                    }
                    if (intent != null) {
                        pendingSpecialResume = true
                        specialPermLauncher.launch(intent)
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(horizontalAlignment = Alignment.End) {
        Switch(
            checked = checked,
            onCheckedChange = { newChecked ->
                if (!newChecked) {
                    onCheckedChange(false)
                    return@Switch
                }
                // Turning ON
                requestPermission()
            }
        )
        if (permissionMissing) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_perm_needed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { requestPermission() },
            )
        }
    }
}
