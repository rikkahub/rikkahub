package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup {
                    item(
                        onClick = {
                            selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                            showUpdatePauseDialog = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = {
                            Text(
                                if (updateChecksEnabled) {
                                    stringResource(R.string.setting_update_reminder_enabled)
                                } else {
                                    stringResource(
                                        R.string.setting_update_reminder_paused_until,
                                        Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis)
                                            .toLocalDateTime(),
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    switchItem(
                        checked = displaySetting.enableNotificationOnMessageGeneration,
                        onCheckedChange = {
                            if (it && !permissionState.allPermissionsGranted) {
                                permissionState.requestPermissions()
                            }
                            updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                        },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        switchItem(
                            checked = displaySetting.enableLiveUpdateNotification,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        )
                    }
                }
            }
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    ConnectedChoiceButtons(
                        options = UPDATE_PAUSE_DAY_OPTIONS,
                        selected = selectedUpdatePauseDays,
                        onSelect = { selectedUpdatePauseDays = it },
                        // 对话框底色和默认的按钮底色一样，换一个能分开的
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                    ) { days, selected ->
                        ConnectedChoiceLabel(
                            text = stringResource(R.string.setting_update_reminder_pause_days, days),
                            selected = selected,
                        )
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
