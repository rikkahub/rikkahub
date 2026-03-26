package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.GripHorizontal
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.hasExplicitPromptOrder
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.ui.theme.CustomColors
import sh.calvin.reorderable.ReorderableColumn

private val stPromptGenerationTypes = listOf("normal", "continue", "quiet", "impersonate")

private data class StPromptEditorState(
    val originalIdentifier: String,
    val prompt: SillyTavernPromptItem,
    val enabled: Boolean,
)

@Composable
fun SillyTavernPresetEditorCard(
    template: SillyTavernPromptTemplate,
    onUpdate: (SillyTavernPromptTemplate) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "SillyTavern 预设编辑",
    description: String = "这里按 ST 的模板字段、prompt order 和 prompt definitions 组织。",
) {
    var formatExpanded by rememberSaveable { mutableStateOf(false) }
    var runtimeExpanded by rememberSaveable { mutableStateOf(false) }
    var promptExpanded by rememberSaveable { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current
    val editorTemplate = remember(template) {
        normalizeSillyTavernTemplateForEditor(template)
    }
    val promptOrder = editorTemplate.resolvePromptOrder()
    val enabledPromptCount = promptOrder.count { it.enabled }
    val missingDefaultPrompts = remember(editorTemplate) {
        defaultSillyTavernPromptTemplate().prompts.filter { prompt ->
            editorTemplate.findPrompt(prompt.identifier) == null
        }
    }

    fun updateTemplate(transform: (SillyTavernPromptTemplate) -> SillyTavernPromptTemplate) {
        onUpdate(transform(normalizeSillyTavernTemplateForEditor(template)))
    }

    val promptEditState = useEditState<StPromptEditorState> { edited ->
        updateTemplate { current ->
            applyStPromptEditorState(current, edited)
        }
    }

    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text("共 ${promptOrder.size} 项")
                    }
                    Tag(type = TagType.SUCCESS) {
                        Text("启用 $enabledPromptCount 项")
                    }
                    Tag(type = TagType.WARNING) {
                        Text("长按把手拖动")
                    }
                }
            }

            StEditorSectionCard(
                title = "模板与格式",
                description = "预设名称与 Scenario / Personality / World Info 包装格式。",
                expanded = formatExpanded,
                onExpandedChange = { formatExpanded = it }
            ) {
                OutlinedTextField(
                    value = editorTemplate.sourceName,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(sourceName = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("预设名称") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = editorTemplate.scenarioFormat,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(scenarioFormat = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Scenario 格式") },
                )

                OutlinedTextField(
                    value = editorTemplate.personalityFormat,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(personalityFormat = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Personality 格式") },
                )

                OutlinedTextField(
                    value = editorTemplate.wiFormat,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(wiFormat = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("World Info 格式") },
                )
            }

            StEditorSectionCard(
                title = "运行时选项",
                description = "系统提示词、Continue 行为和默认注入文案。",
                expanded = runtimeExpanded,
                onExpandedChange = { runtimeExpanded = it }
            ) {
                StBooleanSettingRow(
                    title = "复用系统提示词",
                    checked = editorTemplate.useSystemPrompt,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(useSystemPrompt = checked)
                        }
                    }
                )

                StBooleanSettingRow(
                    title = "压缩连续 system 消息",
                    checked = editorTemplate.squashSystemMessages,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(squashSystemMessages = checked)
                        }
                    }
                )

                StBooleanSettingRow(
                    title = "Continue 时使用 assistant prefill",
                    checked = editorTemplate.continuePrefill,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(continuePrefill = checked)
                        }
                    }
                )

                OutlinedTextField(
                    value = editorTemplate.newChatPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(newChatPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新聊天提示") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.newGroupChatPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(newGroupChatPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新群聊提示") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.newExampleChatPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(newExampleChatPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("示例聊天提示") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.continueNudgePrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(continueNudgePrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Continue Nudge Prompt") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.groupNudgePrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(groupNudgePrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Group Nudge Prompt") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.impersonationPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(impersonationPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Impersonation Prompt") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.assistantPrefill,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(assistantPrefill = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Assistant Prefill") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.assistantImpersonation,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(assistantImpersonation = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Assistant Impersonation") },
                    minLines = 2,
                )

                OutlinedTextField(
                    value = editorTemplate.continuePostfix,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(continuePostfix = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Continue Postfix") },
                )

                OutlinedTextField(
                    value = editorTemplate.sendIfEmpty,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(sendIfEmpty = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Send If Empty") },
                    minLines = 2,
                )
            }

            StEditorSectionCard(
                title = "Prompt 顺序与定义",
                description = "长按左侧把手拖动排序，切换开关启停，点设置编辑详情。",
                expanded = promptExpanded,
                onExpandedChange = { promptExpanded = it }
            ) {
                if (missingDefaultPrompts.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "快速补回常见段落",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            missingDefaultPrompts.fastForEach { prompt ->
                                Tag(
                                    type = TagType.INFO,
                                    onClick = {
                                        updateTemplate { current ->
                                            appendStPromptDefinition(current, prompt, enabled = true)
                                        }
                                    }
                                ) {
                                    Text(stPromptDisplayName(prompt))
                                }
                            }
                        }
                    }
                }

                if (promptOrder.isEmpty()) {
                    Text(
                        text = "当前还没有提示项，先添加一个自定义项或补回默认段落。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ReorderableColumn(
                        promptOrder,
                        { from: Int, to: Int ->
                            updateTemplate { current ->
                                moveStPromptOrder(current, from, to)
                            }
                        },
                        Modifier.fillMaxWidth(),
                        Arrangement.spacedBy(8.dp),
                        Alignment.Start,
                        {}
                    ) { _, orderItem, isDragging ->
                        val prompt = editorTemplate.findPrompt(orderItem.identifier)
                            ?: defaultStPromptDefinition(orderItem.identifier)

                        ReorderableItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 0.98f
                                        scaleY = 0.98f
                                    }
                                }
                        ) {
                            StPromptListItem(
                                prompt = prompt,
                                orderItem = orderItem,
                                isDragging = isDragging,
                                dragHandleModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                ),
                                onEnabledChange = { enabled ->
                                    updateTemplate { current ->
                                        updateStPromptOrderEnabled(current, orderItem.identifier, enabled)
                                    }
                                },
                                onEdit = {
                                    promptEditState.open(
                                        StPromptEditorState(
                                            originalIdentifier = orderItem.identifier,
                                            prompt = prompt.copy(identifier = orderItem.identifier),
                                            enabled = orderItem.enabled,
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        updateTemplate { current ->
                            appendStPromptDefinition(
                                template = current,
                                prompt = buildCustomStPrompt(current),
                                enabled = true,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.Plus, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加自定义提示项")
                }
            }
        }
    }

    if (promptEditState.isEditing) {
        promptEditState.currentState?.let { editorState ->
            StPromptEditSheet(
                state = editorState,
                onDismiss = { promptEditState.dismiss() },
                onConfirm = { promptEditState.confirm() },
                onDelete = {
                    promptEditState.dismiss()
                    updateTemplate { current ->
                        removeStPromptDefinition(current, editorState.originalIdentifier)
                    }
                },
                onEdit = { promptEditState.currentState = it }
            )
        }
    }
}

@Composable
private fun StEditorSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onExpandedChange(!expanded) }
                ) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null
                    )
                }
            }

            if (!expanded) {
                return@Column
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun StBooleanSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun StPromptListItem(
    prompt: SillyTavernPromptItem,
    orderItem: SillyTavernPromptOrderItem,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Lucide.GripHorizontal,
                contentDescription = null,
                modifier = dragHandleModifier,
                tint = if (isDragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stPromptDisplayName(prompt),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stPromptSummary(prompt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stPromptPreview(prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (prompt.marker) {
                        Tag(type = TagType.WARNING) {
                            Text("Marker")
                        }
                    }
                    if (prompt.systemPrompt) {
                        Tag(type = TagType.INFO) {
                            Text("System Prompt")
                        }
                    }
                    if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
                        Tag(type = TagType.WARNING) {
                            Text("Absolute")
                        }
                    }
                    if (prompt.forbidOverrides) {
                        Tag(type = TagType.WARNING) {
                            Text("禁止覆盖")
                        }
                    }
                    if (prompt.injectionTriggers.isNotEmpty()) {
                        Tag(type = TagType.SUCCESS) {
                            Text(prompt.injectionTriggers.joinToString(" / ") { stPromptGenerationTypeLabel(it) })
                        }
                    }
                }
            }

            Switch(
                checked = orderItem.enabled,
                onCheckedChange = onEnabledChange
            )
            IconButton(onClick = onEdit) {
                Icon(Lucide.Settings2, "编辑提示项")
            }
        }
    }
}

