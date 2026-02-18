package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.DatabaseBackup
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings

@Composable
fun BackupReminderCard(
    settings: Settings,
    onClick: () -> Unit,
) {
    val config = settings.backupReminderConfig
    var dismissed by remember { mutableStateOf(false) }

    val isDue = config.enabled &&
        (System.currentTimeMillis() - config.lastBackupTime) > config.intervalDays * 24L * 60 * 60 * 1000

    if (!isDue || dismissed) return

    Card(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.DatabaseBackup,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_page_reminder_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val lastBackupText = if (config.lastBackupTime == 0L) {
                    stringResource(R.string.backup_page_reminder_never_backed_up)
                } else {
                    val days = (System.currentTimeMillis() - config.lastBackupTime) / (24L * 60 * 60 * 1000)
                    stringResource(R.string.backup_page_reminder_last_days, days)
                }
                Text(
                    text = lastBackupText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.backup_page_reminder_dismiss),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
