package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.rememberCoroutineScope
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
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
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
            onUpdate = { vm.update(it) },
            // Transform-based path used by the per-tool toggles. Each tap runs inside
            // SettingsStore.update's mutex against the genuinely-current Assistant, so
            // rapid taps no longer race + clobber each other (was: tap A then B then C
            // could land with only C persisted because each tap snapshotted the same
            // pre-A Assistant from `assistant.value`).
            onUpdateAssistant = { transform -> vm.updateAssistant(transform) },
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
) {
    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        // Use the transform path so rapid taps (especially through a permission-grant
        // round-trip to system Settings) all serialise against the actual current state
        // instead of whatever stale snapshot the recomposition was holding.
        onUpdateAssistant { current ->
            current.copy(
                localTools = if (enabled) current.localTools + option
                else current.localTools - option,
            )
        }
    }

    // Setup-hint popups for toggles whose successful enable depends on user setup the
    // toggle itself can't perform: Termux's allow-external-apps property, the Telegram
    // bot token, and the cross-tool dependency hint for cron. Each is gated to fire at
    // most once per visit to this screen, and only when the missing thing is actually
    // missing (e.g. Termux dialog is suppressed if the integration was already verified
    // recently; Telegram dialog is suppressed if a token is on file).
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val telegramBotPreferences = koinInject<TelegramBotPreferences>()

    var showTermuxPostGrantDialog by remember { mutableStateOf(false) }
    var showTelegramNoTokenDialog by remember { mutableStateOf(false) }
    var showWorkflowsHintDialog by remember { mutableStateOf(false) }
    var termuxDialogShownThisVisit by remember { mutableStateOf(false) }
    var telegramDialogShownThisVisit by remember { mutableStateOf(false) }
    var cronToastShownThisVisit by remember { mutableStateOf(false) }
    var workflowsDialogShownThisVisit by remember { mutableStateOf(false) }

    val cronHintText = stringResource(R.string.assistant_page_local_tools_cron_jobs_toast_hint)
    val termuxCommand = stringResource(R.string.assistant_page_local_tools_termux_postgrant_command)
    val termuxCopiedText = stringResource(R.string.assistant_page_local_tools_termux_postgrant_copied)

    if (showTermuxPostGrantDialog) {
        AlertDialog(
            onDismissRequest = { showTermuxPostGrantDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_message))
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = termuxCommand,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.writeClipboardText(termuxCommand)
                    toaster.show(termuxCopiedText)
                    showTermuxPostGrantDialog = false
                }) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTermuxPostGrantDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showTelegramNoTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTelegramNoTokenDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_message)) },
            confirmButton = {
                TextButton(onClick = { showTelegramNoTokenDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showWorkflowsHintDialog) {
        AlertDialog(
            onDismissRequest = { showWorkflowsHintDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_message)) },
            confirmButton = {
                TextButton(onClick = { showWorkflowsHintDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
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
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.NotificationListener),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.NotificationListener, it) },
                        requiresNotificationListener = true,
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
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.CronJobs, newValue)
                            if (newValue && !cronToastShownThisVisit) {
                                cronToastShownThisVisit = true
                                toaster.show(cronHintText)
                            }
                        }
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_files),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Files),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Files, it) },
                        // MANAGE_EXTERNAL_STORAGE is a special "appop" permission. Without it,
                        // File.listFiles() on shared storage paths only returns subdirectories
                        // and the app's own creations — every pre-existing file is hidden.
                        requiresAllFilesAccess = true,
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
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Ssh, it) },
                        // Android 17 (API 37) blocks LAN socket access without
                        // ACCESS_LOCAL_NETWORK. SSH is the canonical case — every
                        // saved host lives on someone's WiFi/VPN. On older API
                        // levels the permission doesn't exist; the request silently
                        // no-ops since the manifest-declared permission isn't
                        // dangerous-protection there.
                        requiredRuntimePerms = if (Build.VERSION.SDK_INT >= 37) {
                            listOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else emptyList(),
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
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.TelegramBot, newValue)
                            if (newValue && !telegramDialogShownThisVisit) {
                                scope.launch {
                                    if (telegramBotPreferences.current().token.isBlank()) {
                                        telegramDialogShownThisVisit = true
                                        showTelegramNoTokenDialog = true
                                    }
                                }
                            }
                        }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mcp_control_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mcp_control_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.McpControl),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.McpControl, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_external_automation_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_external_automation_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ExternalAutomation),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ExternalAutomation, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_reliability_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_reliability_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Reliability),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Reliability, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sub_agents_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sub_agents_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SubAgents),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SubAgents, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cost_guards_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cost_guards_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CostGuards),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CostGuards, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Workflows),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Workflows, newValue)
                            // Workflows depend on a mix of runtime grants the toggle itself
                            // can't request (geofence needs background-location, notification
                            // triggers need notification-listener, app-launch triggers need
                            // accessibility, BT triggers need BLUETOOTH_CONNECT). Surface a
                            // one-time hint at enable so the user knows what to grant when
                            // they author a workflow whose trigger needs it.
                            if (newValue && !workflowsDialogShownThisVisit) {
                                workflowsDialogShownThisVisit = true
                                showWorkflowsHintDialog = true
                            }
                        }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_skill_import_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_skill_import_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SkillImport),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SkillImport, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_js_skills_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_js_skills_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.JsSkills),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JsSkills, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_system_intents_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_system_intents_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SystemIntents),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SystemIntents, it) }
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_browser),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_browser_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_browser_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Browser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Browser, it) },
                    )
                }
            )
        }

        // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_privileged),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SmsSend),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsSend, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.SEND_SMS),
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_wallpaper_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_wallpaper_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Wallpaper),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Wallpaper, it) },
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_keystore_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_keystore_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Keystore),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Keystore, it) },
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_nfc_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_nfc_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Nfc),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Nfc, it) },
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_external_storage_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_external_storage_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ExternalStorage),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ExternalStorage, it) },
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_archive_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_archive_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Archive),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Archive, it) },
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
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_title))
                },
                supportingContent = {
                    TermuxStatusRowSubtitle(
                        enabled = assistant.localTools.contains(LocalToolOption.Termux),
                    )
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Termux),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Termux, newValue)
                            if (newValue && !termuxDialogShownThisVisit) {
                                // Skip the dialog if a recent successful verify proves the
                                // property file is already in place — nothing new to teach.
                                val recentlyVerified = TermuxIntegration.lastVerifiedOkAtMs > 0 &&
                                    (System.currentTimeMillis() - TermuxIntegration.lastVerifiedOkAtMs) < 24L * 60 * 60 * 1000
                                if (!recentlyVerified) {
                                    termuxDialogShownThisVisit = true
                                    showTermuxPostGrantDialog = true
                                }
                            }
                        },
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
    requiresNotificationListener: Boolean = false,
    requiresAllFilesAccess: Boolean = false,
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
                        requiresNotificationListener -> PermissionHelper.hasNotificationListener(ctx)
                        requiresAllFilesAccess -> PermissionHelper.hasAllFilesAccess(ctx)
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
                            requiresNotificationListener -> "Notification access"
                            requiresAllFilesAccess -> "All files access"
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
            requiresNotificationListener -> {
                if (PermissionHelper.hasNotificationListener(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

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

            requiresAllFilesAccess -> {
                if (PermissionHelper.hasAllFilesAccess(ctx)) {
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
    val permissionMissing = remember(checked, resumeTrigger, permsKey, requiresWriteSettings, requiresDndAccess, requiresAccessibilityService, requiresNotificationListener, requiresAllFilesAccess) {
        checked && when {
            requiredRuntimePerms.isNotEmpty() -> !PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)
            requiresWriteSettings -> !PermissionHelper.hasWriteSettings(ctx)
            requiresDndAccess -> !PermissionHelper.hasDndAccess(ctx)
            requiresAccessibilityService -> !PermissionHelper.hasAccessibilityService(ctx)
            requiresNotificationListener -> !PermissionHelper.hasNotificationListener(ctx)
            requiresAllFilesAccess -> !PermissionHelper.hasAllFilesAccess(ctx)
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
                        requiresNotificationListener -> PermissionHelper.notificationListenerSettingsIntent()
                        requiresAllFilesAccess -> PermissionHelper.allFilesAccessIntent(ctx)
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

/**
 * Subtitle row for the Termux toggle: shows a colored dot summarising the integration
 * state plus a "Verify" affordance that runs an end-to-end smoke test (sends a tiny
 * command through Termux and waits for the result). The dot is ONLY green after a
 * successful verification within the last hour — that's the only signal that proves
 * `allow-external-apps=true` is actually in effect, since we cannot read Termux's
 * private home dir.
 */
@Composable
private fun TermuxStatusRowSubtitle(enabled: Boolean) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val staticState = remember(resumeTick) { TermuxIntegration.state(ctx) }

    var verifying by remember { mutableStateOf(false) }
    var lastVerifyError by remember { mutableStateOf<String?>(null) }

    // Reads the process-scoped timestamp from TermuxIntegration so a successful verify
    // earlier in this session keeps the dot green even after the user navigates off the
    // page and returns. resumeTick triggers a recompute on every onResume.
    val lastVerifiedOkAt = remember(resumeTick) { TermuxIntegration.lastVerifiedOkAtMs }
    val verifiedRecently = lastVerifiedOkAt > 0 &&
        (System.currentTimeMillis() - lastVerifiedOkAt) < 60L * 60 * 1000

    val (dotColor, label) = when {
        staticState == TermuxIntegration.State.NOT_INSTALLED ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to stringResource(R.string.assistant_page_local_tools_termux_status_not_installed)
        staticState == TermuxIntegration.State.NO_PERMISSION ->
            androidx.compose.ui.graphics.Color(0xFFF59E0B) to stringResource(R.string.assistant_page_local_tools_termux_status_no_permission)
        verifiedRecently ->
            androidx.compose.ui.graphics.Color(0xFF22C55E) to stringResource(R.string.assistant_page_local_tools_termux_status_ok)
        lastVerifyError != null ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to (lastVerifyError ?: "")
        else ->
            androidx.compose.ui.graphics.Color(0xFFEAB308) to stringResource(R.string.assistant_page_local_tools_termux_status_untested)
    }

    val verifyHint = stringResource(R.string.assistant_page_local_tools_termux_verify)
    val verifyingHint = stringResource(R.string.assistant_page_local_tools_termux_verifying)

    val canVerify = !verifying && enabled && staticState == TermuxIntegration.State.READY

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .let { if (canVerify) it.clickable {
                if (verifying) return@clickable
                verifying = true
                lastVerifyError = null
                scope.launch {
                    val result = TermuxIntegration.verify(ctx)
                    verifying = false
                    when (result) {
                        TermuxIntegration.VerifyResult.Ok -> {
                            TermuxIntegration.markVerifiedOk()
                            resumeTick++  // force recompose so verifiedRecently flips
                            toaster.show(ctx.getString(R.string.assistant_page_local_tools_termux_verify_ok))
                        }
                        TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_props_missing)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NoPermission -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_no_permission)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NotInstalled -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_status_not_installed)
                        }
                        is TermuxIntegration.VerifyResult.UnexpectedOutput -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_unexpected, result.stdout.take(60))
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        is TermuxIntegration.VerifyResult.OtherError -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = result.message
                            toaster.show(result.message, type = ToastType.Error)
                        }
                    }
                }
            } else it }
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(10.dp)
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text = if (verifying) verifyingHint
                   else if (canVerify && !verifiedRecently) "$label · $verifyHint"
                   else label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
