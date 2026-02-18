package me.rerere.rikkahub.ui.components.sandbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * 改名文件对话框
 *
 * @param sandboxId 沙箱ID
 * @param currentPath 当前路径
 * @param oldName 原文件名
 * @param onDismiss 关闭回调
 * @param onSuccess 成功回调（刷新文件列表）
 */
@Composable
fun RenameFileDialog(
    sandboxId: String,
    currentPath: String,
    oldName: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var newName by remember { mutableStateOf(oldName) }
    var isRenaming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun renameFile() {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) {
            errorMessage = "文件名不能为空"
            return
        }

        if (trimmedName == oldName) {
            onDismiss()
            return
        }

        // 简单的文件名验证
        if (trimmedName.contains("/") || trimmedName.contains("\\")) {
            errorMessage = "文件名不能包含路径分隔符"
            return
        }

        scope.launch {
            isRenaming = true
            try {
                val oldFullPath = if (currentPath.isEmpty()) {
                    oldName
                } else {
                    "$currentPath/$oldName"
                }

                val newFullPath = if (currentPath.isEmpty()) {
                    trimmedName
                } else {
                    "$currentPath/$trimmedName"
                }

                // 使用 move 操作来重命名
                val result = me.rerere.rikkahub.sandbox.SandboxEngine.execute(
                    context = context,
                    assistantId = sandboxId,
                    operation = "move",
                    params = mapOf(
                        "source" to oldFullPath,
                        "destination" to newFullPath
                    )
                )

                if (result["success"]?.jsonPrimitive?.boolean == true) {
                    onSuccess()
                    onDismiss()
                } else {
                    errorMessage = "重命名失败: ${result["error"]?.jsonPrimitive?.toString()}"
                }
            } catch (e: Exception) {
                errorMessage = "重命名失败: ${e.message}"
            } finally {
                isRenaming = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Lucide.File, contentDescription = null)
                Text("重命名文件")
            }
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        errorMessage = ""
                    },
                    label = { Text("新文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    isError = errorMessage.isNotEmpty(),
                    supportingText = if (errorMessage.isNotEmpty()) {
                        { Text(errorMessage) }
                    } else {
                        null
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "原名: $oldName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { renameFile() },
                enabled = newName.isNotBlank() && newName != oldName && !isRenaming
            ) {
                if (isRenaming) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("确定")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRenaming) {
                Text("取消")
            }
        },
        properties = DialogProperties(dismissOnBackPress = !isRenaming)
    )
}
