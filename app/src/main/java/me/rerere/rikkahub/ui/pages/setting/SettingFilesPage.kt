package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUpDown
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileFolders.ALL as ALL_FOLDERS
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.CardFileGrid
import me.rerere.rikkahub.ui.pages.setting.components.CompactFileColumn
import me.rerere.rikkahub.ui.pages.setting.components.FileActions
import me.rerere.rikkahub.ui.pages.setting.components.FilePreviewSheet
import me.rerere.rikkahub.ui.pages.setting.components.ListFileColumn
import me.rerere.rikkahub.ui.pages.setting.components.layoutIcon
import me.rerere.rikkahub.ui.pages.setting.components.sortModeDisplayName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.reorderWithFirst
import org.koin.androidx.compose.koinViewModel
import java.io.File

private const val TAG = "SettingFilesPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    vm: SettingFilesVM = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val layoutDirection = LocalLayoutDirection.current

    val files by vm.files.collectAsState()
    val selectedFolder by vm.selectedFolder.collectAsState()
    val layoutMode by vm.layoutMode.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val isMultiSelectMode by vm.isMultiSelectMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val isSyncing by vm.isSyncing.collectAsState()
    val fileCount by vm.fileCount.collectAsState()
    val fileTotalSize by vm.fileTotalSize.collectAsState()

    // Pre-fetch string resources
    val savedToast = stringResource(R.string.setting_files_page_save_success)
    val saveFailedToast = stringResource(R.string.setting_files_page_save_failed)
    val shareFailedToast = stringResource(R.string.setting_files_page_share_failed)
    val syncNoneToast = stringResource(R.string.setting_files_page_sync_none)
    val syncCompleteTemplate = stringResource(R.string.setting_files_page_sync_complete)
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)

    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<ManagedFileEntity?>(null) }

    // pendingSaveFileOnDiskPath is a String that can be restored via rememberSaveable.
    var pendingSaveFileOnDiskPath by rememberSaveable { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val path = pendingSaveFileOnDiskPath ?: return@rememberLauncherForActivityResult
        val file = File(path)
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                    toaster.show(savedToast)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save file via CreateDocument", e)
                    toaster.show(saveFailedToast)
                }
            }
        }
        pendingSaveFileOnDiskPath = null
    }

    // Handle save: image → MediaStore, other → CreateDocument
    // Stable lambda — context, toaster, and string resources are effectively
    // constant across recompositions; pendingSaveFileOnDiskPath and
    // createDocumentLauncher are stable references from rememberSaveable /
    // rememberLauncherForActivityResult.
    val handleSave = remember {
        { entity: ManagedFileEntity, fileOnDisk: File ->
            if (entity.mimeType.startsWith("image/")) {
                val activity = context.getActivity()
                if (activity != null) {
                    try {
                        context.exportImageFile(activity, fileOnDisk, entity.displayName)
                        toaster.show(savedToast)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save image to MediaStore", e)
                        toaster.show(saveFailedToast)
                    }
                } else {
                    toaster.show(saveFailedToast)
                }
            } else {
                pendingSaveFileOnDiskPath = fileOnDisk.absolutePath
                createDocumentLauncher.launch(entity.displayName)
            }
        }
    }

    // Handle share — stable lambda, all captures are stable.
    val handleShare = remember {
        { entity: ManagedFileEntity, fileOnDisk: File ->
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    fileOnDisk,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    setDataAndType(uri, entity.mimeType)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to share file", e)
                toaster.show(shareFailedToast)
            }
        }
    }

    // Handle batch share — reads selectedIds / files from VM StateFlow
    // at call time so the callback always sees the latest state, even
    // though the lambda reference itself is stable across recompositions.
    val handleBatchShare = remember {
        { ->
            try {
                val ids = vm.selectedIds.value
                val allFiles = vm.files.value
                val uris = ids.mapNotNull { id ->
                    val entity = allFiles.find { it.id == id } ?: return@mapNotNull null
                    val fileOnDisk = vm.getFileForEntity(entity)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        fileOnDisk,
                    )
                }
                if (uris.isEmpty()) return@remember
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    val types = uris.mapNotNull { context.contentResolver.getType(it) }.distinct()
                    type = if (types.size == 1) types.first() else "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to batch share files", e)
                toaster.show(shareFailedToast)
            }
        }
    }

    // Back handler for multi-select mode
    BackHandler(enabled = isMultiSelectMode) {
        vm.exitMultiSelectMode()
    }

    // Collect one-shot events from ViewModel
    LaunchedEffect(Unit) {
        vm.events.collectLatest { event ->
            when (event) {
                is FilesEvent.DeleteResult -> {
                    if (event.count > 0) toaster.show(deletedToast)
                    else toaster.show(deleteFailedToast)
                }
                is FilesEvent.SyncResult -> {
                    if (event.count > 0) {
                        toaster.show(syncCompleteTemplate.format(event.count))
                    } else {
                        toaster.show(syncNoneToast)
                    }
                }
            }
        }
    }

    // Single delete confirmation
    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = vm.deleteFile(target.id)
                        if (ok) toaster.show(deletedToast)
                        else toaster.show(deleteFailedToast)
                        pendingDelete = null
                    }
                }) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            },
        )
    }

    // Batch delete confirmation
    if (pendingBatchDelete) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.setting_files_page_delete_selected_title)) },
            text = { Text(stringResource(R.string.setting_files_page_delete_selected_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSelected()
                    pendingBatchDelete = false
                }) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            },
        )
    }

    // Preview
    previewFile?.let { file ->
        val fileOnDisk = vm.getFileForEntity(file)

        if (file.mimeType.startsWith("image/")) {
            val allPaths = vm.getImagePaths()
            ImagePreviewDialog(
                images = allPaths.reorderWithFirst(fileOnDisk.absolutePath),
                onDismissRequest = { previewFile = null },
            )
        } else {
            FilePreviewSheet(
                file = file,
                fileOnDisk = fileOnDisk,
                onDismiss = { previewFile = null },
            )
        }
    }

    Scaffold(
        modifier = if (!isMultiSelectMode) {
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
        topBar = {
            if (isMultiSelectMode) {
                MultiSelectTopBar(
                    selectedCount = selectedIds.size,
                    onCancel = { vm.exitMultiSelectMode() },
                    onSelectAll = { vm.selectAll() },
                    onDelete = { pendingBatchDelete = true },
                    onShare = { handleBatchShare() },
                )
            } else {
                NormalTopBar(
                    scrollBehavior = scrollBehavior,
                    layoutMode = layoutMode,
                    onToggleLayout = {
                        vm.setLayoutMode(
                            when (layoutMode) {
                                LayoutMode.CARD -> LayoutMode.LIST
                                LayoutMode.LIST -> LayoutMode.COMPACT
                                LayoutMode.COMPACT -> LayoutMode.CARD
                            }
                        )
                    },
                    sortMode = sortMode,
                    onSortSelected = { vm.setSortMode(it) },
                    isSyncing = isSyncing,
                    onSync = { vm.syncFolder() },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            // Folder chips
            FolderRow(
                selectedFolder = selectedFolder,
                onFolderSelected = { vm.selectFolder(it) },
            )

            // Stats
            if (fileCount > 0) {
                val statsText = if (fileCount == 1) {
                    stringResource(R.string.setting_files_page_stats_single, fileTotalSize.fileSizeToString())
                } else {
                    stringResource(R.string.setting_files_page_stats, fileCount, fileTotalSize.fileSizeToString())
                }
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // File content
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                val contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
                val actions = FileActions(
                    isMultiSelectMode = isMultiSelectMode,
                    selectedIds = selectedIds,
                    onFileClick = { previewFile = it },
                    onFileLongClick = { vm.enterMultiSelectMode(it.id) },
                    onToggleSelection = { vm.toggleSelection(it) },
                    onSave = { entity -> handleSave(entity, vm.getFileForEntity(entity)) },
                    onShare = { entity -> handleShare(entity, vm.getFileForEntity(entity)) },
                    onDelete = { pendingDelete = it },
                    getFileOnDisk = { vm.getFileForEntity(it) },
                )
                when (layoutMode) {
                    LayoutMode.CARD -> CardFileGrid(
                        files = files,
                        actions = actions,
                        contentPadding = contentPadding,
                    )
                    LayoutMode.LIST -> ListFileColumn(
                        files = files,
                        actions = actions,
                        contentPadding = contentPadding,
                    )
                    LayoutMode.COMPACT -> CompactFileColumn(
                        files = files,
                        actions = actions,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}

// ==================== Top Bars ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    layoutMode: LayoutMode,
    onToggleLayout: () -> Unit,
    sortMode: SortMode,
    onSortSelected: (SortMode) -> Unit,
    isSyncing: Boolean,
    onSync: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }

    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.setting_files_page_title)) },
        navigationIcon = { BackButton() },
        scrollBehavior = scrollBehavior,
        colors = CustomColors.topBarColors,
        actions = {
            // Sync button
            IconButton(onClick = onSync, enabled = !isSyncing) {
                Icon(
                    imageVector = HugeIcons.Refresh,
                    contentDescription = stringResource(R.string.setting_files_page_sync),
                )
            }

            // Sort button
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(
                        imageVector = HugeIcons.ArrowUpDown,
                        contentDescription = stringResource(R.string.setting_files_page_sort),
                    )
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    offset = DpOffset(0.dp, 0.dp),
                ) {
                    SortMode.entries.forEach { mode ->
                        val isSelected = mode == sortMode
                        DropdownMenuItem(
                            text = { Text(sortModeDisplayName(mode)) },
                            onClick = {
                                onSortSelected(mode)
                                showSortMenu = false
                            },
                            leadingIcon = if (isSelected) {
                                { Icon(HugeIcons.Tick01, null, Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                }
            }

            // Layout toggle
            IconButton(onClick = onToggleLayout) {
                Icon(
                    imageVector = layoutIcon(layoutMode),
                    contentDescription = stringResource(R.string.setting_files_page_layout),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectTopBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(stringResource(R.string.setting_files_page_selected_count, selectedCount))
        },
        navigationIcon = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.setting_files_page_cancel))
            }
        },
        colors = CustomColors.topBarColors,
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = HugeIcons.CheckList,
                    contentDescription = stringResource(R.string.setting_files_page_select_all),
                )
            }
            IconButton(onClick = onShare, enabled = selectedCount > 0) {
                Icon(
                    imageVector = HugeIcons.Share01,
                    contentDescription = stringResource(R.string.setting_files_page_share),
                )
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.setting_files_page_delete_selected),
                )
            }
        },
    )
}

// ==================== Folder Row ====================

@Composable
private fun FolderRow(
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ALL_FOLDERS.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) },
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.IMAGES -> stringResource(R.string.setting_files_page_folder_images)
    FileFolders.SKILLS -> stringResource(R.string.setting_files_page_folder_skills)
    FileFolders.FONTS -> stringResource(R.string.setting_files_page_folder_fonts)
    FileFolders.TOOL_OUTPUTS -> stringResource(R.string.setting_files_page_folder_tool_outputs)
    else -> folder
}
