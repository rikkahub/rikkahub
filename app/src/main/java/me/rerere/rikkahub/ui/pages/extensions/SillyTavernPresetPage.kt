package me.rerere.rikkahub.ui.pages.extensions

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.SillyTavernPresetExportSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.configuredValueCount
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.ensureStPresetLibrary
import me.rerere.rikkahub.data.model.removeStPreset
import me.rerere.rikkahub.data.model.selectStPreset
import me.rerere.rikkahub.data.model.selectedStPreset
import me.rerere.rikkahub.data.model.upsertStPreset
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImportKind
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.pages.assistant.detail.toSillyTavernPreset
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SillyTavernPresetPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_st_preset_tab_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        SillyTavernPresetPageContent(
            settings = settings,
            onUpdate = vm::updateSettings,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        )
    }
}

@Composable
private fun SillyTavernPresetPageContent(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onUpdate: (me.rerere.rikkahub.data.datastore.Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val presets = settings.stPresets
    val selectedPreset = settings.selectedStPreset()
    var presetPendingDelete by remember { mutableStateOf<SillyTavernPreset?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            runCatching {
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
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
                    sourceName = fileName?.substringBeforeLast('.')?.ifBlank { "Imported Preset" } ?: "Imported Preset",
                )
            }.onSuccess { payload ->
                if (payload.kind != AssistantImportKind.PRESET) {
                    toaster.show(context.getString(R.string.prompt_page_st_preset_import_only_json))
                } else {
                    val baseSettings = if (settings.stPresetTemplate != null && settings.stPresets.isEmpty()) {
                        settings.ensureStPresetLibrary()
                    } else {
                        settings
                    }
                    onUpdate(
                        baseSettings
                            .upsertStPreset(payload.toSillyTavernPreset(), select = true)
                            .copy(stPresetEnabled = true)
                    )
                    toaster.show(context.getString(R.string.prompt_page_st_preset_import_success))
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
            }
            isImporting = false
        }
    }

    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_tab_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_st_preset_tab_enable),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = settings.stPresetEnabled,
                            onCheckedChange = { enabled ->
                                onUpdate(settings.copy(stPresetEnabled = enabled))
                            },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                            enabled = !isImporting,
                        ) {
                            Icon(HugeIcons.FileImport, null)
                            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
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
                                val template = defaultSillyTavernPromptTemplate().copy(
                                    sourceName = "Preset ${presets.size + 1}",
                                )
                                onUpdate(
                                    settings
                                        .upsertStPreset(SillyTavernPreset(template = template), select = true)
                                        .copy(stPresetEnabled = true)
                                )
                            },
                        ) {
                            Icon(HugeIcons.Add01, null)
                            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                            Text(stringResource(R.string.prompt_page_st_preset_tab_create_default))
                        }
                    }
                }
            }
        }

        if (presets.isEmpty()) {
            item {
                Card(colors = CustomColors.listItemCardColors) {
                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_tab_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_editor_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(presets, key = { it.id }) { preset ->
                PresetLibraryCard(
                    preset = preset,
                    selected = preset.id == settings.selectedStPresetId,
                    onSelect = {
                        onUpdate(settings.selectStPreset(preset.id))
                    },
                    onDelete = { presetPendingDelete = preset },
                )
            }
        }

        selectedPreset?.let { preset ->
            item {
                SillyTavernPresetEditorCard(
                    template = preset.template,
                    onUpdate = { updatedTemplate ->
                        onUpdate(
                            settings.upsertStPreset(
                                preset.copy(template = updatedTemplate),
                                select = true,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                PresetSamplingEditorCard(
                    sampling = preset.sampling,
                    onUpdate = { updatedSampling ->
                        onUpdate(
                            settings.upsertStPreset(
                                preset.copy(sampling = updatedSampling),
                                select = true,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                RegexEditorSection(
                    regexes = preset.regexes,
                    onUpdate = { regexes ->
                        onUpdate(
                            settings.upsertStPreset(
                                preset.copy(regexes = regexes),
                                select = true,
                            )
                        )
                    },
                    title = stringResource(R.string.prompt_page_st_preset_tab_regex_title),
                    description = stringResource(R.string.prompt_page_st_preset_tab_regex_desc),
                )
            }
        }
    }

    presetPendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetPendingDelete = null },
            title = { Text(stringResource(R.string.prompt_page_delete)) },
            text = { Text("删除预设“${preset.displayName}”后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdate(settings.removeStPreset(preset.id))
                        presetPendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.prompt_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetPendingDelete = null }) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun PresetLibraryCard(
    preset: SillyTavernPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(preset, SillyTavernPresetExportSerializer)

    Card(
        onClick = onSelect,
        colors = CustomColors.cardColorsForContainer(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                CustomColors.listItemSurfaceColors.containerColor
            },
            preferredContentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                CustomColors.listItemSurfaceColors.contentColor
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append("Regex ${preset.regexes.size} 条")
                        val samplingCount = preset.sampling.configuredValueCount()
                        if (samplingCount > 0) {
                            append(" · 采样 $samplingCount 项")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showExportDialog = true }) {
                Icon(HugeIcons.Share03, null)
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, null)
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false },
        )
    }
}
