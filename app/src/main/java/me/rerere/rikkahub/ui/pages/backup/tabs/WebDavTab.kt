package me.rerere.rikkahub.ui.pages.backup.tabs

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Upload
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onLoading
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun WebDavTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val webDavConfig = settings.webDavConfig
    val backupItemsState by vm.webDavBackupItems.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackupFiles by remember { mutableStateOf(false) }
    var restoringItemId by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }

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

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_webdav_server_address)) }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.url,
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(url = it.trim())) },
                            placeholder = { Text("https://example.com/dav") },
                            singleLine = true
                        )
                    }
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_username)) }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.username,
                            onValueChange = {
                                updateWebDavConfig(
                                    webDavConfig.copy(
                                        username = it.trim()
                                    )
                                )
                            },
                            singleLine = true
                        )
                    }
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_password)) }
                    ) {
                        var passwordVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.password,
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(password = it)) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) {
                                    Lucide.EyeOff
                                } else {
                                    Lucide.Eye
                                }
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(imageVector = image, contentDescription = null)
                                }
                            },
                            singleLine = true
                        )
                    }
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_path)) }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = webDavConfig.path,
                            onValueChange = { updateWebDavConfig(webDavConfig.copy(path = it.trim())) },
                            singleLine = true
                        )
                    }
                }
            }

            OutlinedCard {
                FormItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    label = {
                        Text(stringResource(R.string.backup_page_backup_items))
                    }
                ) {
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
                                Text(
                                    when (item) {
                                        WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                        WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                    }
                                )
                            }
                        }
                    }
                }
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
                onClick = {
                    scope.launch {
                        isBackingUp = true
                        runCatching {
                            vm.backup()
                            vm.loadBackupFileItems()
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
                enabled = !isBackingUp
            ) {
                if (isBackingUp) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(Lucide.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
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
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
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
                        items(it) { item ->
                            WebDavBackupItemCard(
                                item = item,
                                isRestoring = restoringItemId == item.displayName,
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
                                onRestore = { restoreItem ->
                                    scope.launch {
                                        restoringItemId = restoreItem.displayName
                                        runCatching {
                                            vm.restore(item = restoreItem)
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
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
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
    }
}

@Composable
private fun WebDavBackupItemCard(
    item: WebDavBackupItem,
    isRestoring: Boolean = false,
    onDelete: (WebDavBackupItem) -> Unit = {},
    onRestore: (WebDavBackupItem) -> Unit = {},
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium
            )
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
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
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
}
