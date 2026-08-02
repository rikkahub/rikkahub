package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.toShellStatusLabel
import org.koin.compose.koinInject

@Composable
internal fun WorkspaceSelectSheet(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDetails: ((String) -> Unit)? = null,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_select),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WorkspaceSelectRow(
                    title = stringResource(R.string.workspace_no_binding),
                    selected = assistant.workspaceId == null,
                    onClick = { onSelect(null) },
                )
                workspaces.forEach { workspace ->
                    WorkspaceSelectRow(
                        title = workspace.name,
                        status = workspace.shellStatus.toShellStatusLabel(),
                        selected = workspace.id == assistant.workspaceId?.toString(),
                        onClick = { onSelect(workspace.id) },
                        onOpenDetails = onOpenDetails?.let { open ->
                            {
                                onDismiss()
                                open(workspace.id)
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ListItem(
                leadingContent = { Icon(HugeIcons.Add01, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.workspace_page_create)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { showCreateDialog = true },
            )

            ListItem(
                leadingContent = { Icon(HugeIcons.Codesandbox, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.workspace_manage)) },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onManage() },
            )
        }
    }

    if (showCreateDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val trimmedName = name.trim()
        val duplicate = workspaces.any { it.name.trim() == trimmedName }

        AlertDialog(
            onDismissRequest = { if (!creating) showCreateDialog = false },
            title = { Text(stringResource(R.string.workspace_page_create)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.workspace_page_name)) },
                    supportingText = {
                        when {
                            duplicate -> Text(stringResource(R.string.workspace_page_name_duplicate))
                            error != null -> Text(error.orEmpty())
                        }
                    },
                    isError = duplicate || error != null,
                    singleLine = true,
                    enabled = !creating,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = trimmedName.isNotEmpty() && !duplicate && !creating,
                    onClick = {
                        scope.launch {
                            creating = true
                            runCatching { workspaceRepository.create(trimmedName) }
                                .onSuccess { workspace ->
                                    showCreateDialog = false
                                    onSelect(workspace.id)
                                }
                                .onFailure { throwable ->
                                    error = throwable.message
                                    creating = false
                                }
                        }
                    },
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creating,
                    onClick = { showCreateDialog = false },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceSelectRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    status: String? = null,
    onOpenDetails: (() -> Unit)? = null,
) {
    ListItem(
        leadingContent = { Icon(HugeIcons.Codesandbox, contentDescription = null) },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = status?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = if (selected || onOpenDetails != null) {
            {
                Row {
                    if (onOpenDetails != null) {
                        IconButton(onClick = onOpenDetails) {
                            Icon(
                                imageVector = HugeIcons.Settings02,
                                contentDescription = stringResource(R.string.workspace_detail),
                            )
                        }
                    }
                    if (selected) {
                        Icon(
                            imageVector = HugeIcons.Tick02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { onClick() },
    )
}
