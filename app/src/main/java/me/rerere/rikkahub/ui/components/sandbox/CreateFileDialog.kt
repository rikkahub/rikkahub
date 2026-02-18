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
import androidx.compose.runtime.LaunchedEffect
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
 * 新增文件对话框
 *
 * @param sandboxId 沙箱ID
 * @param currentPath 当前路径
 * @param onDismiss 关闭回调
 * @param onSuccess 成功回调（刷新文件列表）
 */
@Composable
fun CreateFileDialog(
    sandboxId: String,
    currentPath: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun createFile() {
        val trimmedName = fileName.trim()
        if (trimmedName.isEmpty()) {
            errorMessage = "文件名不能为空"
            return
        }

        // 简单的文件名验证
        if (trimmedName.contains("/") || trimmedName.contains("\\")) {
            errorMessage = "文件名不能包含路径分隔符"
            return
        }

        scope.launch {
            isCreating = true
            try {
                val fullPath = if (currentPath.isEmpty()) {
                    trimmedName
                } else {
                    "$currentPath/$trimmedName"
                }

                val result = me.rerere.rikkahub.sandbox.SandboxEngine.execute(
                    context = context,
                    assistantId = sandboxId,
                    operation = "write",
                    params = mapOf(
                        "file_path" to fullPath,
                        "content" to ""  // 创建空文件
                    )
                )

                if (result["success"]?.jsonPrimitive?.boolean == true) {
                    onSuccess()
                    onDismiss()
                } else {
                    errorMessage = "创建失败: ${result["error"]?.jsonPrimitive?.toString()}"
                }
            } catch (e: Exception) {
                errorMessage = "创建失败: ${e.message}"
            } finally {
                isCreating = false
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
                Text("新增文件")
            }
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        errorMessage = ""
                    },
                    label = { Text("文件名") },
                    placeholder = { Text("例如: rikkahub.md") },
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
                    text = "提示: 文件将被创建到当前目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { createFile() },
                enabled = fileName.isNotBlank() && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("创建")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text("取消")
            }
        },
        properties = DialogProperties(dismissOnBackPress = !isCreating)
    )
}
