package me.rerere.rikkahub.ui.pages.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Tooltip
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    // null 表示不在多选状态
    var selectedIds by remember { mutableStateOf<Set<Uuid>?>(null) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()

    // 列表变了（删除、切换助手）就去掉已经不在列表里的选中项
    LaunchedEffect(conversations) {
        val existing = conversations.mapTo(HashSet()) { it.id }
        selectedIds = selectedIds?.filterTo(HashSet()) { it in existing }
    }

    BackHandler(enabled = selectedIds != null) {
        selectedIds = null
    }

    fun toggleSelection(id: Uuid) {
        selectedIds = selectedIds?.let { if (id in it) it - id else it + id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val selected = selectedIds
                    if (selected != null) {
                        Text(stringResource(R.string.history_page_selected_count, selected.size))
                    } else {
                        Text(stringResource(R.string.history_page_title))
                    }
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedIds == null) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        if (conversations.isNotEmpty()) {
                            val selectLabel = stringResource(R.string.history_page_select)
                            Tooltip(tooltip = { Text(selectLabel) }) {
                                IconButton(onClick = { selectedIds = emptySet() }) {
                                    Icon(HugeIcons.CheckList, contentDescription = selectLabel)
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.history_page_delete_all)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            val selected = selectedIds
            if (selected != null) {
                HistorySelectionBar(
                    selectedCount = selected.size,
                    onExit = { selectedIds = null },
                    onDelete = { showDeleteSelectedDialog = true },
                )
            }
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val selected = selectedIds
                ConversationItem(
                    conversation = conversation,
                    selected = selected?.let { conversation.id in it },
                    onClick = {
                        if (selected != null) {
                            toggleSelection(conversation.id)
                        } else {
                            navigateToChatPage(navController, conversation.id)
                        }
                    },
                    onLongClick = {
                        if (selected != null) {
                            toggleSelection(conversation.id)
                        } else {
                            selectedIds = setOf(conversation.id)
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteSelectedDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteSelectedDialog = false
            selectedIds?.let { vm.deleteConversations(it) }
            selectedIds = null
        },
        onDismiss = { showDeleteSelectedDialog = false },
    ) {
        Text(stringResource(R.string.history_page_delete_selected_text, selectedIds?.size ?: 0))
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

/** 多选时的底部操作栏：退出多选，删除选中的对话。 */
@Composable
private fun HistorySelectionBar(
    selectedCount: Int,
    onExit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.largeIncreased,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val exitLabel = stringResource(R.string.history_page_exit_selection)
            Tooltip(tooltip = { Text(exitLabel) }) {
                IconButton(onClick = onExit) {
                    Icon(HugeIcons.Cancel01, contentDescription = exitLabel)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDelete,
                enabled = selectedCount > 0,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(HugeIcons.Delete01, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.delete))
            }
        }
    }
}

/**
 * [selected] 为 null 表示不在多选状态，此时右侧是置顶按钮；多选时换成勾选框，点整行切换选中。
 */
@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean?,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(25)
    Surface(
        tonalElevation = 2.dp,
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = shape,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.history_page_select),
            )
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                if (selected != null) {
                    Checkbox(checked = selected, onCheckedChange = null)
                } else {
                    IconButton(
                        onClick = onTogglePin
                    ) {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (conversation.isPinned) {
                                stringResource(R.string.history_page_unpin)
                            } else {
                                stringResource(R.string.history_page_pin)
                            }
                        )
                    }
                }
            }
        )
    }
}
