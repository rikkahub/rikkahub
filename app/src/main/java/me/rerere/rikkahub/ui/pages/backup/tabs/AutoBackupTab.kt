package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.backup.BackupAutomationManager
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun AutoBackupTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val webDavConfigured = BackupAutomationManager.isWebDavConfigured(settings.webDavConfig)
    val s3Configured = BackupAutomationManager.isS3Configured(settings.s3Config)

    fun update(newSettings: Settings) {
        vm.updateSettings(newSettings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_auto_reminder_title)) },
                    description = { Text(stringResource(R.string.backup_auto_reminder_desc)) },
                    tail = {
                        Switch(
                            checked = settings.backupReminderEnabled,
                            onCheckedChange = {
                                update(settings.copy(backupReminderEnabled = it))
                            }
                        )
                    }
                )
                FormItem(
                    label = { Text(stringResource(R.string.backup_auto_reminder_interval_days)) },
                    description = { Text(stringResource(R.string.backup_auto_interval_desc)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = settings.backupReminderIntervalDays.toString(),
                        onValueChange = { input ->
                            input.toIntOrNull()?.let { days ->
                                update(settings.copy(backupReminderIntervalDays = days.coerceAtLeast(1)))
                            }
                        },
                        singleLine = true
                    )
                }
            }
        }

        AutoBackupProviderSection(
            title = stringResource(R.string.backup_page_webdav_backup),
            configured = webDavConfigured,
            autoEnabled = settings.webDavConfig.autoBackupEnabled,
            onAutoEnabledChange = { enabled ->
                update(settings.copy(webDavConfig = settings.webDavConfig.copy(autoBackupEnabled = enabled)))
            },
            onLaunchEnabled = settings.webDavConfig.autoBackupOnAppLaunch,
            onLaunchEnabledChange = { enabled ->
                update(settings.copy(webDavConfig = settings.webDavConfig.copy(autoBackupOnAppLaunch = enabled)))
            },
            intervalDays = settings.webDavConfig.autoBackupIntervalDays,
            onIntervalDaysChange = { days ->
                update(settings.copy(webDavConfig = settings.webDavConfig.copy(autoBackupIntervalDays = days)))
            },
            lastBackupAtEpochMillis = settings.webDavConfig.lastBackupAtEpochMillis,
            lastError = settings.webDavConfig.lastAutoBackupError,
        )

        AutoBackupProviderSection(
            title = stringResource(R.string.backup_page_s3_backup),
            configured = s3Configured,
            autoEnabled = settings.s3Config.autoBackupEnabled,
            onAutoEnabledChange = { enabled ->
                update(settings.copy(s3Config = settings.s3Config.copy(autoBackupEnabled = enabled)))
            },
            onLaunchEnabled = settings.s3Config.autoBackupOnAppLaunch,
            onLaunchEnabledChange = { enabled ->
                update(settings.copy(s3Config = settings.s3Config.copy(autoBackupOnAppLaunch = enabled)))
            },
            intervalDays = settings.s3Config.autoBackupIntervalDays,
            onIntervalDaysChange = { days ->
                update(settings.copy(s3Config = settings.s3Config.copy(autoBackupIntervalDays = days)))
            },
            lastBackupAtEpochMillis = settings.s3Config.lastBackupAtEpochMillis,
            lastError = settings.s3Config.lastAutoBackupError,
        )
    }
}

@Composable
private fun AutoBackupProviderSection(
    title: String,
    configured: Boolean,
    autoEnabled: Boolean,
    onAutoEnabledChange: (Boolean) -> Unit,
    onLaunchEnabled: Boolean,
    onLaunchEnabledChange: (Boolean) -> Unit,
    intervalDays: Int,
    onIntervalDaysChange: (Int) -> Unit,
    lastBackupAtEpochMillis: Long,
    lastError: String,
) {
    val statusText = if (configured) {
        stringResource(R.string.backup_auto_configured)
    } else {
        stringResource(R.string.backup_auto_not_configured)
    }
    val lastBackupText = if (lastBackupAtEpochMillis > 0) {
        Instant.ofEpochMilli(lastBackupAtEpochMillis).toLocalDateTime()
    } else {
        stringResource(R.string.backup_auto_never)
    }

    OutlinedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title)
            Text(text = statusText)
            FormItem(
                label = { Text(stringResource(R.string.backup_auto_enable)) },
                description = { Text(stringResource(R.string.backup_auto_enable_desc)) },
                tail = {
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = onAutoEnabledChange,
                        enabled = configured
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.backup_auto_on_launch)) },
                description = { Text(stringResource(R.string.backup_auto_on_launch_desc)) },
                tail = {
                    Switch(
                        checked = onLaunchEnabled,
                        onCheckedChange = onLaunchEnabledChange,
                        enabled = configured && autoEnabled
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.backup_auto_interval_days)) },
                description = { Text(stringResource(R.string.backup_auto_interval_desc)) }
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = intervalDays.toString(),
                    onValueChange = { input ->
                        input.toIntOrNull()?.let { days ->
                            onIntervalDaysChange(days.coerceAtLeast(1))
                        }
                    },
                    singleLine = true,
                    enabled = configured && autoEnabled
                )
            }
            Text(text = stringResource(R.string.backup_auto_last_success, lastBackupText))
            if (lastError.isNotBlank()) {
                Text(text = stringResource(R.string.backup_auto_last_error, lastError))
            }
        }
    }
}

