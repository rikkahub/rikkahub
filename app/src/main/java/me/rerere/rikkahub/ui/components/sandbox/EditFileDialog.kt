package me.rerere.rikkahub.ui.components.sandbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/**
 * 编辑文件内容对话框
 *
 * @param sandboxId 沙箱ID
 * @param filePath 文件路径
 * @param fileName 文件名（仅用于显示）
 * @param onDismiss 关闭回调
 * @param onSuccess 成功回调（刷新文件列表）
 */
@Composable
fun EditFileDialog(
    sandboxId: String,
    filePath: String,
    fileName: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // 加载文件内容
    LaunchedEffect(filePath) {
        scope.launch {
            isLoading = true
            try {
                val fileContent = withContext(Dispatchers.IO) {
                    val result = me.rerere.rikkahub.sandbox.SandboxEngine.execute(
                        context = context,
                        assistantId = sandboxId,
                        operation = "read",
                        params = mapOf(
                            "file_path" to filePath,
                            "encoding" to "utf-8"
                        )
                    )

                    if (result["success"]?.jsonPrimitive?.boolean == true) {
                        // 数据在 data 字段中，可能是字符串或对象
                        val dataContent = when (val dataValue: JsonElement = result["data"]!!) {
                            is kotlinx.serialization.json.JsonPrimitive -> dataValue.content
                            is kotlinx.serialization.json.JsonObject -> dataValue["content"]?.jsonPrimitive?.content ?: ""
                            else -> ""
                        }
                        dataContent
                    } else {
                        errorMessage = "读取文件失败: ${result["error"]?.jsonPrimitive?.toString()}"
                        ""
                    }
                }
                content = fileContent
            } catch (e: Exception) {
                errorMessage = "读取文件失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun saveFile() {
        scope.launch {
            isSaving = true
            try {
                val result = me.rerere.rikkahub.sandbox.SandboxEngine.execute(
                    context = context,
                    assistantId = sandboxId,
                    operation = "write",
                    params = mapOf(
                        "file_path" to filePath,
                        "content" to content
                    )
                )

                if (result["success"]?.jsonPrimitive?.boolean == true) {
                    onSuccess()
                    onDismiss()
                } else {
                    errorMessage = "保存失败: ${result["error"]?.jsonPrimitive?.toString()}"
                }
            } catch (e: Exception) {
                errorMessage = "保存失败: ${e.message}"
            } finally {
                isSaving = false
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
                Column {
                    Text("编辑文件")
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("正在加载文件...")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp)
                            .then(
                                Modifier.verticalScroll(rememberScrollState())
                            ),
                        label = if (errorMessage.isNotEmpty()) {
                            { Text("文件内容") }
                        } else {
                            null
                        },
                        isError = errorMessage.isNotEmpty(),
                        supportingText = if (errorMessage.isNotEmpty()) {
                            { Text(errorMessage) }
                        } else {
                            null
                        }
                    )

                    // 显示文件信息
                    if (errorMessage.isEmpty()) {
                        val lineCount = content.lines().size
                        val charCount = content.length
                        Text(
                            text = "字符数: $charCount | 行数: $lineCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { saveFile() },
                enabled = !isLoading && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading && !isSaving) {
                Text("取消")
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isLoading && !isSaving
        )
    )
}
