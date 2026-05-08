package me.rerere.rikkahub.ui.pages.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(
                            imageVector = Lucide.Globe,
                            contentDescription = stringResource(R.string.skill_catalog_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.skills_page_import_from_github)
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Puzzle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(skills, key = { it.name }) { skill ->
                SkillCard(
                    skill = skill,
                    onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                    onDelete = { deleteTarget = skill },
                )
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(context.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    // Phase 19C — local-file picker. Lifted to screen scope so the launcher survives
    // dialog dismiss/recreate (per Compose docs: `rememberLauncherForActivityResult`
    // belongs at the highest stable composable, NOT inside a dialog body).
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importFromLocalFile(uri) { success, message ->
                showImportDialog = false
                if (success) {
                    toaster.show(context.getString(R.string.skills_page_import_success, message))
                } else {
                    val key = mapImportErrorKeyToString(context, message)
                    toaster.show(context.getString(R.string.skills_page_import_failed, key))
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
            onPickFile = {
                openDocumentLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    if (showCatalog) {
        val installedNames by vm.installedSkillNames.collectAsStateWithLifecycle()
        FeaturedCatalogSheet(
            entries = vm.catalog.skills,
            installedNames = installedNames,
            onInstall = { entry, onDone ->
                vm.installFromCatalog(entry) { success, message ->
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(context.getString(R.string.skill_catalog_install_failed, key))
                    }
                    onDone()
                }
            },
            onDismiss = { showCatalog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeaturedCatalogSheet(
    entries: List<CatalogEntry>,
    installedNames: Set<String>,
    onInstall: (CatalogEntry, onResult: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_catalog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        CatalogRow(
                            entry = entry,
                            installed = entry.name in installedNames,
                            installing = entry.name in installing,
                            onInstall = {
                                installing = installing + entry.name
                                onInstall(entry) {
                                    installing = installing - entry.name
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${entry.category} · ${entry.sizeKb} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installed && !installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    when {
                        installed -> stringResource(R.string.skill_catalog_installed)
                        installing -> stringResource(R.string.skill_catalog_installing)
                        else -> stringResource(R.string.skill_catalog_install)
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Puzzle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (!skill.compatibility.isNullOrBlank()) {
                    Text(
                        text = skill.compatibility,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"...\"\n---\n\nSkill body goes here...",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    if (nameError) Text(
                        stringResource(R.string.skills_page_name_error),
                        color = MaterialTheme.colorScheme.error
                    )
                    else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                    else Text(stringResource(R.string.skills_page_paste_hint))
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
    onPickFile: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Phase 19C — secondary action that defers to the screen-level file
                // launcher. The dialog passes through; SkillsPage handles the SAF
                // OpenDocument result and routes to the VM.
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_import_from_file_label))
                }
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Map a VM-emitted error key (one of the `skill_import_*` resource names) to its
 * localized string. We use this rather than passing string-resource IDs out of the VM
 * because the VM has no `Context` callback path for stringResource lookups.
 */
private fun mapImportErrorKeyToString(context: android.content.Context, key: String): String {
    return when (key) {
        "skill_import_unsupported_file_type" ->
            context.getString(R.string.skill_import_unsupported_file_type)
        "skill_import_missing_skill_md" ->
            context.getString(R.string.skill_import_missing_skill_md)
        "skill_import_path_traversal" ->
            context.getString(R.string.skill_import_path_traversal)
        "skill_import_zip_too_large" ->
            context.getString(R.string.skill_import_zip_too_large)
        "skill_import_md_too_large" ->
            context.getString(R.string.skill_import_md_too_large)
        "skill_import_empty_file" ->
            context.getString(R.string.skill_import_empty_file)
        else -> key  // already a free-form message (e.g. importer's "html_response" detail)
    }
}
