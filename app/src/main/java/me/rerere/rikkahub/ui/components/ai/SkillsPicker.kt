package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillInvalidEntry
import me.rerere.rikkahub.data.skills.SkillInvalidReason
import me.rerere.rikkahub.data.skills.SkillsCatalogState
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.sanitizeSkillDirectoryName
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun SkillsPickerButton(
    assistant: Assistant,
    modelSupportsTools: Boolean,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    val skillsRepository = koinInject<SkillsRepository>()
    val skillsState by skillsRepository.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val enabledCount = if (assistant.skillsEnabled) {
        assistant.selectedSkills.count { it in skillsState.entryNames }
    } else {
        0
    }

    LaunchedEffect(showPicker) {
        if (showPicker && !skillsState.isLoading && (skillsState.refreshedAt == 0L || skillsState.error != null)) {
            skillsRepository.requestRefresh()
        }
    }

    ToggleSurface(
        modifier = modifier,
        checked = assistant.skillsEnabled,
        onClick = {
            showPicker = true
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (skillsState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    BadgedBox(
                        badge = {
                            if (enabledCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text(text = enabledCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Package01,
                            contentDescription = stringResource(R.string.assistant_page_tab_skills),
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_tab_skills),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                SkillsPicker(
                    assistant = assistant,
                    skillsState = skillsState,
                    modelSupportsTools = modelSupportsTools,
                    onRefresh = { skillsRepository.requestRefresh() },
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
fun SkillsPicker(
    assistant: Assistant,
    skillsState: SkillsCatalogState,
    modelSupportsTools: Boolean,
    onRefresh: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val skillsRepository = koinInject<SkillsRepository>()
    val termuxToolEnabled = assistant.localTools.contains(LocalToolOption.TermuxExec)
    val missingSelections = remember(assistant.selectedSkills, skillsState.entryNames) {
        assistant.selectedSkills
            .filterNot { it in skillsState.entryNames }
            .sorted()
    }
    val resolvedRootPath = if (skillsState.rootPath.isBlank()) {
        stringResource(R.string.assistant_page_skills_root_unresolved)
    } else {
        skillsState.rootPath
    }
    val fallbackRootPath = if (skillsState.rootPath.isBlank()) {
        stringResource(R.string.assistant_page_skills_root_fallback)
    } else {
        skillsState.rootPath
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createDirectory by remember { mutableStateOf("") }
    var createDescription by remember { mutableStateOf("") }
    var createBody by remember { mutableStateOf("") }
    var createDirectoryEdited by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var editDocument by remember { mutableStateOf<SkillEditorDocument?>(null) }
    var isLoadingEditor by remember { mutableStateOf(false) }
    var isSavingEditor by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<SkillCatalogEntry?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var showInvalidEntries by remember(skillsState.invalidEntries) {
        mutableStateOf(skillsState.invalidEntries.isNotEmpty())
    }
    val actionInProgress = isCreating || isImporting || isLoadingEditor || isSavingEditor || isDeleting

    fun resetCreateDialog() {
        createName = ""
        createDirectory = ""
        createDescription = ""
        createBody = ""
        createDirectoryEdited = false
    }

    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isImporting = true
                try {
                    val imported = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { inputStream ->
                            skillsRepository.importSkillZip(
                                inputStream = inputStream,
                                archiveName = queryDisplayName(context, selectedUri),
                            )
                        } ?: error(context.getString(R.string.assistant_page_skills_import_failed))
                    }
                    val message = if (imported.directories.size == 1) {
                        context.getString(
                            R.string.assistant_page_skills_import_success_single,
                            imported.directories.single(),
                        )
                    } else {
                        context.getString(
                            R.string.assistant_page_skills_import_success_multiple,
                            imported.directories.size,
                        )
                    }
                    toaster.show(message, type = ToastType.Success)
                } catch (error: Throwable) {
                    toaster.show(
                        error.message ?: context.getString(R.string.assistant_page_skills_import_failed),
                        type = ToastType.Error,
                    )
                } finally {
                    isImporting = false
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("controls") {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_page_skills_enable_catalog_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_enable_catalog_desc,
                                    resolvedRootPath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = assistant.skillsEnabled,
                            onCheckedChange = {
                                onUpdateAssistant(assistant.copy(skillsEnabled = it))
                            },
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.assistant_page_skills_action_desc,
                            fallbackRootPath,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !actionInProgress,
                            onClick = {
                                resetCreateDialog()
                                showCreateDialog = true
                            },
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Box(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isCreating) {
                                    stringResource(R.string.assistant_page_skills_create_in_progress)
                                } else {
                                    stringResource(R.string.assistant_page_skills_create)
                                }
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !actionInProgress,
                            onClick = {
                                zipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ) {
                            Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Box(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isImporting) {
                                    stringResource(R.string.assistant_page_skills_import_in_progress)
                                } else {
                                    stringResource(R.string.assistant_page_skills_import_zip)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_skills_selected_count,
                                assistant.selectedSkills.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = !actionInProgress,
                            onClick = onRefresh,
                        ) {
                            Text(stringResource(R.string.webview_page_refresh))
                        }
                    }
                }
            }
        }

        if (!termuxToolEnabled) {
            item("termux-warning") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_termux_required_title),
                    text = stringResource(R.string.assistant_page_skills_termux_required_desc),
                    isError = true,
                )
            }
        }

        if (!modelSupportsTools) {
            item("model-warning") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_model_unsupported_title),
                    text = stringResource(R.string.assistant_page_skills_model_unsupported_desc),
                    isError = false,
                )
            }
        }

        if (skillsState.error != null) {
            item("error") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_refresh_failed_title),
                    text = skillsState.error.orEmpty(),
                    isError = true,
                )
            }
        }

        if (skillsState.isLoading) {
            item("loading") {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(
                                R.string.assistant_page_skills_refreshing,
                                fallbackRootPath,
                            )
                        )
                    }
                }
            }
        }

        if (!skillsState.isLoading && skillsState.entries.isEmpty() && skillsState.invalidEntries.isEmpty()) {
            item("empty") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_empty_title),
                    text = stringResource(
                        R.string.assistant_page_skills_empty_desc,
                        fallbackRootPath,
                    ),
                    isError = false,
                )
            }
        }

        if (skillsState.entries.isNotEmpty()) {
            item("available-header") {
                Text(
                    text = stringResource(R.string.assistant_page_skills_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = skillsState.entries,
                key = { it.directoryName },
            ) { entry ->
                SkillEntryCard(
                    entry = entry,
                    checked = entry.directoryName in assistant.selectedSkills,
                    enabled = !actionInProgress,
                    onEdit = {
                        scope.launch {
                            isLoadingEditor = true
                            try {
                                editDocument = skillsRepository.loadSkillDocument(entry)
                            } catch (error: Throwable) {
                                toaster.show(
                                    error.message ?: context.getString(R.string.assistant_page_skills_edit_load_failed),
                                    type = ToastType.Error,
                                )
                            } finally {
                                isLoadingEditor = false
                            }
                        }
                    },
                    onDelete = if (entry.isBundled) {
                        null
                    } else {
                        {
                            deleteEntry = entry
                        }
                    },
                    onCheckedChange = { checked ->
                        val nextSelection = assistant.selectedSkills.toMutableSet().apply {
                            if (checked) add(entry.directoryName) else remove(entry.directoryName)
                        }
                        onUpdateAssistant(assistant.copy(selectedSkills = nextSelection))
                    },
                )
            }
        }

        if (missingSelections.isNotEmpty()) {
            item("missing-header") {
                Text(
                    text = stringResource(R.string.assistant_page_skills_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = missingSelections,
                key = { it },
            ) { directoryName ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Alert01, contentDescription = null)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = directoryName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_missing_desc,
                                    fallbackRootPath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = true,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    onUpdateAssistant(
                                        assistant.copy(
                                            selectedSkills = assistant.selectedSkills - directoryName
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        if (skillsState.invalidEntries.isNotEmpty() && showInvalidEntries) {
            item("invalid-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_skills_invalid_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showInvalidEntries = false },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                        )
                    }
                }
            }
            items(
                items = skillsState.invalidEntries,
                key = { "${it.directoryName}:${it.reason}" },
            ) { entry ->
                InvalidSkillEntryCard(entry = entry)
            }
        }
    }

    if (showCreateDialog) {
        SkillEditorDialog(
            name = createName,
            directory = createDirectory,
            description = createDescription,
            body = createBody,
            title = stringResource(R.string.assistant_page_skills_create_title),
            confirmText = stringResource(R.string.assistant_page_skills_create_confirm),
            progressText = stringResource(R.string.assistant_page_skills_create_in_progress),
            isSaving = isCreating,
            onDismiss = {
                if (!isCreating) {
                    showCreateDialog = false
                }
            },
            onNameChange = { value ->
                createName = value
                if (!createDirectoryEdited) {
                    createDirectory = sanitizeSkillDirectoryName(value)
                }
            },
            onDirectoryChange = { value ->
                createDirectoryEdited = true
                createDirectory = sanitizeSkillDirectoryName(value)
            },
            onDescriptionChange = { createDescription = it },
            onBodyChange = { createBody = it },
            onConfirm = {
                scope.launch {
                    isCreating = true
                    try {
                        val created = skillsRepository.createSkill(
                            directoryName = createDirectory,
                            name = createName,
                            description = createDescription,
                            body = createBody,
                        )
                        toaster.show(
                            context.getString(
                                R.string.assistant_page_skills_create_success,
                                created.directoryName,
                            ),
                            type = ToastType.Success,
                        )
                        showCreateDialog = false
                        resetCreateDialog()
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: context.getString(R.string.assistant_page_skills_create_failed),
                            type = ToastType.Error,
                        )
                    } finally {
                        isCreating = false
                    }
                }
            },
        )
    }

    deleteEntry?.let { currentEntry ->
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    deleteEntry = null
                }
            },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.assistant_page_skills_delete_body,
                        currentEntry.directoryName,
                        skillsState.rootPath.ifBlank {
                            stringResource(R.string.assistant_page_skills_root_fallback)
                        },
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        scope.launch {
                            val latestEntry = deleteEntry ?: return@launch
                            isDeleting = true
                            try {
                                skillsRepository.deleteSkill(latestEntry.directoryName)
                                if (latestEntry.directoryName in assistant.selectedSkills) {
                                    onUpdateAssistant(
                                        assistant.copy(
                                            selectedSkills = assistant.selectedSkills - latestEntry.directoryName
                                        )
                                    )
                                }
                                toaster.show(
                                    context.getString(
                                        R.string.assistant_page_skills_delete_success,
                                        latestEntry.directoryName,
                                    ),
                                    type = ToastType.Success,
                                )
                                deleteEntry = null
                            } catch (error: Throwable) {
                                toaster.show(
                                    error.message ?: context.getString(R.string.assistant_page_skills_delete_failed),
                                    type = ToastType.Error,
                                )
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (isDeleting) {
                            stringResource(R.string.assistant_page_skills_delete_in_progress)
                        } else {
                            stringResource(R.string.delete)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { deleteEntry = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editDocument?.let { currentDocument ->
        SkillEditorDialog(
            name = currentDocument.name,
            directory = currentDocument.directoryName,
            description = currentDocument.description,
            body = currentDocument.body,
            title = stringResource(R.string.assistant_page_skills_edit_title),
            confirmText = stringResource(R.string.assistant_page_skills_edit_confirm),
            progressText = stringResource(R.string.assistant_page_skills_edit_in_progress),
            isSaving = isSavingEditor,
            onDismiss = {
                if (!isSavingEditor) {
                    editDocument = null
                }
            },
            onNameChange = { value ->
                editDocument = currentDocument.copy(name = value)
            },
            onDirectoryChange = { value ->
                editDocument = currentDocument.copy(directoryName = sanitizeSkillDirectoryName(value))
            },
            onDescriptionChange = { value ->
                editDocument = currentDocument.copy(description = value)
            },
            onBodyChange = { value ->
                editDocument = currentDocument.copy(body = value)
            },
            onConfirm = {
                scope.launch {
                    val latestDocument = editDocument ?: return@launch
                    isSavingEditor = true
                    try {
                        val saved = skillsRepository.updateSkill(
                            originalDirectoryName = latestDocument.originalDirectoryName,
                            directoryName = latestDocument.directoryName,
                            name = latestDocument.name,
                            description = latestDocument.description,
                            body = latestDocument.body,
                        )
                        if (latestDocument.originalDirectoryName in assistant.selectedSkills &&
                            latestDocument.originalDirectoryName != saved.directoryName
                        ) {
                            val nextSelection = assistant.selectedSkills.toMutableSet().apply {
                                remove(latestDocument.originalDirectoryName)
                                add(saved.directoryName)
                            }
                            onUpdateAssistant(assistant.copy(selectedSkills = nextSelection))
                        }
                        toaster.show(
                            context.getString(
                                R.string.assistant_page_skills_edit_success,
                                saved.directoryName,
                            ),
                            type = ToastType.Success,
                        )
                        editDocument = null
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: context.getString(R.string.assistant_page_skills_edit_failed),
                            type = ToastType.Error,
                        )
                    } finally {
                        isSavingEditor = false
                    }
                }
            },
        )
    }
}

@Composable
private fun SkillEditorDialog(
    name: String,
    directory: String,
    description: String,
    body: String,
    title: String,
    confirmText: String,
    progressText: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onDirectoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val canConfirm = name.isNotBlank() && description.isNotBlank() && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_name)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = directory,
                    onValueChange = onDirectoryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_directory)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = onDescriptionChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_description)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = body,
                    onValueChange = onBodyChange,
                    minLines = 6,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_body)) },
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_skills_create_body_placeholder))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = onConfirm,
            ) {
                Text(
                    text = if (isSaving) {
                        progressText
                    } else {
                        confirmText
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SkillEntryCard(
    entry: SkillCatalogEntry,
    checked: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        onClick = onEdit,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Package01, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.name != entry.directoryName) {
                    Text(
                        text = entry.directoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = enabled,
                    onClick = onEdit,
                ) {
                    Icon(
                        imageVector = HugeIcons.PencilEdit01,
                        contentDescription = stringResource(R.string.assistant_page_skills_edit_title),
                    )
                }
                onDelete?.let { deleteSkill ->
                    IconButton(
                        enabled = enabled,
                        onClick = deleteSkill,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun InvalidSkillEntryCard(entry: SkillInvalidEntry) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Alert01, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.directoryName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = localizedSkillInvalidReason(entry.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun localizedSkillInvalidReason(reason: SkillInvalidReason): String {
    return when (reason) {
        SkillInvalidReason.MissingSkillFile -> stringResource(R.string.assistant_page_skills_reason_missing_skill_md)
        SkillInvalidReason.MissingYamlFrontmatter -> stringResource(
            R.string.assistant_page_skills_reason_missing_yaml_frontmatter
        )
        SkillInvalidReason.FrontmatterMustStart -> stringResource(
            R.string.assistant_page_skills_reason_frontmatter_must_start
        )
        SkillInvalidReason.FrontmatterNotClosed -> stringResource(
            R.string.assistant_page_skills_reason_frontmatter_not_closed
        )
        SkillInvalidReason.MissingName -> stringResource(R.string.assistant_page_skills_reason_missing_name)
        SkillInvalidReason.MissingDescription -> stringResource(
            R.string.assistant_page_skills_reason_missing_description
        )
        is SkillInvalidReason.FailedToRead -> stringResource(
            R.string.assistant_page_skills_reason_failed_to_read,
            reason.detail,
        )
        is SkillInvalidReason.Other -> reason.message
    }
}

@Composable
private fun SkillsInfoCard(
    title: String,
    text: String,
    isError: Boolean,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } else {
            null
        }
    }
}
