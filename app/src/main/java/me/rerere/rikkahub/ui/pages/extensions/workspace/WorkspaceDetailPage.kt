package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.SegmentedButtonLabel
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class CreateKind { FILE, FOLDER }

@Composable
fun WorkspaceDetailPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    // A failed file action (e.g. "folder already exists") surfaces as a toast, then is consumed so it
    // doesn't re-fire on recomposition.
    LaunchedEffect(actionError) {
        actionError?.let {
            toaster.show(it, type = ToastType.Error)
            vm.dismissActionError()
        }
    }

    var showCreateMenu by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(FileViewMode.LIST) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val fileView by vm.fileView.collectAsStateWithLifecycle()
    // New file/folder is a FILES-area, files-tab affordance only (the LINUX rootfs is installer-managed).
    val canCreate = pagerState.currentPage == 1 && state.area == WorkspaceStorageArea.FILES

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        floatingActionButton = {
            if (canCreate) {
                Box {
                    FloatingActionButton(onClick = { showCreateMenu = true }) {
                        Icon(HugeIcons.Add01, contentDescription = "Create")
                    }
                    DropdownMenu(
                        expanded = showCreateMenu,
                        onDismissRequest = { showCreateMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("New file") },
                            leadingIcon = { Icon(HugeIcons.File02, contentDescription = null) },
                            onClick = {
                                showCreateMenu = false
                                createKind = CreateKind.FILE
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            leadingIcon = { Icon(HugeIcons.Folder01, contentDescription = null) },
                            onClick = {
                                showCreateMenu = false
                                createKind = CreateKind.FOLDER
                            },
                        )
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    onToolApprovalChange = vm::setToolApproval,
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    viewMode = viewMode,
                    onToggleViewMode = {
                        viewMode = if (viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST
                    },
                    onSelectArea = vm::selectArea,
                    onBrowseTo = vm::browseTo,
                    onOpen = vm::open,
                    onView = vm::openFile,
                    onDelete = { deleteTarget = it },
                    onSetProjectDir = vm::setCurrentAsProjectDir,
                    onClearProjectDir = vm::clearProjectDir,
                )
            }
        }
    }

    createKind?.let { kind ->
        CreateEntryDialog(
            kind = kind,
            onConfirm = { name ->
                when (kind) {
                    CreateKind.FILE -> vm.createFile(name)
                    CreateKind.FOLDER -> vm.createFolder(name)
                }
                createKind = null
            },
            onDismiss = { createKind = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    if (target.isDirectory) {
                        "This folder and everything inside it will be permanently deleted."
                    } else {
                        "This file will be permanently deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteEntry(target)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    fileView?.let { view ->
        FileViewerSheet(state = view, onDismiss = vm::closeFile, onSave = vm::saveFile)
    }
}

@Composable
private fun CreateEntryDialog(
    kind: CreateKind,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val title = if (kind == CreateKind.FOLDER) "New folder" else "New file"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(if (kind == CreateKind.FOLDER) "Folder name" else "File name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_section_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val loading = stringResource(R.string.workspace_detail_loading)
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: loading)
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_id), workspace?.id ?: "-")
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_root), workspace?.root ?: "-")
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_shell_status),
                        workspace?.shellStatus?.lowercase() ?: "-",
                    )
                }
            }
        }

        // Flavor seam (I-FLAVOR): the shell-enable toggle / rootfs-install / terminal entry render
        // ONLY in the sideload flavor (slice 6b fills its body). The play body is empty, so these
        // controls are physically absent from the Play APK. Slice 6a ships both bodies empty.
        item {
            SideloadWorkspaceControls(workspace = workspace)
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    // null = corrupt/unparseable policy blob; pass it through (do NOT .orEmpty()) so the switches
    // render the fail-CLOSED state resolveWorkspaceToolApproval enforces for the tool consumer,
    // instead of misrepresenting a corrupt row as the relaxed defaults (#197 slice 6a review).
    val overrides = workspace?.toolApprovalOverrides()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_tool_approval_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems(): List<Pair<String, String>> = listOf(
    "workspace_list_files" to stringResource(R.string.workspace_tool_list_label),
    "workspace_read_file" to stringResource(R.string.workspace_tool_read_label),
    "workspace_write_file" to stringResource(R.string.workspace_tool_write_label),
    "workspace_edit_file" to stringResource(R.string.workspace_tool_edit_label),
    "workspace_delete_file" to stringResource(R.string.workspace_tool_delete_label),
    "workspace_move_file" to stringResource(R.string.workspace_tool_move_label),
    "workspace_shell" to stringResource(R.string.workspace_tool_shell_label),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class FileViewMode { LIST, GRID }

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    viewMode: FileViewMode,
    onToggleViewMode: () -> Unit,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onBrowseTo: (String) -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onView: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onSetProjectDir: () -> Unit,
    onClearProjectDir: () -> Unit,
) {
    val isFiles = state.area == WorkspaceStorageArea.FILES
    LazyVerticalGrid(
        // Fixed(1) renders the list rows full-width; Adaptive packs compact tiles for the grid view.
        columns = if (viewMode == FileViewMode.GRID) GridCells.Adaptive(108.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    WorkspaceAreaSelector(selected = state.area, onSelected = onSelectArea)
                }
                IconButton(onClick = onToggleViewMode) {
                    Icon(
                        imageVector = if (viewMode == FileViewMode.LIST) {
                            Lucide.LayoutGrid
                        } else {
                            HugeIcons.LeftToRightListBullet
                        },
                        contentDescription = "Toggle view",
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            WorkspaceBreadcrumb(path = state.path, onNavigate = onBrowseTo)
        }

        // Project directory is a FILES-area concept (the agent's cwd seed); hide it for the LINUX rootfs.
        if (isFiles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ProjectDirBar(
                    currentPath = state.path,
                    projectDir = state.workspace?.workingDir.orEmpty(),
                    onSet = onSetProjectDir,
                    onClear = onClearProjectDir,
                )
            }
        }

        if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorCard(stringResource(R.string.workspace_files_load_error))
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyDirectoryState()
            }
        }

        gridItems(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            val onActivate = { if (entry.isDirectory) onOpen(entry) else onView(entry) }
            if (viewMode == FileViewMode.GRID) {
                WorkspaceFileTile(entry = entry, onOpen = onActivate, onDelete = { onDelete(entry) })
            } else {
                WorkspaceFileCard(entry = entry, onOpen = onActivate, onDelete = { onDelete(entry) })
            }
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_files_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_files_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                SegmentedButtonLabel(label)
            }
        }
    }
}

