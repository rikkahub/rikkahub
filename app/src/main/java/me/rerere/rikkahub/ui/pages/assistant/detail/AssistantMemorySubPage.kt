package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState

@Composable
fun AssistantMemorySettings(
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 1,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_memory))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_recent_chats))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory(0, ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Lucide.Plus,
                    contentDescription = null
                )
            }
        }

        memories.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = onDeleteMemory
                )
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = memory.content,
                modifier = Modifier.weight(1f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(Lucide.Pencil, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    Lucide.Trash2,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
