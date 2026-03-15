package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.GripHorizontal
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.MessageTemplateSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.MessageInjectionTemplate
import me.rerere.rikkahub.data.model.MessageTemplateNode
import me.rerere.rikkahub.data.model.TemplateRoleMapping
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MessageTemplateEditorTab(
    template: MessageInjectionTemplate,
    onUpdate: (MessageInjectionTemplate) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    // Reorderable indices include non-template items at the top of the list.
    val fixedItemCount = 1 + if (!template.enabled) 1 else 0
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(template, MessageTemplateSerializer)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - fixedItemCount
        val toIndex = to.index - fixedItemCount
        if (fromIndex !in template.nodes.indices || toIndex !in template.nodes.indices) {
            return@rememberReorderableLazyListState
        }

        val newNodes = template.nodes.toMutableList()
        val moved = newNodes.removeAt(fromIndex)
        newNodes.add(toIndex, moved)
        onUpdate(template.copy(nodes = newNodes))
    }
    val editState = useEditState<MessageTemplateNode> { edited ->
        val index = template.nodes.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(template.copy(nodes = template.nodes.toMutableList().apply { set(index, edited) }))
        } else {
            onUpdate(template.copy(nodes = template.nodes + edited))
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val defaultPromptName = stringResource(R.string.prompt_page_message_template_prompt_default_name)
    val importer = rememberImporter(MessageTemplateSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(imported.copy(enabled = template.enabled))
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        item {
            MessageTemplateControlCard(
                enabled = template.enabled,
                onToggleEnabled = { enabled ->
                    onUpdate(template.copy(enabled = enabled))
                },
                onImport = { importer.importFromFile() },
                onExport = { showExportDialog = true },
                onAddPrompt = {
                    editState.open(
                        MessageTemplateNode.PromptNode(
                            name = defaultPromptName
                        )
                    )
                },
                onAddHistory = {
                    onUpdate(template.copy(nodes = template.nodes + MessageTemplateNode.HistoryNode()))
                },
                onAddLastUser = {
                    onUpdate(template.copy(nodes = template.nodes + MessageTemplateNode.LastUserMessageNode()))
                }
            )
        }

        if (!template.enabled) {
            item {
                Text(
                    text = stringResource(R.string.prompt_page_message_template_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        if (template.nodes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.prompt_page_message_template_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        } else {
            items(template.nodes, key = { it.id }) { node ->
                ReorderableItem(
                    state = reorderableState,
                    key = node.id
                ) { isDragging ->
                    MessageTemplateNodeCard(
                        node = node,
                        modifier = Modifier
                            .longPressDraggableHandle()
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            },
                        onToggleEnabled = { enabled ->
                            val nextNode = when (node) {
                                is MessageTemplateNode.PromptNode -> node.copy(enabled = enabled)
                                is MessageTemplateNode.HistoryNode -> node.copy(enabled = enabled)
                                is MessageTemplateNode.LastUserMessageNode -> node.copy(enabled = enabled)
                            }
                            val index = template.nodes.indexOfFirst { it.id == node.id }
                            if (index >= 0) {
                                onUpdate(template.copy(nodes = template.nodes.toMutableList().apply { set(index, nextNode) }))
                            }
                        },
                        onEdit = { editState.open(node) },
                        onDelete = {
                            onUpdate(template.copy(nodes = template.nodes.filter { it.id != node.id }))
                        }
                    )
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { node ->
            MessageTemplateNodeEditSheet(
                node = node,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun MessageTemplateControlCard(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAddPrompt: () -> Unit,
    onAddHistory: () -> Unit,
    onAddLastUser: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_tab),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.Import, null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_import),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.FileDown, null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_export),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAddPrompt,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.Plus, null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_add_prompt),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onAddHistory,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.Plus, null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_add_history),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAddLastUser,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.Plus, null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.prompt_page_message_template_add_last_user),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageTemplateNodeCard(
    node: MessageTemplateNode,
    modifier: Modifier = Modifier,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Lucide.GripHorizontal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = getNodeDisplayName(node),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = getNodeTypeLabel(node),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (node is MessageTemplateNode.PromptNode && node.content.isNotBlank()) {
                    Text(
                        text = node.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Switch(
                checked = node.enabled,
                onCheckedChange = onToggleEnabled
            )
            IconButton(onClick = onEdit) {
                Icon(Lucide.Settings2, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Lucide.Trash2, stringResource(R.string.prompt_page_delete))
            }
        }
    }
}

@Composable
private fun MessageTemplateNodeEditSheet(
    node: MessageTemplateNode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (MessageTemplateNode) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(Lucide.ChevronDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_message_template_edit_node),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = node.enabled,
                            onCheckedChange = { enabled ->
                                val nextNode = when (node) {
                                    is MessageTemplateNode.PromptNode -> node.copy(enabled = enabled)
                                    is MessageTemplateNode.HistoryNode -> node.copy(enabled = enabled)
                                    is MessageTemplateNode.LastUserMessageNode -> node.copy(enabled = enabled)
                                }
                                onEdit(nextNode)
                            }
                        )
                    }
                )

                when (node) {
                    is MessageTemplateNode.PromptNode -> {
                        OutlinedTextField(
                            value = node.name,
                            onValueChange = { onEdit(node.copy(name = it)) },
                            label = { Text(stringResource(R.string.prompt_page_message_template_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        MessageTemplateRoleSelector(
                            label = stringResource(R.string.prompt_page_message_template_role),
                            selected = node.role,
                            onSelect = { onEdit(node.copy(role = it)) }
                        )
                        OutlinedTextField(
                            value = node.content,
                            onValueChange = { onEdit(node.copy(content = it)) },
                            label = { Text(stringResource(R.string.prompt_page_message_template_content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            minLines = 6
                        )
                    }

                    is MessageTemplateNode.HistoryNode -> {
                        Text(
                            text = stringResource(R.string.prompt_page_message_template_history_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        MessageTemplateRoleMappingEditor(
                            roleMapping = node.roleMapping,
                            onUpdate = { onEdit(node.copy(roleMapping = it)) }
                        )
                    }

                    is MessageTemplateNode.LastUserMessageNode -> {
                        Text(
                            text = stringResource(R.string.prompt_page_message_template_last_user_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        MessageTemplateRoleMappingEditor(
                            roleMapping = node.roleMapping,
                            onUpdate = { onEdit(node.copy(roleMapping = it)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
private fun MessageTemplateRoleMappingEditor(
    roleMapping: TemplateRoleMapping,
    onUpdate: (TemplateRoleMapping) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MessageTemplateRoleSelector(
            label = stringResource(R.string.prompt_page_message_template_mapping_system),
            selected = roleMapping.system,
            onSelect = { onUpdate(roleMapping.copy(system = it)) }
        )
        MessageTemplateRoleSelector(
            label = stringResource(R.string.prompt_page_message_template_mapping_user),
            selected = roleMapping.user,
            onSelect = { onUpdate(roleMapping.copy(user = it)) }
        )
        MessageTemplateRoleSelector(
            label = stringResource(R.string.prompt_page_message_template_mapping_assistant),
            selected = roleMapping.assistant,
            onSelect = { onUpdate(roleMapping.copy(assistant = it)) }
        )
    }
}

@Composable
private fun MessageTemplateRoleSelector(
    label: String,
    selected: MessageRole,
    onSelect: (MessageRole) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Select(
            options = listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
            selectedOption = selected,
            onOptionSelected = onSelect,
            optionToString = { messageTemplateRoleLabel(it) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun messageTemplateRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> stringResource(R.string.prompt_page_message_template_mapping_system)
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}

@Composable
private fun getNodeTypeLabel(node: MessageTemplateNode): String = when (node) {
    is MessageTemplateNode.PromptNode -> stringResource(R.string.prompt_page_message_template_node_prompt)
    is MessageTemplateNode.HistoryNode -> stringResource(R.string.prompt_page_message_template_node_history)
    is MessageTemplateNode.LastUserMessageNode -> stringResource(R.string.prompt_page_message_template_node_last_user)
}

@Composable
private fun getNodeDisplayName(node: MessageTemplateNode): String = when (node) {
    is MessageTemplateNode.PromptNode -> {
        if (node.name.isBlank()) stringResource(R.string.prompt_page_message_template_prompt_default_name) else node.name
    }

    is MessageTemplateNode.HistoryNode -> stringResource(R.string.prompt_page_message_template_history_title)
    is MessageTemplateNode.LastUserMessageNode -> stringResource(R.string.prompt_page_message_template_last_user_title)
}
