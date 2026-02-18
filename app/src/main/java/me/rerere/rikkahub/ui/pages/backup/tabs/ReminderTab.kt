package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig

    fun updateConfig(update: BackupReminderConfig) {
        vm.updateSettings(settings.copy(backupReminderConfig = update))
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_reminder_enable)) },
                    tail = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { updateConfig(config.copy(enabled = it)) }
                        )
                    }
                )
                if (config.enabled) {
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_reminder_interval)) }
                    ) {
                        val intervals = listOf(1, 3, 7, 14, 30)
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            intervals.forEachIndexed { index, days ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = intervals.size,
                                    ),
                                    onClick = { updateConfig(config.copy(intervalDays = days)) },
                                    selected = config.intervalDays == days,
                                ) {
                                    Text(stringResource(R.string.backup_page_reminder_interval_days, days))
                                }
                            }
                        }
                    }
                    val lastBackupText = if (config.lastBackupTime == 0L) {
                        stringResource(R.string.backup_page_reminder_no_record)
                    } else {
                        stringResource(
                            R.string.backup_page_reminder_last_time,
                            Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime()
                        )
                    }
                    Text(
                        text = lastBackupText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
