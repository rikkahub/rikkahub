package me.rerere.rikkahub.ui.pages.backup.tabs

import android.util.Log
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Upload02
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormTextField
import me.rerere.rikkahub.ui.components.ui.SegmentedButtonLabel
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupOperationState
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.common.state.UiState
import me.rerere.common.text.fileSizeToString
import me.rerere.common.state.onError
import me.rerere.common.state.onLoading
import me.rerere.common.state.onSuccess
import me.rerere.common.time.toLocalDateTime
import java.time.Instant

private const val TAG = "WebDavTab"
private val passwordOutputTransformation = OutputTransformation {
    replace(0, length, "\u2022".repeat(length))
}

@Composable
fun WebDavTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val webDavConfig = settings.webDavConfig
    val backupItemsState by vm.webDavBackupItems.collectAsStateWithLifecycle()
    val operationState by vm.operationState.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackupFiles by remember { mutableStateOf(false) }

    val isBackingUp = operationState ==
        BackupOperationState.Running(BackupOperationState.Kind.WebDavBackup)
    // Carry the active item's display name in Running.message so only that row shows the spinner,
    // matching the old restoringItemId behavior with no extra UI-owned state.
    val restoringItemName = (operationState as? BackupOperationState.Running)
        ?.takeIf { it.kind == BackupOperationState.Kind.WebDavRestore }
        ?.message

    // Terminal backup/restore outcomes are owned by the VM now; the tab only renders them. Both
    // kinds map to the same success/failure toast text the inline handlers showed before.
    LaunchedEffect(operationState) {
        when (val state = operationState) {
            is BackupOperationState.Success -> {
                when (state.kind) {
                    BackupOperationState.Kind.WebDavBackup -> {
                        vm.loadBackupFileItems()
                        toaster.show(
                            context.getString(R.string.backup_page_backup_success),
                            type = ToastType.Success
                        )
                        vm.acknowledgeOperation()
                    }

                    BackupOperationState.Kind.WebDavRestore -> {
                        toaster.show(
                            context.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success
                        )
                        vm.acknowledgeOperation()
                    }

                    else -> Unit
                }
            }

            is BackupOperationState.Error -> {
                when (state.kind) {
                    BackupOperationState.Kind.WebDavBackup,
                    BackupOperationState.Kind.WebDavRestore -> {
                        toaster.show(state.message, type = ToastType.Error)
                        vm.acknowledgeOperation()
                    }

                    else -> Unit
                }
            }

            is BackupOperationState.Running, BackupOperationState.Idle -> Unit
        }
    }

    fun updateWebDavConfig(newConfig: WebDavConfig) {
        vm.updateSettings(settings.copy(webDavConfig = newConfig))
    }

    val lastBackupText = if (settings.backupReminderConfig.lastBackupTime == 0L) {
        stringResource(R.string.backup_page_reminder_no_record)
    } else {
        stringResource(
            R.string.backup_page_reminder_last_time,
            Instant.ofEpochMilli(settings.backupReminderConfig.lastBackupTime).toLocalDateTime()
        )
    }
    val backupFileSummary = when (val state = backupItemsState) {
        is UiState.Success -> "${stringResource(R.string.backup_page_files)}: ${state.data.size}"
        UiState.Loading -> "${stringResource(R.string.backup_page_files)}: ..."
        UiState.Idle -> "${stringResource(R.string.backup_page_files)}: -"
        is UiState.Error -> "${stringResource(R.string.backup_page_files)}: -"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupStatusCard(
                title = stringResource(R.string.backup_page_webdav_backup),
                lastBackupText = lastBackupText,
                fileSummaryText = backupFileSummary
            )

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_webdav_server_address)) },
                    supportingContent = {
                        FormTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.url,
                            externalKey = "webdav:url",
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(url = it.trim())) },
                            placeholder = { Text("https://example.com/dav") },
                            singleLine = true
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_username)) },
                    supportingContent = {
                        FormTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.username,
                            externalKey = "webdav:username",
                            onValueChange = {
                                updateWebDavConfig(
                                    webDavConfig.copy(
                                        username = it.trim()
                                    )
                                )
                            },
                            singleLine = true
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_password)) },
                    supportingContent = {
                        var passwordVisible by remember { mutableStateOf(false) }
                        FormTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.password,
                            externalKey = "webdav:password",
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(password = it.trim())) },
                            outputTransformation = if (passwordVisible) null else passwordOutputTransformation,
                            trailingIcon = {
                                val image = if (passwordVisible) {
                                    HugeIcons.ViewOff
                                } else {
                                    HugeIcons.View
                                }
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(imageVector = image, contentDescription = null)
                                }
                            },
                            singleLine = true
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_path)) },
                    supportingContent = {
                        FormTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.path,
                            externalKey = "webdav:path",
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(path = it.trim())) },
                            singleLine = true
                        )
                    },
                )
            }

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                    MultiChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        WebDavConfig.BackupItem.entries.forEachIndexed { index, item ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = WebDavConfig.BackupItem.entries.size
                                ),
                                onCheckedChange = { checked ->
                                    val newItems = if (checked) {
                                        webDavConfig.items + item
                                    } else {
                                        webDavConfig.items - item
                                    }
                                    updateWebDavConfig(webDavConfig.copy(items = newItems))
                                },
                                checked = item in webDavConfig.items
                            ) {
                                SegmentedButtonLabel(
                                    when (item) {
                                        WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                        WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                    }
                                )
                            }
                        }
                    }
                    },
                )
            }
        }

        HorizontalDivider()
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            vm.testWebDav()
                            toaster.show(
                                context.getString(R.string.backup_page_connection_success),
                                type = ToastType.Success
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "WebDav connection test failed", e)
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_connection_failed,
                                    e.message ?: ""
                                ),
                                type = ToastType.Error
                            )
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.backup_page_test_connection))
            }
            OutlinedButton(
                onClick = {
                    vm.loadBackupFileItems()
                    showBackupFiles = true
                }
            ) {
                Text(stringResource(R.string.backup_page_restore))
            }
            Button(
                onClick = { vm.backup() },
                enabled = !isBackingUp
            ) {
                if (isBackingUp) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(HugeIcons.Upload02, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isBackingUp) {
                        stringResource(R.string.backup_page_backing_up)
                    } else {
                        stringResource(R.string.backup_page_backup_now)
                    }
                )
            }
        }
    }

    if (showBackupFiles) {
        ModalBottomSheet(
            onDismissRequest = {
                showBackupFiles = false
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.backup_page_webdav_backup_files),
                    modifier = Modifier.fillMaxWidth()
                )
                backupItemsState.onSuccess {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(it, key = { backupItem -> backupItem.href }) { item ->
                            WebDavBackupItemCard(
                                item = item,
                                isRestoring = restoringItemName == item.displayName,
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            vm.deleteWebDavBackupFile(item)
                                            toaster.show(
                                                context.getString(R.string.backup_page_delete_success),
                                                type = ToastType.Success
                                            )
                                            vm.loadBackupFileItems()
                                        }.onFailure { err ->
                                            Log.e(TAG, "WebDav backup delete failed", err)
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
                                onRestore = { restoreItem ->
                                    // Restore success/error toasts come from the operationState
                                    // LaunchedEffect; the onSuccess hook only drives UI navigation.
                                    vm.restore(item = restoreItem) {
                                        showBackupFiles = false
                                        onShowRestartDialog()
                                    }
                                },
                            )
                        }
                    }
                }.onError {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.backup_page_loading_failed, it.message ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }.onLoading {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    title: String,
    lastBackupText: String,
    fileSummaryText: String,
) {
    CardGroup {
        item(
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = lastBackupText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = fileSummaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
        )
    }
}

@Composable
private fun WebDavBackupItemCard(
    item: WebDavBackupItem,
    isRestoring: Boolean = false,
    onDelete: (WebDavBackupItem) -> Unit = {},
    onRestore: (WebDavBackupItem) -> Unit = {},
) {
    CardGroup {
        item(
            headlineContent = {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.lastModified.toLocalDateTime(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = item.size.fileSizeToString(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                onDelete(item)
                            },
                            enabled = !isRestoring
                        ) {
                            Text(stringResource(R.string.backup_page_delete))
                        }
                        Button(
                            onClick = {
                                onRestore(item)
                            },
                            enabled = !isRestoring
                        ) {
                            if (isRestoring) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (isRestoring) {
                                    stringResource(R.string.backup_page_restoring)
                                } else {
                                    stringResource(R.string.backup_page_restore_now)
                                }
                            )
                        }
                    }
                }
            },
        )
    }
}
