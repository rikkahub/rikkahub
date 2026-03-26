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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
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
    val importedPresetName = stringResource(R.string.prompt_page_st_preset_imported_name)
    val importOnlyJson = stringResource(R.string.prompt_page_st_preset_import_only_json)
    val importFailed = stringResource(R.string.assistant_importer_import_failed)
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
                        ?: error(context.getString(R.string.prompt_page_st_preset_import_read_failed))
                }
                parseAssistantImportFromJson(
                    jsonString = jsonString,
                    sourceName = fileName?.substringBeforeLast('.')?.ifBlank { importedPresetName } ?: importedPresetName,
                )
            }.onSuccess { payload ->
                if (payload.kind != AssistantImportKind.PRESET) {
                    toaster.show(importOnlyJson)
                } else {
                    val template = payload.presetTemplate ?: defaultSillyTavernPromptTemplate()
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
                        toaster.show(
                            context.getString(
                                R.string.prompt_page_st_preset_import_success_with_regex,
                                payload.regexes.size,
                            )
                        )
                    } else {
                        toaster.show(context.getString(R.string.prompt_page_st_preset_import_success))
                    }
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: importFailed)
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
                    text = stringResource(R.string.prompt_page_st_preset_tab_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_tab_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RowLabelSwitch(
                    title = stringResource(R.string.prompt_page_st_preset_tab_enable),
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
                        text = stringResource(
                            R.string.prompt_page_st_preset_tab_current,
                            template.sourceName.ifBlank {
                                context.getString(R.string.prompt_page_st_preset_tab_default_name)
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } ?: Text(
                    text = stringResource(R.string.prompt_page_st_preset_tab_empty),
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
                        Text(
                            if (isImporting) {
                                stringResource(R.string.prompt_page_st_preset_tab_importing)
                            } else {
                                stringResource(R.string.prompt_page_st_preset_tab_import)
                            }
                        )
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
                        Text(
                            if (settings.stPresetTemplate == null) {
                                stringResource(R.string.prompt_page_st_preset_tab_create_default)
                            } else {
                                stringResource(R.string.prompt_page_st_preset_tab_restore_default)
                            }
                        )
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
            title = stringResource(R.string.prompt_page_st_preset_tab_regex_title),
            description = stringResource(R.string.prompt_page_st_preset_tab_regex_desc),
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
