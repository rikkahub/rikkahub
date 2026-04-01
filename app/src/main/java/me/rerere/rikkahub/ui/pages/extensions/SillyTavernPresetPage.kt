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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import me.rerere.rikkahub.data.model.resolvedStPresets
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.data.model.selectStPreset
import me.rerere.rikkahub.data.model.selectedStPreset
import me.rerere.rikkahub.data.model.upsertStPreset
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.EditorGuideAction
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
                actions = {
                    EditorGuideAction(
                        title = stringResource(R.string.prompt_page_st_preset_help_title),
                        body = stringResource(R.string.prompt_page_st_preset_help_body_markdown),
                    )
                },
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val presets = settings.resolvedStPresets()
    val selectedPreset = settings.selectedStPreset()
    var presetPendingDelete by remember { mutableStateOf<SillyTavernPreset?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var showLibrarySheet by rememberSaveable { mutableStateOf(false) }
    var presetSearch by rememberSaveable { mutableStateOf("") }
    val filteredPresets = remember(presets, presetSearch) {
        val query = presetSearch.trim()
        if (query.isBlank()) {
            presets
        } else {
            presets.filter { preset ->
                preset.displayName.contains(query, ignoreCase = true) ||
                    preset.template.prompts.any { prompt ->
                        prompt.name.contains(query, ignoreCase = true) ||
                            prompt.identifier.contains(query, ignoreCase = true)
                    }
            }
        }
    }

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
                        ?: error(resources.getString(R.string.prompt_page_st_preset_import_read_failed))
                }
                parseAssistantImportFromJson(
                    jsonString = jsonString,
                    sourceName = fileName
                        ?.substringBeforeLast('.')
                        ?.ifBlank { resources.getString(R.string.prompt_page_st_preset_imported_name) }
                        ?: resources.getString(R.string.prompt_page_st_preset_imported_name),
                )
            }.onSuccess { payload ->
                if (payload.kind != AssistantImportKind.PRESET) {
                    toaster.show(resources.getString(R.string.prompt_page_st_preset_import_only_json))
                } else {
                    val baseSettings = settings.ensureStPresetLibrary()
                    onUpdate(
                        baseSettings
                            .upsertStPreset(payload.toSillyTavernPreset(), select = true)
                            .copy(stPresetEnabled = true)
                    )
                    toaster.show(resources.getString(R.string.prompt_page_st_preset_import_success))
                }
            }.onFailure { exception ->
                exception.printStackTrace()
                toaster.show(exception.message ?: resources.getString(R.string.assistant_importer_import_failed))
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "全局 Regex",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (settings.globalRegexEnabled) {
                                    "${settings.globalRegexes.size} 条全局规则会参与所有助手的运行时处理。"
                                } else {
                                    "全局规则已整体停用，但列表和顺序会保留。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.globalRegexEnabled,
                            onCheckedChange = { enabled ->
                                onUpdate(settings.copy(globalRegexEnabled = enabled))
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
                                    sourceName = resources.getString(
                                        R.string.prompt_page_st_preset_generated_name,
                                        presets.size + 1,
                                    ),
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
                        OutlinedButton(
                            onClick = { showLibrarySheet = true },
                            enabled = presets.isNotEmpty(),
                        ) {
                            Icon(HugeIcons.Share03, null)
                            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                            Text(
                                stringResource(
                                    R.string.prompt_page_st_preset_tab_manage_library,
                                    presets.size,
                                )
                            )
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
                PresetSelectionCard(
                    preset = selectedPreset,
                    globalRegexCount = settings.globalRegexes.size,
                    globalRegexEnabled = settings.globalRegexEnabled,
                    presetCount = presets.size,
                    onManageLibrary = { showLibrarySheet = true },
                )
            }
        }

        item {
            RegexEditorSection(
                regexes = settings.globalRegexes,
                onUpdate = { regexes ->
                    onUpdate(settings.copy(globalRegexes = regexes))
                },
                title = "全局 Regex",
                description = "总是先于预设和角色 Regex 执行。适合通用清洗、统一替换和跨助手共享规则。",
            )
        }

        selectedPreset?.let { preset ->
            item {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "当前预设 Regex",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (preset.regexEnabled) {
                                    "切换到该预设时，会自动带上它自己的 ${preset.regexes.size} 条 Regex。"
                                } else {
                                    "该预设的 Regex 已整体停用，切换预设时不会自动参与运行时处理。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = preset.regexEnabled,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    settings.upsertStPreset(
                                        preset.copy(regexEnabled = enabled),
                                        select = true,
                                    )
                                )
                            },
                        )
                    }
                }
            }
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
            text = {
                Text(
                    stringResource(
                        R.string.prompt_page_st_preset_delete_confirm,
                        preset.displayName,
                    )
                )
            },
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

    if (showLibrarySheet) {
        ModalBottomSheet(
            onDismissRequest = { showLibrarySheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_library_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_library_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = presetSearch,
                    onValueChange = { presetSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_library_search)) },
                    singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (filteredPresets.isEmpty()) {
                        item {
                            Card(colors = CustomColors.listItemCardColors) {
                                Text(
                                    text = stringResource(R.string.prompt_page_st_preset_library_empty_search),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(filteredPresets, key = { it.id }) { preset ->
                            PresetLibraryCard(
                                preset = preset,
                                selected = preset.id == settings.selectedStPresetId,
                                onToggleRegex = { enabled ->
                                    onUpdate(
                                        settings.upsertStPreset(
                                            preset.copy(regexEnabled = enabled),
                                        )
                                    )
                                },
                                onSelect = {
                                    onUpdate(settings.selectStPreset(preset.id))
                                    showLibrarySheet = false
                                },
                                onDelete = { presetPendingDelete = preset },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSelectionCard(
    preset: SillyTavernPreset?,
    globalRegexCount: Int,
    globalRegexEnabled: Boolean,
    presetCount: Int,
    onManageLibrary: () -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_page_st_preset_current_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (preset == null) {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_current_card_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_tab_current, preset.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                R.string.prompt_page_st_preset_current_card_summary,
                                preset.regexes.size,
                                preset.sampling.configuredValueCount(),
                                preset.template.resolvePromptOrder().size,
                            )
                        )
                        append(" · 全局 Regex ")
                        append(globalRegexCount)
                        if (!globalRegexEnabled) {
                            append("（已停用）")
                        }
                        if (!preset.regexEnabled) {
                            append(" · 当前预设 Regex 已停用")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_library_count, presetCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onManageLibrary) {
                    Text(stringResource(R.string.prompt_page_st_preset_open_library))
                }
            }
        }
    }
}

@Composable
private fun PresetLibraryCard(
    preset: SillyTavernPreset,
    selected: Boolean,
    onToggleRegex: (Boolean) -> Unit,
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
                        append(
                            stringResource(
                                R.string.prompt_page_st_preset_library_regex_count,
                                preset.regexes.size,
                            )
                        )
                        if (!preset.regexEnabled) {
                            append(" · Regex 已停用")
                        }
                        val samplingCount = preset.sampling.configuredValueCount()
                        if (samplingCount > 0) {
                            append(
                                stringResource(
                                    R.string.prompt_page_st_preset_library_sampling_count,
                                    samplingCount,
                                )
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Regex",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = preset.regexEnabled,
                    onCheckedChange = onToggleRegex,
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
