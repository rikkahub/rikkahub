package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.s3ConnectionItems
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.BackupFileInfo
import me.rerere.rikkahub.ui.pages.backup.components.BackupFilesSheet
import me.rerere.rikkahub.ui.pages.backup.components.RemoteBackupLayout
import me.rerere.rikkahub.ui.pages.backup.components.RemoteBackupStatus
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem

@Composable
fun S3Tab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s3Config = settings.s3Config
    val backupItemsState by vm.s3BackupItems.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackupFiles by remember { mutableStateOf(false) }
    var restoringItemId by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }

    fun updateS3Config(newConfig: S3Config) {
        vm.updateSettings { it.copy(s3Config = newConfig) }
    }

    RemoteBackupLayout(
        isBackingUp = isBackingUp,
        onTestConnection = {
            scope.launch {
                try {
                    vm.testS3()
                    toaster.show(
                        context.getString(R.string.backup_page_connection_success),
                        type = ToastType.Success
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    toaster.show(
                        context.getString(
                            R.string.backup_page_connection_failed,
                            e.message ?: ""
                        ),
                        type = ToastType.Error
                    )
                }
            }
        },
        onBackup = {
            scope.launch {
                isBackingUp = true
                runCatching {
                    vm.backupToS3()
                    vm.loadS3BackupFileItems()
                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure {
                    it.printStackTrace()
                    toaster.show(
                        it.message ?: context.getString(R.string.backup_page_unknown_error),
                        type = ToastType.Error
                    )
                }
                isBackingUp = false
            }
        },
    ) {
        RemoteBackupStatus(
            lastBackupTime = settings.backupReminderConfig.lastBackupTime,
            filesState = backupItemsState,
            onShowFiles = {
                vm.loadS3BackupFileItems()
                showBackupFiles = true
            },
        )

        CardGroup {
            s3ConnectionItems(config = s3Config, onUpdate = ::updateS3Config)
        }

        CardGroup {
            backupContentItem(
                options = S3Config.BackupItem.entries,
                selected = s3Config.items,
                onChange = { updateS3Config(s3Config.copy(items = it)) },
            ) { item ->
                when (item) {
                    S3Config.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                    S3Config.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                }
            }
        }
    }

    if (showBackupFiles) {
        BackupFilesSheet(
            title = stringResource(R.string.backup_page_s3_backup_files),
            state = backupItemsState,
            restoringName = restoringItemId,
            info = { BackupFileInfo(name = it.displayName, size = it.size, lastModified = it.lastModified) },
            onDelete = { item ->
                scope.launch {
                    runCatching {
                        vm.deleteS3BackupFile(item)
                        toaster.show(
                            context.getString(R.string.backup_page_delete_success),
                            type = ToastType.Success
                        )
                        vm.loadS3BackupFileItems()
                    }.onFailure { err ->
                        err.printStackTrace()
                        toaster.show(
                            context.getString(
                                R.string.backup_page_delete_failed,
                                err.message ?: ""
                            ),
                            type = ToastType.Error
                        )
                    }
                }
            },
            onRestore = { item ->
                scope.launch {
                    restoringItemId = item.displayName
                    runCatching {
                        vm.restoreFromS3(item = item)
                        toaster.show(
                            context.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success
                        )
                        showBackupFiles = false
                        onShowRestartDialog()
                    }.onFailure { err ->
                        err.printStackTrace()
                        toaster.show(
                            context.getString(
                                R.string.backup_page_restore_failed,
                                err.message ?: ""
                            ),
                            type = ToastType.Error
                        )
                    }
                    restoringItemId = null
                }
            },
            onDismiss = { showBackupFiles = false },
        )
    }
}
