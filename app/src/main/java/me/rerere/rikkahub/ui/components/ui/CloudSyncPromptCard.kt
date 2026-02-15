package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CloudSyncProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun CloudSyncPromptCard(
    vm: ChatVM,
    settings: Settings,
    onShowRestartDialog: () -> Unit,
) {
    val prompt = settings.pendingCloudSyncPrompt ?: return
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var showConfirmDialog by remember { mutableStateOf(false) }
    val providerText = when (prompt.provider) {
        CloudSyncProvider.WEBDAV -> stringResource(R.string.backup_cloud_sync_provider_webdav)
        CloudSyncProvider.S3 -> stringResource(R.string.backup_cloud_sync_provider_s3)
    }
    val backupTime = Instant.ofEpochMilli(prompt.backupLastModifiedEpochMillis).toLocalDateTime()

    Card {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.backup_cloud_sync_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    R.string.backup_cloud_sync_prompt_desc,
                    providerText,
                    prompt.backupDisplayName,
                    backupTime
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = { vm.dismissPendingCloudSyncPrompt() }
                ) {
                    Text(stringResource(R.string.backup_cloud_sync_later))
                }
                TextButton(
                    onClick = { showConfirmDialog = true }
                ) {
                    Text(stringResource(R.string.backup_cloud_sync_now))
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_cloud_sync_confirm_title)) },
            text = { Text(stringResource(R.string.backup_cloud_sync_confirm_desc)) },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.syncFromPendingCloudPrompt { result ->
                        result
                            .onSuccess {
                                showConfirmDialog = false
                                toaster.show(
                                    context.getString(R.string.backup_cloud_sync_success),
                                    type = ToastType.Success
                                )
                                onShowRestartDialog()
                            }
                            .onFailure {
                                toaster.show(
                                    context.getString(
                                        R.string.backup_cloud_sync_failed,
                                        it.message ?: ""
                                    ),
                                    type = ToastType.Error
                                )
                            }
                    }
                }) {
                    Text(stringResource(R.string.backup_cloud_sync_now))
                }
            }
        )
    }
}
