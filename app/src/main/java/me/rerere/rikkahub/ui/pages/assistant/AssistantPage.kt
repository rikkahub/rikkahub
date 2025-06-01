package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.GripHorizontal
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            createState.open(Assistant())
                        }
                    ) {
                        Icon(Lucide.Plus, stringResource(R.string.assistant_page_add))
                    }
                }
            )
        }
    ) {
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val newAssistants = settings.assistants.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            vm.updateSettings(settings.copy(assistants = newAssistants))
        }
        val haptic = LocalHapticFeedback.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            items(settings.assistants, key = { assistant -> assistant.id }) { assistant ->
                ReorderableItem(
                    state = reorderableState,
                    key = assistant.id
                ) { isDragging ->
                    val memories by vm.getMemories(assistant).collectAsStateWithLifecycle(
                        initialValue = emptyList(),
                    )
                    AssistantItem(
                        assistant = assistant,
                        memories = memories,
                        onEdit = {
                            navController.navigate("assistant/${assistant.id}")
                        },
                        onDelete = {
                            vm.removeAssistant(assistant)
                        },
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        dragHandle = {
                            Icon(
                                imageVector = Lucide.GripHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    AssistantCreationSheet(createState)
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FormItem(
                        label = {
                            Text(stringResource(R.string.assistant_page_name))
                        },
                    ) {
                        OutlinedTextField(
                            value = assistant.name,
                            onValueChange = {
                                update(
                                    assistant.copy(
                                        name = it
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }
                    ) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    TextButton(
                        onClick = {
                            state.confirm()
                        }
                    ) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    memories: List<AssistantMemory>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium
                )

                Tag(
                    type = TagType.INFO
                ) {
                    Text(
                        stringResource(
                            R.string.assistant_page_temperature_value,
                            assistant.temperature.toFixed(1)
                        )
                    )
                }

                if (assistant.enableMemory) {
                    Tag(
                        type = TagType.SUCCESS
                    ) {
                        Text(stringResource(R.string.assistant_page_memory_count, memories.size))
                    }
                }

                Spacer(Modifier.weight(1f))

                dragHandle()
            }

            Text(
                text = buildAnnotatedString {
                    if (assistant.systemPrompt.isNotBlank()) {
                        // 变量替换为蓝色
                        // 正则匹配 {xxx}
                        val regex = "\\{[^}]+\\}".toRegex()
                        var lastIndex = 0
                        val input = assistant.systemPrompt
                        regex.findAll(input).forEach { matchResult ->
                            val start = matchResult.range.first
                            val end = matchResult.range.last + 1
                            // 普通文本
                            if (lastIndex < start) {
                                append(input.substring(lastIndex, start))
                            }
                            // 蓝色变量
                            withStyle(SpanStyle(color = MaterialTheme.extendColors.blue6)) { // 你可以自定义颜色
                                append(input.substring(start, end))
                            }
                            lastIndex = end
                        }
                        // 末尾剩余文本
                        if (lastIndex < input.length) {
                            append(input.substring(lastIndex))
                        }
                    } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(stringResource(R.string.assistant_page_no_system_prompt))
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))

                // Right
                TextButton(
                    onClick = {
                        showDeleteDialog = true
                    },
                    enabled = assistant.id !in DEFAULT_ASSISTANTS_IDS
                ) {
                    Icon(
                        Lucide.Trash2,
                        stringResource(R.string.assistant_page_delete),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp)
                    )
                    Text(stringResource(R.string.assistant_page_delete))
                }

                Button(
                    onClick = {
                        onEdit()
                    }
                ) {
                    Icon(
                        Lucide.Pencil,
                        stringResource(R.string.edit),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp)
                    )
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }
    if(showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_delete))
            },
            text = {
                Text(stringResource(R.string.assistant_page_delete_dialog_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}