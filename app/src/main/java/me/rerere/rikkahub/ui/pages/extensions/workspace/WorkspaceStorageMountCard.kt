package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.FolderLinks
import me.rerere.hugeicons.stroke.FolderOpen
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.InvalidStorageMountPathException
import me.rerere.rikkahub.data.model.WorkspaceStorageMount
import me.rerere.rikkahub.data.model.normalizeStorageMountPath
import me.rerere.rikkahub.data.model.sharedStorageRoot
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import java.io.File

@Composable
internal fun WorkspaceStorageMountCard(
    workspace: WorkspaceEntity?,
    onMountsChange: (List<WorkspaceStorageMount>) -> Unit,
) {
    // 依赖只在 github 渠道声明的 MANAGE_EXTERNAL_STORAGE, Android 11 以下没有这个权限
    if (BuildConfig.IS_PLAY_STORE || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val context = LocalContext.current
    val mounts = workspace?.storageMountList().orEmpty()
    // 从系统设置授权回来时刷新
    var hasPermission by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        hasPermission = Environment.isExternalStorageManager()
        onPauseOrDispose { }
    }
    // null: 不显示; -1: 新增; 其余: 编辑对应下标
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    CardGroup(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.workspace_detail_storage_title))
                Text(
                    text = stringResource(R.string.workspace_detail_storage_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (!hasPermission) {
            item(
                onClick = { context.openAllFilesAccessSettings() },
                leadingContent = { CardGroupIcon(HugeIcons.Alert02) },
                headlineContent = { Text(stringResource(R.string.workspace_detail_storage_permission_missing)) },
                supportingContent = {
                    Text(stringResource(R.string.workspace_detail_storage_permission_missing_desc))
                },
            )
        }
        mounts.forEachIndexed { index, mount ->
            item(
                onClick = { editingIndex = index },
                leadingContent = { CardGroupIcon(HugeIcons.FolderLinks) },
                headlineContent = {
                    Text(
                        text = mount.path,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(
                            if (mount.writable) {
                                R.string.workspace_detail_storage_writable
                            } else {
                                R.string.workspace_detail_storage_needs_approval
                            }
                        )
                    )
                },
            )
        }
        item(
            onClick = if (workspace != null) {
                { editingIndex = -1 }
            } else {
                null
            },
            leadingContent = { CardGroupIcon(HugeIcons.FolderAdd) },
            headlineContent = { Text(stringResource(R.string.workspace_detail_storage_add)) },
        )
    }

    editingIndex?.let { index ->
        val editing = mounts.getOrNull(index)
        StorageMountDialog(
            initial = editing,
            onDismiss = { editingIndex = null },
            onConfirm = { mount ->
                onMountsChange(
                    if (editing == null) mounts + mount else mounts.toMutableList().apply { this[index] = mount }
                )
                editingIndex = null
            },
            onDelete = editing?.let {
                {
                    onMountsChange(mounts.filterIndexed { i, _ -> i != index })
                    editingIndex = null
                }
            },
        )
    }
}

@Composable
private fun StorageMountDialog(
    initial: WorkspaceStorageMount?,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceStorageMount) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var path by rememberSaveable { mutableStateOf(initial?.path ?: "${sharedStorageRoot()}/") }
    var writable by rememberSaveable { mutableStateOf(initial?.writable ?: false) }
    // 选择器返回的目录无法换算成路径 / 保存时目录不可访问; 改动路径后清除
    var pickFailed by rememberSaveable { mutableStateOf(false) }
    var unavailable by rememberSaveable { mutableStateOf(false) }
    val normalizedPath = runCatching { normalizeStorageMountPath(path) }
    val pathError = when ((normalizedPath.exceptionOrNull() as? InvalidStorageMountPathException)?.reason) {
        null -> null
        InvalidStorageMountPathException.Reason.NOT_ABSOLUTE ->
            stringResource(R.string.workspace_detail_storage_path_not_absolute)

        InvalidStorageMountPathException.Reason.OUTSIDE_STORAGE ->
            stringResource(R.string.workspace_detail_storage_path_outside, "${sharedStorageRoot()}/Documents")
    }
    val errorText = when {
        pickFailed -> stringResource(R.string.workspace_detail_storage_pick_failed)
        pathError != null -> pathError
        unavailable -> stringResource(R.string.workspace_detail_storage_path_unavailable)
        else -> null
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = uri.toStoragePath()
        pickFailed = picked == null
        unavailable = false
        if (picked != null) path = picked
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.workspace_detail_storage_add else R.string.workspace_detail_storage_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = {
                        path = it
                        pickFailed = false
                        unavailable = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_storage_path)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = errorText != null,
                    supportingText = errorText?.let { message -> { Text(message) } },
                    maxLines = 3,
                )
                FilledTonalButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.workspace_detail_storage_pick_folder))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.workspace_detail_storage_writable),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.workspace_detail_storage_writable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = writable, onCheckedChange = { writable = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = normalizedPath.getOrNull() ?: return@TextButton
                    // 新路径要能访问, 否则 PRoot 会跳过挂载; 原有路径不再校验, 失效时仍能改开关
                    if (target != initial?.path && !File(target).isDirectory) {
                        unavailable = true
                    } else {
                        onConfirm(WorkspaceStorageMount(path = target, writable = writable))
                    }
                },
                enabled = pathError == null && !unavailable,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** 系统文件选择器返回的目录 URI 转成真实路径, 只认外部存储提供方, 其他来源 (如网盘) 返回 null */
private fun Uri.toStoragePath(): String? {
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() ?: return null
    val volume = documentId.substringBefore(':')
    val relative = documentId.substringAfter(':', "").trim('/')
    val base = when {
        // primary 指当前用户自己的共享存储
        volume.equals("primary", ignoreCase = true) -> sharedStorageRoot()
        // 部分系统的「文档」入口
        volume.equals("home", ignoreCase = true) -> "${sharedStorageRoot()}/${Environment.DIRECTORY_DOCUMENTS}"
        volume.isNotBlank() -> "/storage/$volume"
        else -> return null
    }
    return if (relative.isEmpty()) base else "$base/$relative"
}

@RequiresApi(Build.VERSION_CODES.R)
private fun Context.openAllFilesAccessSettings() {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", packageName, null),
    )
    try {
        startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        // 部分定制系统没有单个应用的授权页, 退回到总列表
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
