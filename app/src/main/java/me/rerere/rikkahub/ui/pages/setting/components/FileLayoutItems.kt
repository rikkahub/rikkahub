package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.LayoutGrid
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.TextAlignLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.ui.pages.setting.LayoutMode
import me.rerere.rikkahub.ui.pages.setting.SortMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File

data class FileActions(
    val isMultiSelectMode: Boolean,
    val selectedIds: Set<Long>,
    val onFileClick: (ManagedFileEntity) -> Unit,
    val onFileLongClick: (ManagedFileEntity) -> Unit,
    val onToggleSelection: (Long) -> Unit,
    val onSave: (ManagedFileEntity) -> Unit,
    val onShare: ((ManagedFileEntity) -> Unit)? = null, // null for Compact which has no share
    val onDelete: (ManagedFileEntity) -> Unit,
    val getFileOnDisk: (ManagedFileEntity) -> File,
)

// ==================== Layout: Card Grid ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardFileGrid(
    files: List<ManagedFileEntity>,
    actions: FileActions,
    contentPadding: PaddingValues,
) {
    LazyVerticalStaggeredGrid(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        columns = StaggeredGridCells.Fixed(2),
    ) {
        items(files, key = { it.id }) { file ->
            CardFileItem(
                file = file,
                fileOnDisk = actions.getFileOnDisk(file),
                isSelected = file.id in actions.selectedIds,
                showCheckbox = actions.isMultiSelectMode,
                onClick = {
                    if (actions.isMultiSelectMode) {
                        actions.onToggleSelection(file.id)
                    } else {
                        actions.onFileClick(file)
                    }
                },
                onLongClick = { actions.onFileLongClick(file) },
                onSave = { actions.onSave(file) },
                onShare = { actions.onShare?.invoke(file) },
                onDelete = { actions.onDelete(file) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardFileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                CustomColors.listItemColors.containerColor.copy(alpha = 0.7f)
            else
                CustomColors.listItemColors.containerColor
        ),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(fileOnDisk)
                            .build(),
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center,
                    ) {
                        FileTypeIcon(
                            mimeType = file.mimeType,
                            size = 56.dp,
                        )
                    }
                }

                if (showCheckbox) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = file.sizeBytes.fileSizeToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FileActionButtons(
                        showShare = true,
                        onSave = onSave,
                        onShare = onShare,
                        onDelete = onDelete,
                        buttonSize = 32.dp,
                        iconSize = 18.dp,
                    )
                }
            }
        }
    }
}

// ==================== Layout: List ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListFileColumn(
    files: List<ManagedFileEntity>,
    actions: FileActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(files, key = { it.id }) { file ->
            ListFileItem(
                file = file,
                fileOnDisk = actions.getFileOnDisk(file),
                isSelected = file.id in actions.selectedIds,
                showCheckbox = actions.isMultiSelectMode,
                onClick = {
                    if (actions.isMultiSelectMode) {
                        actions.onToggleSelection(file.id)
                    } else {
                        actions.onFileClick(file)
                    }
                },
                onLongClick = { actions.onFileLongClick(file) },
                onSave = { actions.onSave(file) },
                onShare = { actions.onShare?.invoke(file) },
                onDelete = { actions.onDelete(file) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListFileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                CustomColors.listItemColors.containerColor.copy(alpha = 0.7f)
            else
                CustomColors.listItemColors.containerColor
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCheckbox) {
                Row {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            FileTypeIcon(
                mimeType = file.mimeType,
                filePath = fileOnDisk.absolutePath,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${file.mimeType} · ${file.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (!showCheckbox) {
                FileActionButtons(
                    showShare = true,
                    onSave = onSave,
                    onShare = onShare,
                    onDelete = onDelete,
                )
            }
        }
    }
}

// ==================== Layout: Compact ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactFileColumn(
    files: List<ManagedFileEntity>,
    actions: FileActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(files, key = { it.id }) { file ->
            CompactFileItem(
                file = file,
                fileOnDisk = actions.getFileOnDisk(file),
                isSelected = file.id in actions.selectedIds,
                showCheckbox = actions.isMultiSelectMode,
                onClick = {
                    if (actions.isMultiSelectMode) {
                        actions.onToggleSelection(file.id)
                    } else {
                        actions.onFileClick(file)
                    }
                },
                onLongClick = { actions.onFileLongClick(file) },
                onSave = { actions.onSave(file) },
                onDelete = { actions.onDelete(file) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = showCheckbox) {
            Row {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        FileTypeIcon(
            mimeType = file.mimeType,
            filePath = fileOnDisk.absolutePath,
            modifier = Modifier.size(32.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.sizeBytes.fileSizeToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!showCheckbox) {
            FileActionButtons(
                showShare = false,
                onSave = onSave,
                onShare = {},
                onDelete = onDelete,
                buttonSize = 32.dp,
                iconSize = 16.dp,
            )
        }
    }
}

// ==================== Helpers ====================

@Composable
fun FileActionButtons(
    showShare: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 18.dp,
) {
    IconButton(onClick = onSave, modifier = Modifier.size(buttonSize)) {
        Icon(
            imageVector = HugeIcons.Download01,
            contentDescription = stringResource(R.string.setting_files_page_save),
            modifier = Modifier.size(iconSize),
        )
    }
    if (showShare) {
        IconButton(onClick = onShare, modifier = Modifier.size(buttonSize)) {
            Icon(
                imageVector = HugeIcons.Share01,
                contentDescription = stringResource(R.string.setting_files_page_share),
                modifier = Modifier.size(iconSize),
            )
        }
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(buttonSize)) {
        Icon(
            imageVector = HugeIcons.Delete01,
            contentDescription = stringResource(R.string.setting_files_page_delete_content_description),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun sortModeDisplayName(mode: SortMode): String = when (mode) {
    SortMode.DATE_DESC -> stringResource(R.string.setting_files_page_sort_date_desc)
    SortMode.DATE_ASC -> stringResource(R.string.setting_files_page_sort_date_asc)
    SortMode.NAME_ASC -> stringResource(R.string.setting_files_page_sort_name_asc)
    SortMode.NAME_DESC -> stringResource(R.string.setting_files_page_sort_name_desc)
    SortMode.SIZE_DESC -> stringResource(R.string.setting_files_page_sort_size_desc)
    SortMode.SIZE_ASC -> stringResource(R.string.setting_files_page_sort_size_asc)
}

@Composable
fun layoutIcon(mode: LayoutMode) = when (mode) {
    LayoutMode.CARD -> HugeIcons.LayoutGrid
    LayoutMode.LIST -> HugeIcons.ListView
    LayoutMode.COMPACT -> HugeIcons.TextAlignLeft01
}