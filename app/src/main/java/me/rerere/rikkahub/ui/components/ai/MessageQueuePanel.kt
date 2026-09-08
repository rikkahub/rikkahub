package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState

@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onResume: () -> Unit,
) {
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    if (state.messages.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_message_queue"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (state.paused) R.string.chat_page_queue_paused_count
                            else R.string.chat_page_queue_pending_count,
                            state.messages.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
                    if (state.paused) {
                        TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(
                        state.messages,
                        key = { _, message -> message.id }) { index, message ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val attachmentLabels = listOf(
                                stringResource(R.string.chat_page_queue_image),
                                stringResource(R.string.chat_page_queue_file),
                                stringResource(R.string.chat_page_queue_audio),
                                stringResource(R.string.chat_page_queue_video),
                            )
                            Text(
                                text = "${index + 1}. " + message.parts.joinToString(" ") {
                                    when (it) {
                                        is UIMessagePart.Text -> it.text
                                        is UIMessagePart.Image -> attachmentLabels[0]
                                        is UIMessagePart.Document -> attachmentLabels[1]
                                        is UIMessagePart.Audio -> attachmentLabels[2]
                                        is UIMessagePart.Video -> attachmentLabels[3]
                                        else -> ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { editing = onBeginEdit(message.id) },
                            ) { Text(if (message.isEditing) stringResource(R.string.chat_page_queue_editing) else stringResource(R.string.edit)) }
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { onRemove(message.id) },
                            ) { Text(stringResource(R.string.chat_page_queue_remove)) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { message ->
        val input = remember(message.id) {
            ChatInputState().apply {
                editingMessage = message.id
                setContents(message.parts)
            }
        }
        val finishEdit by rememberUpdatedState(onFinishEdit)
        // Leaving the page or dismissing the dialog must release the queue item.
        DisposableEffect(message.id) {
            onDispose { finishEdit(message.id, null) }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_page_queue_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (input.messageContent.isNotEmpty()) MediaFileInputRow(input)
                    TextField(
                        state = input.textContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_queue_edit_text"),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 3,
                            maxHeightInLines = 8
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !input.isEmpty(),
                    onClick = {
                        onFinishEdit(message.id, input.getContents())
                        editing = null
                    },
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
