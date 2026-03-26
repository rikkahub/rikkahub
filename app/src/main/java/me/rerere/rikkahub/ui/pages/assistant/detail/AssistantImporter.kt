package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    allowedKinds: Set<AssistantImportKind> = setOf(
        AssistantImportKind.PRESET,
        AssistantImportKind.CHARACTER_CARD,
    ),
    onImport: (AssistantImportPayload, Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(
            allowedKinds = allowedKinds,
            onImport = onImport,
        )
    }
}

@Composable
private fun SillyTavernImporter(
    allowedKinds: Set<AssistantImportKind>,
    onImport: (AssistantImportPayload, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<AssistantImportPayload?>(null) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isLoading = true
        scope.launch {
            runCatching {
                parseAssistantImportFromUri(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
            }.onSuccess { payload ->
                if (payload.kind !in allowedKinds) {
                    toaster.show("这里只允许导入角色卡")
                } else if (payload.regexes.isNotEmpty()) {
                    pendingImport = payload
                } else {
                    onImport(payload, false)
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: "Import failed")
            }
            isLoading = false
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isLoading = true
        scope.launch {
            runCatching {
                parseAssistantImportFromUri(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
            }.onSuccess { payload ->
                if (payload.kind !in allowedKinds) {
                    toaster.show("这里只允许导入角色卡")
                } else if (payload.regexes.isNotEmpty()) {
                    pendingImport = payload
                } else {
                    onImport(payload, false)
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: "Import failed")
            }
            isLoading = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { pngPickerLauncher.launch(arrayOf("image/png")) },
            enabled = !isLoading,
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(if (isLoading) "Importing..." else "Import Tavern PNG")
        }

        OutlinedButton(
            onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "text/plain")) },
            enabled = !isLoading,
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(
                if (isLoading) {
                    "Importing..."
                } else if (AssistantImportKind.PRESET in allowedKinds) {
                    "Import Tavern JSON / Preset"
                } else {
                    "Import Tavern Character JSON"
                }
            )
        }
    }

    pendingImport?.let { payload ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = {
                Text("导入配套正则")
            },
            text = {
                Text(
                    when (payload.kind) {
                        AssistantImportKind.PRESET ->
                            "检测到 ${payload.regexes.size} 条配套正则。预设 regex 会导入到全局，是否一并导入？"
                        AssistantImportKind.CHARACTER_CARD ->
                            "检测到 ${payload.regexes.size} 条配套正则。角色卡 regex 会导入到当前助手，是否一并导入？"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImport(payload, true)
                        pendingImport = null
                    }
                ) {
                    Text("导入全部")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onImport(payload, false)
                            pendingImport = null
                        }
                    ) {
                        Text("跳过正则")
                    }
                    TextButton(onClick = { pendingImport = null }) {
                        Text("取消")
                    }
                }
            }
        )
    }
}
