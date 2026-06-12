package me.rerere.rikkahub.ui.pages.chat.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.Task01

/**
 * The chat-side, read-write work-item board panel (SPEC.md M5, maintainer decision #4). It is fed
 * by [BoardViewModel.items] (a Room `Flow`, so subagent and tool edits appear live) and every
 * mutation — create, edit, status change, delete — calls a [BoardViewModel] method that routes
 * through the SAME [me.rerere.rikkahub.data.repository.TaskBoardRepository] the board tools use.
 * The panel itself performs NO validation: an illegal transition is simply not offered as a
 * gesture (see [actionsFor]) and the repository remains the single legality authority.
 *
 * Not a message part: the board is a panel, never a `UIMessagePart` (v1 prohibition).
 */
@Composable
fun BoardPanel(
    vm: BoardViewModel,
    modifier: Modifier = Modifier,
) {
    val items by vm.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BoardEditTarget?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = HugeIcons.Task01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Task board",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { editing = BoardEditTarget.New }) {
                Icon(imageVector = HugeIcons.Add01, contentDescription = "Add task")
            }
        }

        if (items.isEmpty()) {
            EmptyBoard()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id.toString() }) { item ->
                    BoardItemCard(
                        item = item,
                        onStatusChange = { target -> vm.changeStatus(item, target) },
                        onEdit = { editing = BoardEditTarget.Existing(item) },
                        onDelete = { vm.delete(item.id) },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        BoardEditDialog(
            target = target,
            onDismiss = { editing = null },
            onConfirm = { subject, description ->
                when (target) {
                    BoardEditTarget.New -> vm.create(subject, description)
                    is BoardEditTarget.Existing -> vm.edit(target.item.id, subject, description)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun EmptyBoard() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No tasks yet. Add one or let the assistant break work into items.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoardItemCard(
    item: WorkItem,
    onStatusChange: (WorkItemStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(item.status)
                Text(
                    text = item.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.status == WorkItemStatus.Completed) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.ownerName?.takeIf { it.isNotBlank() }?.let { owner ->
                Text(
                    text = "Claimed by $owner",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actionsFor(item.status).forEach { action ->
                    IconButton(onClick = { onStatusChange(action.target) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = HugeIcons.Edit01, contentDescription = "Edit task", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = HugeIcons.Delete01, contentDescription = "Delete task", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: WorkItemStatus) {
    val (label, color) = when (status) {
        WorkItemStatus.Pending -> "Pending" to MaterialTheme.colorScheme.secondaryContainer
        WorkItemStatus.InProgress -> "In progress" to MaterialTheme.colorScheme.tertiaryContainer
        WorkItemStatus.Completed -> "Done" to MaterialTheme.colorScheme.primaryContainer
        WorkItemStatus.Deleted -> "Deleted" to MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BoardEditDialog(
    target: BoardEditTarget,
    onDismiss: () -> Unit,
    onConfirm: (subject: String, description: String) -> Unit,
) {
    val existing = (target as? BoardEditTarget.Existing)?.item
    var subject by remember { mutableStateOf(existing?.subject ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New task" else "Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(subject, description) },
                enabled = subject.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** What the dialog is editing: a brand-new item or an existing one. */
private sealed interface BoardEditTarget {
    data object New : BoardEditTarget
    data class Existing(val item: WorkItem) : BoardEditTarget
}

/** One status-change affordance: its target status, icon, and accessibility label. */
private data class BoardStatusAction(
    val target: WorkItemStatus,
    val icon: ImageVector,
    val label: String,
)

/**
 * The status-change gestures legal from [status] — only canonical single-action transitions are
 * offered (no UI-only validation; the repository remains authoritative). Pending -> claim;
 * InProgress -> complete or release; Completed -> reopen. Deleted items never reach the panel.
 */
private fun actionsFor(status: WorkItemStatus): List<BoardStatusAction> = when (status) {
    WorkItemStatus.Pending -> listOf(
        BoardStatusAction(WorkItemStatus.InProgress, HugeIcons.PlayCircle, "Start task"),
    )
    WorkItemStatus.InProgress -> listOf(
        BoardStatusAction(WorkItemStatus.Completed, HugeIcons.CheckmarkCircle01, "Complete task"),
        BoardStatusAction(WorkItemStatus.Pending, HugeIcons.ArrowTurnBackward, "Release task"),
    )
    WorkItemStatus.Completed -> listOf(
        BoardStatusAction(WorkItemStatus.Pending, HugeIcons.ArrowTurnBackward, "Reopen task"),
    )
    WorkItemStatus.Deleted -> emptyList()
}
