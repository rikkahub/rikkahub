package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.ToastType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.WorkdirBrowserEntry
import me.rerere.rikkahub.data.skills.WorkdirBrowserListing
import me.rerere.rikkahub.data.skills.WorkdirTextFileDocument
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private enum class WorkdirCreateKind {
    FILE,
    DIRECTORY,
}

@Composable
fun WorkdirBrowserPage(relativePath: String) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val skillsRepository = koinInject<SkillsRepository>()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    val pullToRefreshState = rememberPullToRefreshState()
    val browseErrorTitle = stringResource(R.string.assistant_page_skills_workdir_error_title)
    val openFailedText = stringResource(R.string.assistant_page_skills_workdir_open_failed)
    val actionFailedText = stringResource(R.string.assistant_page_skills_workdir_action_failed)
    val saveSuccessText = stringResource(R.string.assistant_page_skills_workdir_save_success)

    var listing by remember(relativePath) { mutableStateOf<WorkdirBrowserListing?>(null) }
    var isRefreshing by remember(relativePath) { mutableStateOf(false) }
    var errorMessage by remember(relativePath) { mutableStateOf<String?>(null) }
    var showCreateChooser by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf<WorkdirCreateKind?>(null) }
    var renameTarget by remember { mutableStateOf<WorkdirBrowserEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkdirBrowserEntry?>(null) }
    var editorDocument by remember { mutableStateOf<WorkdirTextFileDocument?>(null) }
    var isActionRunning by remember { mutableStateOf(false) }
    var isEditorSaving by remember { mutableStateOf(false) }

    fun loadDirectory(showBlocking: Boolean = false) {
        scope.launch {
            if (showBlocking || listing == null) {
                listing = if (showBlocking) null else listing
            }
            isRefreshing = true
            errorMessage = null
            try {
                listing = skillsRepository.browseWorkdir(relativePath)
            } catch (error: Throwable) {
                if (listing == null || showBlocking) {
                    listing = null
                    errorMessage = error.message ?: browseErrorTitle
                } else {
                    toaster.show(
                        error.message ?: browseErrorTitle,
                        type = ToastType.Error,
                    )
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    fun openFile(entry: WorkdirBrowserEntry) {
        scope.launch {
            isActionRunning = true
            try {
                editorDocument = skillsRepository.readWorkdirTextFile(entry.relativePath)
            } catch (error: Throwable) {
                toaster.show(
                    error.message ?: openFailedText,
                    type = ToastType.Error,
                )
            } finally {
                isActionRunning = false
            }
        }
    }

    LaunchedEffect(relativePath) {
        loadDirectory(showBlocking = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_skills_workdir_title))
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateChooser = true },
                icon = { Icon(HugeIcons.Add01, contentDescription = null) },
                text = { Text(stringResource(R.string.assistant_page_skills_workdir_new)) },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            listing?.let { currentListing ->
                WorkspaceHeader(
                    listing = currentListing,
                    onNavigateTo = { target ->
                        navController.navigate(
                            Screen.WorkdirBrowser(relativePath = target)
                        ) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { loadDirectory(showBlocking = false) },
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    listing == null && isRefreshing -> {
                        InitialLoadingState()
                    }

                    listing == null && errorMessage != null -> {
                        FullscreenMessageState(
                            icon = HugeIcons.Alert01,
                            title = stringResource(R.string.assistant_page_skills_workdir_error_title),
                            body = errorMessage.orEmpty(),
                            actionLabel = stringResource(R.string.webview_page_refresh),
                            onAction = { loadDirectory(showBlocking = true) },
                            isError = true,
                        )
                    }

                    listing != null && listing!!.entries.isEmpty() -> {
                        FullscreenMessageState(
                            icon = Lucide.FolderOpen,
                            title = stringResource(R.string.assistant_page_skills_workdir_empty_title),
                            body = stringResource(R.string.assistant_page_skills_workdir_empty),
                            actionLabel = stringResource(R.string.assistant_page_skills_workdir_create_file),
                            onAction = { createKind = WorkdirCreateKind.FILE },
                            isError = false,
                        )
                    }

                    listing != null -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                        ) {
                            items(
                                items = listing!!.entries,
                                key = { it.relativePath },
                            ) { entry ->
                                WorkdirEntryRow(
                                    entry = entry,
                                    onOpen = {
                                        if (entry.isDirectory) {
                                            navController.navigate(
                                                Screen.WorkdirBrowser(relativePath = entry.relativePath)
                                            ) {
                                                launchSingleTop = true
                                            }
                                        } else {
                                            openFile(entry)
                                        }
                                    },
                                    onRename = { renameTarget = entry },
                                    onDelete = { deleteTarget = entry },
                                    onEdit = {
                                        if (!entry.isDirectory) {
                                            openFile(entry)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateChooser) {
        ModalBottomSheet(
            onDismissRequest = { showCreateChooser = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CreateChooserSheet(
                onCreateFile = {
                    showCreateChooser = false
                    createKind = WorkdirCreateKind.FILE
                },
                onCreateFolder = {
                    showCreateChooser = false
                    createKind = WorkdirCreateKind.DIRECTORY
                },
            )
        }
    }

    createKind?.let { kind ->
        WorkdirCreateDialog(
            kind = kind,
            parentPath = listing?.currentPath.orEmpty(),
            inProgress = isActionRunning,
            onDismiss = {
                if (!isActionRunning) {
                    createKind = null
                }
            },
            onConfirm = { name, content ->
                scope.launch {
                    val currentListing = listing ?: return@launch
                    isActionRunning = true
                    try {
                        when (kind) {
                            WorkdirCreateKind.FILE -> {
                                skillsRepository.createWorkdirTextFile(
                                    parentRelativePath = currentListing.currentRelativePath,
                                    name = name,
                                    content = content,
                                )
                                toaster.show(
                                    resources.getString(
                                        R.string.assistant_page_skills_workdir_create_file_success,
                                        name,
                                    ),
                                    type = ToastType.Success,
                                )
                            }

                            WorkdirCreateKind.DIRECTORY -> {
                                skillsRepository.createWorkdirDirectory(
                                    parentRelativePath = currentListing.currentRelativePath,
                                    name = name,
                                )
                                toaster.show(
                                    resources.getString(
                                        R.string.assistant_page_skills_workdir_create_folder_success,
                                        name,
                                    ),
                                    type = ToastType.Success,
                                )
                            }
                        }
                        createKind = null
                        loadDirectory(showBlocking = false)
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: actionFailedText,
                            type = ToastType.Error,
                        )
                    } finally {
                        isActionRunning = false
                    }
                }
            },
        )
    }

    renameTarget?.let { target ->
        RenameEntryDialog(
            target = target,
            inProgress = isActionRunning,
            onDismiss = {
                if (!isActionRunning) {
                    renameTarget = null
                }
            },
            onConfirm = { newName ->
                scope.launch {
                    isActionRunning = true
                    try {
                        skillsRepository.renameWorkdirEntry(
                            relativePath = target.relativePath,
                            newName = newName,
                        )
                        toaster.show(
                            resources.getString(
                                R.string.assistant_page_skills_workdir_rename_success,
                                newName,
                            ),
                            type = ToastType.Success,
                        )
                        renameTarget = null
                        loadDirectory(showBlocking = false)
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: actionFailedText,
                            type = ToastType.Error,
                        )
                    } finally {
                        isActionRunning = false
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!isActionRunning) {
                    deleteTarget = null
                }
            },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.assistant_page_skills_workdir_delete_body,
                        target.name,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = {
                        scope.launch {
                            isActionRunning = true
                            try {
                                skillsRepository.deleteWorkdirEntry(target.relativePath)
                                toaster.show(
                                    resources.getString(
                                        R.string.assistant_page_skills_workdir_delete_success,
                                        target.name,
                                    ),
                                    type = ToastType.Success,
                                )
                                deleteTarget = null
                                loadDirectory(showBlocking = false)
                            } catch (error: Throwable) {
                                toaster.show(
                                    error.message ?: actionFailedText,
                                    type = ToastType.Error,
                                )
                            } finally {
                                isActionRunning = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = { deleteTarget = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editorDocument?.let { document ->
        TextEditorSheet(
            document = document,
            saving = isEditorSaving,
            onDismiss = {
                if (!isEditorSaving) {
                    editorDocument = null
                }
            },
            onSave = { content ->
                scope.launch {
                    isEditorSaving = true
                    try {
                        skillsRepository.writeWorkdirTextFile(
                            relativePath = document.relativePath,
                            content = content,
                        )
                        toaster.show(
                            saveSuccessText,
                            type = ToastType.Success,
                        )
                        editorDocument = document.copy(
                            content = content,
                            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                        )
                        loadDirectory(showBlocking = false)
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: actionFailedText,
                            type = ToastType.Error,
                        )
                    } finally {
                        isEditorSaving = false
                    }
                }
            },
        )
    }
}

@Composable
private fun WorkspaceHeader(
    listing: WorkdirBrowserListing,
    onNavigateTo: (String) -> Unit,
) {
    val rootLabel = stringResource(R.string.assistant_page_skills_workdir_root)
    val breadcrumbs = remember(listing.currentRelativePath, rootLabel) {
        buildBreadcrumbs(listing.currentRelativePath, rootLabel)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_page_skills_workdir_workspace),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = listing.currentPath,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(breadcrumbs, key = { "${it.path}:${it.label}" }) { crumb ->
                    AssistChip(
                        onClick = { onNavigateTo(crumb.path) },
                        label = { Text(crumb.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.assistant_page_skills_workdir_item_count,
                        listing.entries.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (listing.currentRelativePath.isNotBlank()) {
                    TextButton(onClick = { onNavigateTo(parentRelativePathOf(listing.currentRelativePath)) }) {
                        Text(stringResource(R.string.assistant_page_skills_workdir_up))
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.assistant_page_skills_workdir_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullscreenMessageState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    isError: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun WorkdirEntryRow(
    entry: WorkdirBrowserEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val iconBackground = if (entry.isDirectory) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val iconTint = if (entry.isDirectory) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .defaultMinSize(minHeight = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = iconBackground,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (entry.isDirectory) Lucide.Folder else HugeIcons.File01,
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildEntrySubtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (entry.isDirectory) {
                                    stringResource(R.string.assistant_page_skills_workdir_open_folder)
                                } else {
                                    stringResource(R.string.edit)
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (entry.isDirectory) Lucide.FolderOpen else Lucide.FilePen,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            if (entry.isDirectory) onOpen() else onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.assistant_page_skills_workdir_rename)) },
                        leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                HugeIcons.Delete01,
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
        HorizontalDivider()
    }
}

@Composable
private fun CreateChooserSheet(
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_page_skills_workdir_new),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onCreateFile,
        ) {
            Icon(HugeIcons.File01, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.assistant_page_skills_workdir_create_file))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onCreateFolder,
        ) {
            Icon(Lucide.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.assistant_page_skills_workdir_create_folder))
        }
    }
}

@Composable
private fun WorkdirCreateDialog(
    kind: WorkdirCreateKind,
    parentPath: String,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var name by rememberSaveable(kind, parentPath) { mutableStateOf("") }
    var content by rememberSaveable(kind, parentPath) { mutableStateOf("") }
    val isFile = kind == WorkdirCreateKind.FILE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isFile) {
                    stringResource(R.string.assistant_page_skills_workdir_create_file)
                } else {
                    stringResource(R.string.assistant_page_skills_workdir_create_folder)
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = parentPath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_workdir_name_label)) },
                )
                if (isFile) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.assistant_page_skills_workdir_content_label)) },
                        minLines = 6,
                        placeholder = {
                            Text(stringResource(R.string.assistant_page_skills_workdir_content_placeholder))
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotBlank() && !inProgress,
                onClick = { onConfirm(name.trim(), content) },
            ) {
                Text(
                    if (inProgress) {
                        stringResource(R.string.assistant_page_skills_workdir_action_running)
                    } else {
                        stringResource(R.string.assistant_page_skills_workdir_create_confirm)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !inProgress,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RenameEntryDialog(
    target: WorkdirBrowserEntry,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(target.relativePath) { mutableStateOf(target.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_page_skills_workdir_rename)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.assistant_page_skills_workdir_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotBlank() && !inProgress,
                onClick = { onConfirm(name.trim()) },
            ) {
                Text(
                    if (inProgress) {
                        stringResource(R.string.assistant_page_skills_workdir_action_running)
                    } else {
                        stringResource(R.string.save)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !inProgress,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun TextEditorSheet(
    document: WorkdirTextFileDocument,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by rememberSaveable(document.relativePath) { mutableStateOf(document.content) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = document.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${document.currentPath} · ${document.sizeBytes.fileSizeToString()}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.assistant_page_skills_workdir_content_label)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = !saving,
                    onClick = onDismiss,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    enabled = !saving,
                    onClick = { onSave(content) },
                ) {
                    Text(
                        if (saving) {
                            stringResource(R.string.assistant_page_skills_workdir_action_running)
                        } else {
                            stringResource(R.string.save)
                        }
                    )
                }
            }
        }
    }
}

private data class WorkdirBreadcrumb(
    val label: String,
    val path: String,
)

private fun buildBreadcrumbs(relativePath: String, rootLabel: String): List<WorkdirBreadcrumb> {
    if (relativePath.isBlank()) {
        return listOf(
            WorkdirBreadcrumb(label = rootLabel, path = ""),
        )
    }

    val breadcrumbs = mutableListOf<WorkdirBreadcrumb>()
    breadcrumbs += WorkdirBreadcrumb(label = rootLabel, path = "")
    val segments = relativePath.split('/').filter { it.isNotBlank() }
    segments.indices.forEach { index ->
        breadcrumbs += WorkdirBreadcrumb(
            label = segments[index],
            path = segments.take(index + 1).joinToString("/"),
        )
    }
    return breadcrumbs
}

private fun parentRelativePathOf(relativePath: String): String {
    return relativePath.substringBeforeLast('/', "")
}

@Composable
private fun buildEntrySubtitle(entry: WorkdirBrowserEntry): String {
    val unknownTime = stringResource(R.string.assistant_page_skills_workdir_unknown_time)
    val modified = formatWorkdirTime(entry.modifiedAtEpochSeconds, unknownTime)
    return if (entry.isDirectory) {
        "${stringResource(R.string.assistant_page_skills_workdir_folder)} · $modified"
    } else {
        "${entry.sizeBytes.fileSizeToString()} · $modified"
    }
}

private fun formatWorkdirTime(epochSeconds: Long, unknownTime: String): String {
    if (epochSeconds <= 0L) return unknownTime
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    )
}