@Composable
private fun StPromptEditSheet(
    state: StPromptEditorState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (StPromptEditorState) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var depthText by remember(state.originalIdentifier) {
        mutableStateOf(state.prompt.injectionDepth.toString())
    }
    var orderText by remember(state.originalIdentifier) {
        mutableStateOf(state.prompt.injectionOrder.toString())
    }

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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stPromptDisplayName(state.prompt),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "编辑提示项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FormItem(
                    label = { Text("已启用") },
                    description = {
                        Text("关闭后会保留定义，但不会参与 prompt order。")
                    },
                    tail = {
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { enabled ->
                                onEdit(state.copy(enabled = enabled))
                            }
                        )
                    }
                )

                OutlinedTextField(
                    value = state.prompt.identifier,
                    onValueChange = { value ->
                        onEdit(
                            state.copy(
                                prompt = state.prompt.copy(identifier = value)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Identifier") },
                    singleLine = true,
                )

                Text(
                    text = "Identifier 留空或与现有项重复时，会保留原值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = state.prompt.name,
                    onValueChange = { value ->
                        onEdit(
                            state.copy(
                                prompt = state.prompt.copy(name = value)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("显示名称") },
                    singleLine = true,
                )

                Select(
                    options = MessageRole.entries.toList(),
                    selectedOption = state.prompt.role,
                    onOptionSelected = { role ->
                        onEdit(
                            state.copy(
                                prompt = state.prompt.copy(role = role)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { stRoleLabel(it) }
                )

                Select(
                    options = StPromptInjectionPosition.entries.toList(),
                    selectedOption = state.prompt.injectionPosition,
                    onOptionSelected = { position ->
                        onEdit(
                            state.copy(
                                prompt = state.prompt.copy(injectionPosition = position)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { stInjectionPositionLabel(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = depthText,
                        onValueChange = { value ->
                            depthText = value
                            value.toIntOrNull()?.let { depth ->
                                onEdit(
                                    state.copy(
                                        prompt = state.prompt.copy(injectionDepth = depth)
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Depth") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = orderText,
                        onValueChange = { value ->
                            orderText = value
                            value.toIntOrNull()?.let { order ->
                                onEdit(
                                    state.copy(
                                        prompt = state.prompt.copy(injectionOrder = order)
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Order") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "触发类型",
                        style = MaterialTheme.typography.labelMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        stPromptGenerationTypes.fastForEach { trigger ->
                            val selected = trigger in state.prompt.injectionTriggers
                            Tag(
                                type = if (selected) TagType.INFO else TagType.DEFAULT,
                                onClick = {
                                    val updatedTriggers = if (selected) {
                                        state.prompt.injectionTriggers - trigger
                                    } else {
                                        (state.prompt.injectionTriggers + trigger).distinct()
                                    }
                                    onEdit(
                                        state.copy(
                                            prompt = state.prompt.copy(injectionTriggers = updatedTriggers)
                                        )
                                    )
                                }
                            ) {
                                Text(stPromptGenerationTypeLabel(trigger))
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StCheckboxField(
                        label = "System Prompt",
                        checked = state.prompt.systemPrompt,
                        onCheckedChange = { checked ->
                            onEdit(
                                state.copy(
                                    prompt = state.prompt.copy(systemPrompt = checked)
                                )
                            )
                        }
                    )
                    StCheckboxField(
                        label = "Marker",
                        checked = state.prompt.marker,
                        onCheckedChange = { checked ->
                            onEdit(
                                state.copy(
                                    prompt = state.prompt.copy(marker = checked)
                                )
                            )
                        }
                    )
                    StCheckboxField(
                        label = "禁止覆盖",
                        checked = state.prompt.forbidOverrides,
                        onCheckedChange = { checked ->
                            onEdit(
                                state.copy(
                                    prompt = state.prompt.copy(forbidOverrides = checked)
                                )
                            )
                        }
                    )
                }

                OutlinedTextField(
                    value = state.prompt.content,
                    onValueChange = { value ->
                        onEdit(
                            state.copy(
                                prompt = state.prompt.copy(content = value)
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    label = { Text("Prompt 内容") },
                    minLines = 6,
                )
            }

            TextButton(
                onClick = onDelete
            ) {
                Icon(Lucide.Trash2, null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("删除该项")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun StCheckboxField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun normalizeSillyTavernTemplateForEditor(template: SillyTavernPromptTemplate): SillyTavernPromptTemplate {
    val promptOrder = buildEditorPromptOrder(template)
    val prompts = template.prompts
        .filter { it.identifier.isNotBlank() }
        .distinctBy { it.identifier }
        .toMutableList()

    promptOrder.fastForEach { orderItem ->
        if (prompts.none { it.identifier == orderItem.identifier }) {
            prompts += defaultStPromptDefinition(orderItem.identifier)
        }
    }

    return template.copy(prompts = prompts).withPromptOrder(promptOrder)
}

private fun buildEditorPromptOrder(template: SillyTavernPromptTemplate): List<SillyTavernPromptOrderItem> {
    val explicitOrder = template.hasExplicitPromptOrder()
    val order = template.resolvePromptOrder().toMutableList()
    template.prompts
        .filter { it.identifier.isNotBlank() }
        .forEach { prompt ->
            if (order.none { it.identifier == prompt.identifier }) {
                order += SillyTavernPromptOrderItem(
                    identifier = prompt.identifier,
                    enabled = if (explicitOrder) false else prompt.enabled,
                )
            }
        }
    return order.distinctBy { it.identifier }
}

private fun appendStPromptDefinition(
    template: SillyTavernPromptTemplate,
    prompt: SillyTavernPromptItem,
    enabled: Boolean,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val prompts = normalized.prompts.filterNot { it.identifier == prompt.identifier } + prompt
    val promptOrder = normalized.resolvePromptOrder().filterNot { it.identifier == prompt.identifier } +
        SillyTavernPromptOrderItem(prompt.identifier, enabled)
    return normalized.copy(prompts = prompts).withPromptOrder(promptOrder)
}

private fun removeStPromptDefinition(
    template: SillyTavernPromptTemplate,
    identifier: String,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    return normalized.copy(
        prompts = normalized.prompts.filterNot { it.identifier == identifier }
    ).withPromptOrder(
        normalized.resolvePromptOrder().filterNot { it.identifier == identifier }
    )
}

private fun updateStPrompt(
    template: SillyTavernPromptTemplate,
    identifier: String,
    transform: (SillyTavernPromptItem) -> SillyTavernPromptItem,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val prompts = normalized.prompts.map { prompt ->
        if (prompt.identifier == identifier) {
            transform(prompt)
        } else {
            prompt
        }
    }
    return normalized.copy(prompts = prompts)
}

private fun renameStPromptIdentifier(
    template: SillyTavernPromptTemplate,
    oldIdentifier: String,
    newIdentifier: String,
): SillyTavernPromptTemplate {
    val normalizedIdentifier = newIdentifier.trim()
    if (normalizedIdentifier.isBlank() || normalizedIdentifier == oldIdentifier) {
        return template
    }
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    if (normalized.prompts.any { it.identifier == normalizedIdentifier && it.identifier != oldIdentifier }) {
        return normalized
    }
    val prompts = normalized.prompts.map { prompt ->
        if (prompt.identifier == oldIdentifier) {
            prompt.copy(identifier = normalizedIdentifier)
        } else {
            prompt
        }
    }
    val promptOrder = normalized.resolvePromptOrder().map { orderItem ->
        if (orderItem.identifier == oldIdentifier) {
            orderItem.copy(identifier = normalizedIdentifier)
        } else {
            orderItem
        }
    }
    return normalized.copy(prompts = prompts).withPromptOrder(promptOrder)
}

private fun updateStPromptOrderEnabled(
    template: SillyTavernPromptTemplate,
    identifier: String,
    enabled: Boolean,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    return normalized.withPromptOrder(
        normalized.resolvePromptOrder().map { orderItem ->
            if (orderItem.identifier == identifier) {
                orderItem.copy(enabled = enabled)
            } else {
                orderItem
            }
        }
    )
}

private fun moveStPromptOrder(
    template: SillyTavernPromptTemplate,
    fromIndex: Int,
    toIndex: Int,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val order = normalized.resolvePromptOrder().toMutableList()
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
        return normalized
    }
    val item = order.removeAt(fromIndex)
    order.add(toIndex, item)
    return normalized.withPromptOrder(order)
}

private fun applyStPromptEditorState(
    template: SillyTavernPromptTemplate,
    state: StPromptEditorState,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val requestedIdentifier = state.prompt.identifier.trim()
    val hasConflict = normalized.prompts.any { prompt ->
        prompt.identifier == requestedIdentifier && prompt.identifier != state.originalIdentifier
    }
    val finalIdentifier = when {
        requestedIdentifier.isBlank() -> state.originalIdentifier
        hasConflict -> state.originalIdentifier
        else -> requestedIdentifier
    }

    val renamed = if (finalIdentifier != state.originalIdentifier) {
        renameStPromptIdentifier(normalized, state.originalIdentifier, finalIdentifier)
    } else {
        normalized
    }

    return updateStPromptOrderEnabled(
        updateStPrompt(renamed, finalIdentifier) {
            state.prompt.copy(identifier = finalIdentifier)
        },
        identifier = finalIdentifier,
        enabled = state.enabled
    )
}

private fun buildCustomStPrompt(template: SillyTavernPromptTemplate): SillyTavernPromptItem {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    var index = 1
    var identifier = "customPrompt$index"
    while (normalized.findPrompt(identifier) != null) {
        index++
        identifier = "customPrompt$index"
    }
    return SillyTavernPromptItem(
        identifier = identifier,
        name = "Custom Prompt $index",
        role = MessageRole.SYSTEM,
        systemPrompt = true,
    )
}

private fun defaultStPromptDefinition(identifier: String): SillyTavernPromptItem {
    return defaultSillyTavernPromptTemplate().findPrompt(identifier)
        ?: SillyTavernPromptItem(
            identifier = identifier,
            name = identifier.replaceFirstChar { it.uppercase() },
            role = MessageRole.SYSTEM,
            systemPrompt = true,
        )
}

private fun stPromptDisplayName(prompt: SillyTavernPromptItem): String {
    return prompt.name.ifBlank { defaultStPromptDefinition(prompt.identifier).name.ifBlank { prompt.identifier } }
}

private fun stPromptSummary(prompt: SillyTavernPromptItem): String {
    return buildList {
        add(prompt.identifier)
        add(stRoleLabel(prompt.role))
        if (!prompt.marker) {
            add("Depth ${prompt.injectionDepth}")
            add("Order ${prompt.injectionOrder}")
        }
    }.joinToString(" · ")
}

private fun stPromptPreview(prompt: SillyTavernPromptItem): String {
    return when {
        prompt.content.isNotBlank() -> prompt.content
        prompt.marker -> "Marker 节点，不直接写入文本内容。"
        else -> "点击进入详情，填写或修改这段提示词内容。"
    }
}

private fun stRoleLabel(role: MessageRole): String {
    return when (role) {
        MessageRole.SYSTEM -> "System"
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        else -> role.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun stInjectionPositionLabel(position: StPromptInjectionPosition): String {
    return when (position) {
        StPromptInjectionPosition.RELATIVE -> "Relative"
        StPromptInjectionPosition.ABSOLUTE -> "Absolute"
    }
}

private fun stPromptGenerationTypeLabel(trigger: String): String {
    return when (trigger) {
        "normal" -> "Normal"
        "continue" -> "Continue"
        "quiet" -> "Quiet"
        "impersonate" -> "Impersonate"
        else -> trigger
    }
}
