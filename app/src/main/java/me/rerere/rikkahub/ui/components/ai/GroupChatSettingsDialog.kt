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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.UserAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatParticipant
import me.rerere.rikkahub.data.model.SpeakingOrder
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun GroupChatSettingsDialog(
    settings: Settings,
    currentAssistant: Assistant,
    groupChatConfig: GroupChatConfig,
    onDismiss: () -> Unit,
    onUpdateConfig: (GroupChatConfig) -> Unit,
) {
    var showAddParticipantSheet by remember { mutableStateOf(false) }
    var selectedSpeakingOrder by remember { mutableStateOf(groupChatConfig.speakingOrder) }
    var customSystemPrompt by remember { mutableStateOf(groupChatConfig.groupSystemPrompt ?: "") }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val navController = LocalNavController.current

    var participants by remember(groupChatConfig) {
        mutableStateOf(groupChatConfig.participants.sortedBy { it.order })
    }

    val reorderableState = rememberReorderableLazyListState { from, to ->
        participants = participants.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }.mapIndexed { index, participant ->
            participant.copy(order = index)
        }
    }

    fun getAssistantById(id: Uuid): Assistant? =
        settings.assistants.find { it.id == id }

    fun getParticipantLabel(participant: GroupChatParticipant): String {
        val assistant = getAssistantById(participant.assistantId)
        return participant.displayName
            ?: assistant?.name?.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
            ?: "Participant ${participant.order + 1}"
    }

    fun saveChanges() {
        val enabledCount = participants.count { it.enabled }
        if (enabledCount < 2) {
            toaster.show("群聊至少需要 2 个参与者", type = com.dokar.sonner.ToastType.Warning)
            return
        }

        val newConfig = GroupChatConfig(
            isGroupChat = enabledCount >= 2,
            participants = participants,
            speakingOrder = selectedSpeakingOrder,
            groupSystemPrompt = customSystemPrompt.takeIf { it.isNotBlank() },
        )
        onUpdateConfig(newConfig)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_chat_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxHeight(0.8f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.group_chat_participants),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { showAddParticipantSheet = true }
                    ) {
                        Icon(HugeIcons.UserAdd01, "Add Participant")
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                ) {
                    if (participants.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                HugeIcons.Add01,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.group_chat_no_participants),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = reorderableState.lazyListState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(
                                items = participants,
                                key = { it.id }
                            ) { participant ->
                                ReorderableItem(
                                    state = reorderableState,
                                    key = participant.id
                                ) { isDragging ->
                                    val assistant = getAssistantById(participant.assistantId)
                                    val isCurrentAssistant = participant.assistantId == currentAssistant.id

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (isDragging) Modifier
                                                else Modifier.animateItem()
                                            ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isDragging) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        ),
                                        elevation = CardDefaults.cardElevation(
                                            if (isDragging) 8.dp else 0.dp
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.DragDropVertical,
                                                contentDescription = "Reorder",
                                                modifier = Modifier.longPressDraggableHandle(),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            UIAvatar(
                                                name = assistant?.name ?: "?",
                                                value = assistant?.avatar,
                                                modifier = Modifier.size(36.dp),
                                                onClick = {
                                                    assistant?.let { ass ->
                                                        navController.navigate(
                                                            me.rerere.rikkahub.Screen.AssistantDetail(
                                                                ass.id.toString()
                                                            )
                                                        )
                                                    }
                                                }
                                            )

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = getParticipantLabel(participant),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (isCurrentAssistant) {
                                                    Text(
                                                        text = stringResource(R.string.group_chat_current_assistant),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }

                                            Checkbox(
                                                checked = participant.enabled,
                                                onCheckedChange = { enabled ->
                                                    participants = participants.map { p ->
                                                        if (p.id == participant.id) {
                                                            p.copy(enabled = enabled)
                                                        } else p
                                                    }
                                                }
                                            )

                                            if (participants.size > 1) {
                                                IconButton(
                                                    onClick = {
                                                        participants = participants.filter { p ->
                                                            p.id != participant.id
                                                        }.mapIndexed { index, p ->
                                                            p.copy(order = index)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        HugeIcons.Cancel01,
                                                        "Remove Participant",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_chat_speaking_order),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    SpeakingOrderSelector(
                        selectedOrder = selectedSpeakingOrder,
                        onSelect = { selectedSpeakingOrder = it }
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_chat_custom_prompt),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    OutlinedTextField(
                        value = customSystemPrompt,
                        onValueChange = { customSystemPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.group_chat_custom_prompt_placeholder))
                        },
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { saveChanges() }) {
                Text(stringResource(R.string.group_chat_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.group_chat_cancel))
            }
        }
    )

    if (showAddParticipantSheet) {
        AddParticipantSheet(
            settings = settings,
            currentAssistant = currentAssistant,
            existingParticipants = participants,
            onDismiss = { showAddParticipantSheet = false },
            onAddParticipant = { newParticipant ->
                val newOrder = if (participants.isEmpty()) 0 else participants.maxOf { it.order } + 1
                participants = participants + newParticipant.copy(order = newOrder)
                showAddParticipantSheet = false
            }
        )
    }
}

@Composable
private fun SpeakingOrderSelector(
    selectedOrder: SpeakingOrder,
    onSelect: (SpeakingOrder) -> Unit,
) {
    val orders = listOf(
        SpeakingOrder.Sequential to stringResource(R.string.group_chat_order_sequential),
        SpeakingOrder.Random to stringResource(R.string.group_chat_order_random),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        orders.forEach { (order, label) ->
            Surface(
                onClick = { onSelect(order) },
                modifier = Modifier.fillMaxWidth(),
                color = if (order == selectedOrder) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = order == selectedOrder,
                        onClick = { onSelect(order) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddParticipantSheet(
    settings: Settings,
    currentAssistant: Assistant,
    existingParticipants: List<GroupChatParticipant>,
    onDismiss: () -> Unit,
    onAddParticipant: (GroupChatParticipant) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    val existingAssistantIds = existingParticipants.map { it.assistantId }.toSet()

    val availableAssistants = settings.assistants.filter {
        it.id !in existingAssistantIds
    }

    var selectedModelId by remember { mutableStateOf<Uuid?>(null) }
    var customDisplayName by remember { mutableStateOf("") }
    var selectedAssistant by remember { mutableStateOf<Assistant?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.group_chat_add_participant),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = stringResource(R.string.group_chat_select_assistant),
                style = MaterialTheme.typography.titleMedium,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            ) {
                if (availableAssistants.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            HugeIcons.UserAdd01,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.group_chat_no_available_assistants),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            items = availableAssistants,
                            key = { it.id }
                        ) { assistant ->
                            val isSelected = selectedAssistant?.id == assistant.id
                            Card(
                                onClick = {
                                    selectedAssistant = assistant
                                    selectedModelId = null
                                    customDisplayName = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    UIAvatar(
                                        name = assistant.name,
                                        value = assistant.avatar,
                                        modifier = Modifier.size(40.dp),
                                    )

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = assistant.name.ifBlank {
                                                stringResource(R.string.assistant_page_default_assistant)
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (assistant.id == currentAssistant.id) {
                                            Text(
                                                text = stringResource(R.string.group_chat_current_assistant),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                sheetState.hide()
                                                navController.navigate(
                                                    me.rerere.rikkahub.Screen.AssistantDetail(
                                                        assistant.id.toString()
                                                    )
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = me.rerere.hugeicons.HugeIcons.stroke.PencilEdit01,
                                            contentDescription = "Edit Assistant",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedAssistant = assistant
                                            selectedModelId = null
                                            customDisplayName = ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            selectedAssistant?.let { assistant ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_chat_select_model),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    ModelSelector(
                        modelId = selectedModelId ?: assistant.chatModelId,
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onlyIcon = false,
                        allowClear = true,
                        onSelect = { model ->
                            selectedModelId = if (model.id == Uuid.random() && model.displayName.isEmpty()) {
                                null
                            } else {
                                model.id
                            }
                        }
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.group_chat_custom_name),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    OutlinedTextField(
                        value = customDisplayName,
                        onValueChange = { customDisplayName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.group_chat_custom_name_placeholder))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.group_chat_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val newParticipant = GroupChatParticipant(
                                assistantId = assistant.id,
                                modelId = selectedModelId,
                                displayName = customDisplayName.takeIf { it.isNotBlank() },
                                enabled = true,
                                order = 0,
                            )
                            onAddParticipant(newParticipant)
                        }
                    ) {
                        Text(stringResource(R.string.group_chat_add))
                    }
                }
            }
        }
    }
}
