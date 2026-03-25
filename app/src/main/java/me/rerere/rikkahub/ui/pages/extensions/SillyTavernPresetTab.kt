package me.rerere.rikkahub.ui.pages.extensions

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImportKind
import me.rerere.rikkahub.ui.pages.assistant.detail.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.ui.pages.assistant.detail.mergeImportedRegexes
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SillyTavernPresetTab(
    settings: Settings,
    onUpdate: (Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isImporting by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            runCatching {
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (columnIndex >= 0) cursor.getString(columnIndex) else null
                    }
                }
                val jsonString = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Failed to read import file")
                }
                parseAssistantImportFromJson(
                    jsonString = jsonString,
                    sourceName = fileName?.substringBeforeLast('.')?.ifBlank { "Imported Preset" } ?: "Imported Preset",
                )
            }.onSuccess { payload ->
                if (payload.kind != AssistantImportKind.PRESET) {
                    toaster.show("这里只支持导入 ST 预设 JSON")
                } else {
                    val template = payload.assistant.stPromptTemplate ?: defaultSillyTavernPromptTemplate()
                    val regexes = mergeImportedRegexes(
                        current = settings.regexes,
                        imported = payload.regexes,
                        includeImported = true,
                    )
                    onUpdate(
                        settings.copy(
                            stPresetEnabled = true,
                            stPresetTemplate = template,
                            regexes = regexes,
                        )
                    )
                    if (payload.regexes.isNotEmpty()) {
                        toaster.show("已导入 ST 预设，并追加 ${payload.regexes.size} 条全局 regex")
                    } else {
                        toaster.show("已导入 ST 预设")
                    }
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: "Import failed")
            }
            isImporting = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SillyTavern 预设",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "全局 ST 提示词预设，对所有助手生效。角色卡和预设聊天消息仍绑定助手，regex 仍在这里统一管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RowLabelSwitch(
                    title = "启用全局 ST 预设",
                    checked = settings.stPresetEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            settings.copy(
                                stPresetEnabled = enabled,
                                stPresetTemplate = if (enabled) {
                                    settings.stPresetTemplate ?: defaultSillyTavernPromptTemplate()
                                } else {
                                    settings.stPresetTemplate
                                },
                            )
                        )
                    }
                )
                settings.stPresetTemplate?.let { template ->
                    Text(
                        text = "当前预设: ${template.sourceName.ifBlank { "SillyTavern Default" }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } ?: Text(
                    text = "当前尚未配置 ST 预设，可导入预设 JSON 或直接创建默认预设。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        enabled = !isImporting,
                    ) {
                        Text(if (isImporting) "导入中..." else "导入 ST 预设 JSON")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdate(
                                settings.copy(
                                    stPresetEnabled = true,
                                    stPresetTemplate = defaultSillyTavernPromptTemplate(),
                                )
                            )
                        }
                    ) {
                        Text(if (settings.stPresetTemplate == null) "创建默认预设" else "恢复默认预设")
                    }
                }
            }
        }

        settings.stPresetTemplate?.let { template ->
            SillyTavernPresetEditorCard(
                template = template,
                onUpdate = { updatedTemplate ->
                    onUpdate(
                        settings.copy(
                            stPresetTemplate = updatedTemplate,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RegexEditorSection(
            regexes = settings.regexes,
            onUpdate = { regexes ->
                onUpdate(
                    settings.copy(regexes = regexes)
                )
            },
            title = "全局 Regex",
            description = "默认对所有助手生效。适合放通用 ST 预设 regex、美化 regex 和通用格式整理规则。",
        )
    }
}

@Composable
private fun RowLabelSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
