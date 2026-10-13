package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ConnectedMultiChoiceButtons
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

// 列表项开头的图标，操作进行中时换成进度圈
@Composable
internal fun BackupActionIcon(
    icon: ImageVector,
    loading: Boolean = false,
) {
    if (loading) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        CardGroupIcon(icon)
    }
}

// 「备份内容」多选项，本地、WebDAV、S3 共用
internal fun <T> CardGroupScope.backupContentItem(
    options: List<T>,
    selected: List<T>,
    onChange: (List<T>) -> Unit,
    label: @Composable (T) -> String,
) = formItem(
    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
) {
    ConnectedMultiChoiceButtons(
        options = options,
        selected = selected,
        onToggle = { option, checked ->
            onChange(if (checked) selected + option else selected - option)
        },
    ) { option, isSelected ->
        ConnectedChoiceLabel(
            text = label(option),
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}

// WebDAV / S3 页顶部的状态分组：上次备份时间，以及进入备份文件列表的入口
@Composable
internal fun RemoteBackupStatus(
    lastBackupTime: Long,
    filesState: UiState<List<*>>,
    onShowFiles: () -> Unit,
) {
    val lastBackupText = if (lastBackupTime == 0L) {
        stringResource(R.string.backup_page_reminder_no_record)
    } else {
        stringResource(
            R.string.backup_page_reminder_last_time,
            Instant.ofEpochMilli(lastBackupTime).toLocalDateTime()
        )
    }
    val fileCount = when (filesState) {
        is UiState.Success -> filesState.data.size.toString()
        UiState.Loading -> "..."
        UiState.Idle, is UiState.Error -> "-"
    }

    CardGroup {
        item(
            headlineContent = { Text(lastBackupText) },
            leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
        )
        item(
            onClick = onShowFiles,
            headlineContent = { Text(stringResource(R.string.backup_page_restore)) },
            supportingContent = { Text("${stringResource(R.string.backup_page_files)}: $fileCount") },
            leadingContent = { CardGroupIcon(HugeIcons.CloudDownload) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
        )
    }
}

// WebDAV / S3 页共用的外壳：可滚动的表单，底部悬浮着测试连接和立即备份
@Composable
internal fun RemoteBackupLayout(
    isBackingUp: Boolean,
    onTestConnection: () -> Unit,
    onBackup: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                )
                .verticalScroll(rememberScrollState())
                // 底部留出悬浮工具栏的位置
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )

        // 键盘弹出时收起，不然会盖住正在输入的那一行
        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = expanded,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = onTestConnection) {
                        Icon(HugeIcons.Connect, stringResource(R.string.backup_page_test_connection))
                    }
                },
            ) {
                Button(
                    onClick = onBackup,
                    enabled = !isBackingUp,
                ) {
                    if (isBackingUp) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                    } else {
                        Icon(
                            imageVector = HugeIcons.CloudUpload,
                            contentDescription = stringResource(R.string.backup_page_backup_now),
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
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
            }
        }
    }
}

internal class BackupFileInfo(
    val name: String,
    val size: Long,
    val lastModified: Instant,
)

// 远程备份文件列表，可以恢复或删除其中一份
@Composable
internal fun <T> BackupFilesSheet(
    title: String,
    state: UiState<List<T>>,
    // 正在恢复的那份备份的文件名，恢复期间所有按钮都不可点
    restoringName: String?,
    info: (T) -> BackupFileInfo,
    onDelete: (T) -> Unit,
    onRestore: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            when (state) {
                is UiState.Success -> {
                    val files = state.data
                    if (files.isEmpty()) {
                        ExtensionEmptyState(
                            message = stringResource(R.string.setting_files_page_no_files),
                            icon = HugeIcons.FileZip,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(files) { index, file ->
                                val fileInfo = info(file)
                                BackupFileItem(
                                    info = fileInfo,
                                    index = index,
                                    count = files.size,
                                    isRestoring = restoringName == fileInfo.name,
                                    enabled = restoringName == null,
                                    onDelete = { onDelete(file) },
                                    onRestore = { onRestore(file) },
                                )
                            }
                        }
                    }
                }

                is UiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.backup_page_loading_failed, state.error.message ?: ""),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }

                UiState.Idle -> {}
            }
        }
    }
}

@Composable
private fun BackupFileItem(
    info: BackupFileInfo,
    index: Int,
    count: Int,
    isRestoring: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardGroupItemShape(index, count),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CardGroupIcon(HugeIcons.FileZip)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = info.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${info.lastModified.toLocalDateTime()} · ${info.size.fileSizeToString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.backup_page_delete))
                }
                FilledTonalButton(
                    onClick = onRestore,
                    enabled = enabled,
                ) {
                    if (isRestoring) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
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
}
