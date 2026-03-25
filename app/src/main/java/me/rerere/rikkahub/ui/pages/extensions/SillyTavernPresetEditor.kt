package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.hasExplicitPromptOrder
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.pages.assistant.detail.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.ui.theme.CustomColors

private val stPromptGenerationTypes = listOf("normal", "continue", "quiet", "impersonate")

@Composable
fun SillyTavernPresetEditorCard(
    template: SillyTavernPromptTemplate,
    onUpdate: (SillyTavernPromptTemplate) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "SillyTavern 预设编辑",
    description: String = "这里按 ST 的模板字段、 prompt order 和 prompt definitions 组织。",
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedPromptIds by remember { mutableStateOf(setOf<String>()) }
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

    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "共 ${promptOrder.size} 项，已启用 $enabledPromptCount 项。$description",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded }
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

            Text(
                text = "模板与格式",
                style = MaterialTheme.typography.labelLarge
            )

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

            Text(
                text = "运行时选项",
                style = MaterialTheme.typography.labelLarge
            )

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

            Text(
                text = "Prompt 顺序与定义",
                style = MaterialTheme.typography.labelLarge
            )

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

            promptOrder.fastForEachIndexed { index, orderItem ->
                val prompt = editorTemplate.findPrompt(orderItem.identifier)
                    ?: defaultStPromptDefinition(orderItem.identifier)
                val expandedPrompt = orderItem.identifier in expandedPromptIds

                SillyTavernPromptCard(
                    prompt = prompt,
                    orderItem = orderItem,
                    expanded = expandedPrompt,
                    canMoveUp = index > 0,
                    canMoveDown = index < promptOrder.lastIndex,
                    onToggleExpanded = {
                        expandedPromptIds = if (expandedPrompt) {
                            expandedPromptIds - orderItem.identifier
                        } else {
                            expandedPromptIds + orderItem.identifier
                        }
                    },
                    onEnabledChange = { enabled ->
                        updateTemplate { current ->
                            updateStPromptOrderEnabled(current, orderItem.identifier, enabled)
                        }
                    },
                    onMoveUp = {
                        updateTemplate { current ->
                            moveStPromptOrder(current, orderItem.identifier, -1)
                        }
                    },
                    onMoveDown = {
                        updateTemplate { current ->
                            moveStPromptOrder(current, orderItem.identifier, 1)
                        }
                    },
                    onRenameIdentifier = { newIdentifier ->
                        updateTemplate { current ->
                            renameStPromptIdentifier(current, orderItem.identifier, newIdentifier)
                        }
                    },
                    onUpdatePrompt = { updatedPrompt ->
                        updateTemplate { current ->
                            updateStPrompt(current, orderItem.identifier) {
                                updatedPrompt
                            }
                        }
                    },
                    onDelete = {
                        expandedPromptIds -= orderItem.identifier
                        updateTemplate { current ->
                            removeStPromptDefinition(current, orderItem.identifier)
                        }
                    }
                )
            }

            Button(
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
                Icon(HugeIcons.Add01, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加自定义提示项")
            }
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
private fun SillyTavernPromptCard(
    prompt: SillyTavernPromptItem,
    orderItem: SillyTavernPromptOrderItem,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRenameIdentifier: (String) -> Unit,
    onUpdatePrompt: (SillyTavernPromptItem) -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stPromptDisplayName(prompt),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Tag { Text(prompt.identifier) }
                        Tag(type = TagType.INFO) { Text(stRoleLabel(prompt.role)) }
                        if (prompt.marker) {
                            Tag(type = TagType.WARNING) { Text("Marker") }
                        }
                        if (prompt.systemPrompt) {
                            Tag(type = TagType.INFO) { Text("System Prompt") }
                        }
                        if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
                            Tag(type = TagType.WARNING) { Text("Absolute") }
                        }
                        if (prompt.injectionTriggers.isNotEmpty()) {
                            Tag(type = TagType.SUCCESS) {
                                Text(prompt.injectionTriggers.joinToString(" / "))
                            }
                        }
                    }
                }

                Switch(
                    checked = orderItem.enabled,
                    onCheckedChange = onEnabledChange
                )

                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp
                ) {
                    Icon(HugeIcons.ArrowUp01, null)
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown
                ) {
                    Icon(HugeIcons.ArrowDown01, null)
                }

                IconButton(
                    onClick = onToggleExpanded
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

            OutlinedTextField(
                value = prompt.identifier,
                onValueChange = onRenameIdentifier,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Identifier") },
                singleLine = true,
            )

            OutlinedTextField(
                value = prompt.name,
                onValueChange = { value ->
                    onUpdatePrompt(prompt.copy(name = value))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                singleLine = true,
            )

            Select(
                options = MessageRole.entries.toList(),
                selectedOption = prompt.role,
                onOptionSelected = { role ->
                    onUpdatePrompt(prompt.copy(role = role))
                },
                modifier = Modifier.fillMaxWidth(),
                optionToString = { stRoleLabel(it) }
            )

            Select(
                options = StPromptInjectionPosition.entries.toList(),
                selectedOption = prompt.injectionPosition,
                onOptionSelected = { position ->
                    onUpdatePrompt(prompt.copy(injectionPosition = position))
                },
                modifier = Modifier.fillMaxWidth(),
                optionToString = { stInjectionPositionLabel(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = prompt.injectionDepth.toString(),
                    onValueChange = { value ->
                        val depth = value.toIntOrNull() ?: return@OutlinedTextField
                        onUpdatePrompt(prompt.copy(injectionDepth = depth))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Depth") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = prompt.injectionOrder.toString(),
                    onValueChange = { value ->
                        val order = value.toIntOrNull() ?: return@OutlinedTextField
                        onUpdatePrompt(prompt.copy(injectionOrder = order))
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
                        val selected = trigger in prompt.injectionTriggers
                        Tag(
                            type = if (selected) TagType.INFO else TagType.DEFAULT,
                            onClick = {
                                val updatedTriggers = if (selected) {
                                    prompt.injectionTriggers - trigger
                                } else {
                                    (prompt.injectionTriggers + trigger).distinct()
                                }
                                onUpdatePrompt(prompt.copy(injectionTriggers = updatedTriggers))
                            }
                        ) {
                            Text(stPromptGenerationTypeLabel(trigger))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StCheckboxField(
                    label = "System Prompt",
                    checked = prompt.systemPrompt,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(systemPrompt = checked))
                    }
                )
                StCheckboxField(
                    label = "Marker",
                    checked = prompt.marker,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(marker = checked))
                    }
                )
                StCheckboxField(
                    label = "禁止覆盖",
                    checked = prompt.forbidOverrides,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(forbidOverrides = checked))
                    }
                )
            }

            OutlinedTextField(
                value = prompt.content,
                onValueChange = { value ->
                    onUpdatePrompt(prompt.copy(content = value))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt 内容") },
                minLines = 4,
                maxLines = 10,
            )

            TextButton(
                onClick = onDelete
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(HugeIcons.Delete01, null)
                    Text("删除该项")
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
    identifier: String,
    delta: Int,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val order = normalized.resolvePromptOrder().toMutableList()
    val currentIndex = order.indexOfFirst { it.identifier == identifier }
    if (currentIndex < 0) return normalized
    val targetIndex = (currentIndex + delta).coerceIn(0, order.lastIndex)
    if (targetIndex == currentIndex) return normalized
    val item = order.removeAt(currentIndex)
    order.add(targetIndex, item)
    return normalized.withPromptOrder(order)
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