@Composable
private fun WorkspaceBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
) {
    val segments = path.split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Root crumb: navigable from any sub-path.
        BreadcrumbCrumb(
            label = stringResource(R.string.workspace_files_root_path),
            enabled = path.isNotBlank(),
            onClick = { onNavigate("") },
        )
        segments.forEachIndexed { index, segment ->
            Text(
                text = " › ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val target = segments.take(index + 1).joinToString("/")
            BreadcrumbCrumb(
                label = segment,
                enabled = index < segments.lastIndex,
                onClick = { onNavigate(target) },
            )
        }
    }
}

@Composable
private fun BreadcrumbCrumb(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = if (enabled) {
            Modifier
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 2.dp)
        } else {
            Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
        },
    )
}

@Composable
private fun ProjectDirBar(
    currentPath: String,
    projectDir: String,
    onSet: () -> Unit,
    onClear: () -> Unit,
) {
    val isCurrent = currentPath == projectDir
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Project directory",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = (if (isCurrent) "✓ " else "") +
                        projectDir.ifBlank { "Files root (default)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isCurrent) {
                FilledTonalButton(onClick = onSet) {
                    Text("Set here")
                }
            } else if (projectDir.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = fileTint(entry),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${formatBytes(entry.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FileEntryMenu(onDelete = onDelete)
        }
    }
}

@Composable
private fun WorkspaceFileTile(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = fileTint(entry),
                )
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        FileEntryMenu(
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun FileEntryMenu(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                HugeIcons.MoreVertical,
                contentDescription = "More",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Lucide.Trash2, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerSheet(
    state: FileViewState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Edit state is keyed to the open file's path so switching files resets it; draft also resets when
    // the persisted content changes (e.g. after a save round-trips through the VM).
    var editing by remember(state.path) { mutableStateOf(false) }
    var draft by remember(state.path, state.content) { mutableStateOf(state.content.orEmpty()) }
    val canEdit = !state.loading && !state.isBinary && state.content != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canEdit) {
                    if (editing) {
                        IconButton(onClick = {
                            draft = state.content.orEmpty()
                            editing = false
                        }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Cancel")
                        }
                        IconButton(onClick = {
                            onSave(draft)
                            editing = false
                        }) {
                            Icon(
                                HugeIcons.CheckmarkCircle01,
                                contentDescription = "Save",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(onClick = { editing = true }) {
                            Icon(HugeIcons.Edit01, contentDescription = "Edit")
                        }
                    }
                }
            }
            when {
                state.loading -> Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.isBinary -> Text(
                    text = "Binary file — not viewable as text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                editing -> OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Bounded so the field scrolls internally instead of fighting the sheet's scroll.
                        .heightIn(min = 240.dp, max = 480.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                state.content.isNullOrEmpty() -> Text(
                    text = "(empty file)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Reuse the app's Prism-based code block so code files get syntax colors (it also brings
                // line numbers + copy + collapse). An unsupported language degrades to plain text, the
                // same as a fenced code block with an unknown language in chat.
                else -> HighlightCodeBlock(
                    code = state.content,
                    language = prismLanguageFor(state.name),
                    modifier = Modifier.fillMaxWidth(),
                    // The viewer always wraps + numbers lines (a narrow sheet shouldn't need horizontal
                    // scrolling), independent of the global code-block display settings.
                    forceWrap = true,
                    forceLineNumbers = true,
                )
            }
        }
    }
}

/** Map a file name to a Prism language id for syntax highlighting; unknown → plaintext (renders plain). */
private fun prismLanguageFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "jsx" -> "jsx"
    "tsx" -> "tsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "xml", "html", "htm", "svg" -> "markup"
    "css" -> "css"
    "scss", "sass" -> "scss"
    "sql" -> "sql"
    "md", "markdown" -> "markdown"
    "gradle" -> "groovy"
    "properties", "ini", "cfg", "conf", "env" -> "properties"
    else -> "plaintext"
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_files_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A subtle, theme-token tint hinting at the file kind (the concept's per-type icons, adapted to the
 * existing M3 palette rather than brand logos). Directories use primary; source/markup, data/config,
 * and docs each get a distinct role color; everything else falls back to onSurfaceVariant.
 */
@Composable
private fun fileTint(entry: WorkspaceFileEntry): Color {
    if (entry.isDirectory) return MaterialTheme.colorScheme.primary
    return when (entry.name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs",
        "c", "cc", "cpp", "h", "hpp", "swift", "sh", "bash", "gradle", "html", "htm", "css",
        "scss", "vue", "svelte" -> MaterialTheme.colorScheme.tertiary

        "json", "xml", "yaml", "yml", "toml", "properties", "ini", "cfg", "conf", "env", "lock"
            -> MaterialTheme.colorScheme.secondary

        "md", "markdown", "txt", "rst", "adoc" -> MaterialTheme.colorScheme.primary

        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
