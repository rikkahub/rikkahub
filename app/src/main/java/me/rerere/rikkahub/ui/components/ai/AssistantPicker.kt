package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Drama
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pen
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import kotlin.uuid.Uuid

@Composable
fun AssistantPicker(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    onClickSetting: () -> Unit,
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        icon = {
            Icon(Lucide.Drama, contentDescription = null)
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                UIAvatar(
                    name = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    value = state.currentAssistant.avatar,
                    onClick = onClickSetting
                )
            }
        },
        onClick = {
            showPicker = true
        },
        modifier = modifier,
        selected = false,
    )

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = state.currentAssistant,
            onAssistantSelected = { assistant ->
                showPicker = false
                state.setSelectAssistant(assistant)
            },
            onDismiss = {
                showPicker = false
            }
        )
    }
}

@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }

    // 根据选中的标签过滤助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds) {
        if (selectedTagIds.isEmpty()) {
            settings.assistants
        } else {
            settings.assistants.filter { assistant ->
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 标签过滤器
            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { tag -> tag.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 助手列表
            val navController = LocalNavController.current
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == currentAssistant.id
                    Card(
                        onClick = { onAssistantSelected(assistant) },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        AssistantItem(
                            assistant = assistant,
                            defaultAssistantName = defaultAssistantName,
                            onEdit = {
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                    navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    defaultAssistantName: String,
    onEdit: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = assistant.name.ifEmpty { defaultAssistantName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = assistant.systemPrompt.ifBlank { stringResource(R.string.assistant_page_no_system_prompt) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        },
        leadingContent = {
            UIAvatar(
                name = assistant.name.ifEmpty { defaultAssistantName },
                value = assistant.avatar,
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = {
            IconButton(
                onClick = {
                    onEdit()
                }
            ) {
                Icon(
                    imageVector = Lucide.Pen,
                    contentDescription = null
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
