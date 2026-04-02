package me.rerere.rikkahub.ui.pages.extensions

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.hasExplicitPromptOrder
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import sh.calvin.reorderable.ReorderableColumn

private val stPromptGenerationTypes = listOf("normal", "continue", "quiet", "impersonate")
private val stNamesBehaviorOptions = listOf<Int?>(null, -1, 0, 1, 2)

private enum class StBuiltInPromptSourceKind {
    CHARACTER_DESCRIPTION,
    CHARACTER_PERSONALITY,
    SCENARIO,
    PERSONA_DESCRIPTION,
    WORLD_INFO,
    DIALOGUE_EXAMPLES,
    CHAT_HISTORY,
}

private data class StBuiltInPromptDefinition(
    val prompt: SillyTavernPromptItem,
    val sourceKind: StBuiltInPromptSourceKind? = null,
)

private val stBuiltInPromptDefinitions = run {
    val defaultTemplate = defaultSillyTavernPromptTemplate()
    listOf(
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("main")
                ?: SillyTavernPromptItem(
                    identifier = "main",
                    name = "Main Prompt",
                    role = MessageRole.SYSTEM,
                    content = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
                    systemPrompt = true,
                ),
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("worldInfoBefore")
                ?: SillyTavernPromptItem(
                    identifier = "worldInfoBefore",
                    name = "World Info (before)",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.WORLD_INFO,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("personaDescription")
                ?: SillyTavernPromptItem(
                    identifier = "personaDescription",
                    name = "Persona Description",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.PERSONA_DESCRIPTION,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("charDescription")
                ?: SillyTavernPromptItem(
                    identifier = "charDescription",
                    name = "Char Description",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.CHARACTER_DESCRIPTION,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("charPersonality")
                ?: SillyTavernPromptItem(
                    identifier = "charPersonality",
                    name = "Char Personality",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.CHARACTER_PERSONALITY,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("scenario")
                ?: SillyTavernPromptItem(
                    identifier = "scenario",
                    name = "Scenario",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.SCENARIO,
        ),
        StBuiltInPromptDefinition(
            prompt = SillyTavernPromptItem(
                identifier = "enhanceDefinitions",
                name = "Enhance Definitions",
                role = MessageRole.SYSTEM,
                content = "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute.",
                systemPrompt = true,
            ),
        ),
        StBuiltInPromptDefinition(
            prompt = SillyTavernPromptItem(
                identifier = "nsfw",
                name = "Auxiliary Prompt",
                role = MessageRole.SYSTEM,
                content = "",
                systemPrompt = true,
            ),
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("worldInfoAfter")
                ?: SillyTavernPromptItem(
                    identifier = "worldInfoAfter",
                    name = "World Info (after)",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.WORLD_INFO,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("dialogueExamples")
                ?: SillyTavernPromptItem(
                    identifier = "dialogueExamples",
                    name = "Chat Examples",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.DIALOGUE_EXAMPLES,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("chatHistory")
                ?: SillyTavernPromptItem(
                    identifier = "chatHistory",
                    name = "Chat History",
                    marker = true,
                ),
            sourceKind = StBuiltInPromptSourceKind.CHAT_HISTORY,
        ),
        StBuiltInPromptDefinition(
            prompt = defaultTemplate.findPrompt("jailbreak")
                ?: SillyTavernPromptItem(
                    identifier = "jailbreak",
                    name = "Post-History Instructions",
                    role = MessageRole.SYSTEM,
                    content = "",
                    systemPrompt = true,
                ),
        ),
    )
}
private val stBuiltInPromptDefinitionMap = stBuiltInPromptDefinitions.associateBy { it.prompt.identifier }

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
    title: String? = null,
    description: String? = null,
) {
    val resources = LocalResources.current
    var formatExpanded by rememberSaveable { mutableStateOf(false) }
    var runtimeExpanded by rememberSaveable { mutableStateOf(false) }
    var promptExpanded by rememberSaveable { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current
    val resolvedTitle = title ?: stringResource(R.string.prompt_page_st_preset_editor_title)
    val resolvedDescription = description ?: stringResource(R.string.prompt_page_st_preset_editor_desc)
    val editorTemplate = remember(template) {
        normalizeSillyTavernTemplateForEditor(template)
    }
    val promptOrder = editorTemplate.resolvePromptOrder()
    val enabledPromptCount = promptOrder.count { it.enabled }
    val missingDefaultPrompts = remember(editorTemplate) {
        stBuiltInPromptDefinitions.map { it.prompt }.filter { prompt ->
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
                    text = resolvedTitle,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = resolvedDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.prompt_page_st_preset_editor_count, promptOrder.size))
                    }
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.prompt_page_st_preset_editor_enabled_count, enabledPromptCount))
                    }
                    Tag(type = TagType.WARNING) {
                        Text(stringResource(R.string.prompt_page_st_preset_editor_drag_hint))
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "使用说明",
                            style = MaterialTheme.typography.titleSmall
                        )
                        StFeatureGuideRow(
                            title = "模板和格式",
                            body = "决定 Scenario / Personality / World Info 在最终提示词里怎么包裹。导入 ST 预设后，优先在这里确认格式外壳是否符合你的聊天风格。"
                        )
                        StFeatureGuideRow(
                            title = "运行时选项",
                            body = "控制新聊天、继续生成、群聊提醒、预填文本和名字行为。它们主要影响 ST 风格运行时，而不是单纯的系统提示词。"
                        )
                        StFeatureGuideRow(
                            title = "提示词顺序和定义",
                            body = "这里对应 ST 的 prompts + prompt_order。你可以决定哪些模块启用、以什么顺序注入，以及 normal / continue / impersonate 等场景分别使用什么内容。"
                        )
                    }
                }
            }

            StEditorSectionCard(
                title = stringResource(R.string.prompt_page_st_preset_editor_format_section_title),
                description = stringResource(R.string.prompt_page_st_preset_editor_format_section_desc),
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_name)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_scenario_format)) },
                )

                OutlinedTextField(
                    value = editorTemplate.personalityFormat,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(personalityFormat = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_personality_format)) },
                )

                OutlinedTextField(
                    value = editorTemplate.wiFormat,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(wiFormat = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_world_info_format)) },
                )
            }

            StEditorSectionCard(
                title = stringResource(R.string.prompt_page_st_preset_editor_runtime_section_title),
                description = stringResource(R.string.prompt_page_st_preset_editor_runtime_section_desc),
                expanded = runtimeExpanded,
                onExpandedChange = { runtimeExpanded = it }
            ) {
                StBooleanSettingRow(
                    title = stringResource(R.string.prompt_page_st_preset_editor_reuse_system_prompt),
                    checked = editorTemplate.useSystemPrompt,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(useSystemPrompt = checked)
                        }
                    }
                )

                StBooleanSettingRow(
                    title = stringResource(R.string.prompt_page_st_preset_editor_squash_system_messages),
                    checked = editorTemplate.squashSystemMessages,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(squashSystemMessages = checked)
                        }
                    }
                )

                StBooleanSettingRow(
                    title = stringResource(R.string.prompt_page_st_preset_editor_continue_prefill),
                    checked = editorTemplate.continuePrefill,
                    onCheckedChange = { checked ->
                        updateTemplate { current ->
                            current.copy(continuePrefill = checked)
                        }
                    }
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_editor_names_behavior),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Select(
                        options = stNamesBehaviorOptions,
                        selectedOption = editorTemplate.namesBehavior,
                        onOptionSelected = { value ->
                            updateTemplate { current ->
                                current.copy(namesBehavior = value)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        optionToString = { stringResource(stNamesBehaviorLabelRes(it)) }
                    )
                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_editor_names_behavior_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = editorTemplate.newChatPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(newChatPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_new_chat_prompt)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_new_group_chat_prompt)) },
                    minLines = 2,
                )
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_group_runtime_desc)
                )

                OutlinedTextField(
                    value = editorTemplate.newExampleChatPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(newExampleChatPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_example_chat_prompt)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_continue_nudge_prompt)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_group_nudge_prompt)) },
                    minLines = 2,
                )
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_group_runtime_desc)
                )

                OutlinedTextField(
                    value = editorTemplate.impersonationPrompt,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(impersonationPrompt = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_impersonation_prompt)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_assistant_prefill)) },
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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_assistant_impersonation)) },
                    minLines = 2,
                )
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_impersonation_runtime_desc)
                )

                OutlinedTextField(
                    value = editorTemplate.continuePostfix,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(continuePostfix = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_continue_postfix)) },
                )

                OutlinedTextField(
                    value = editorTemplate.sendIfEmpty,
                    onValueChange = { value ->
                        updateTemplate { current ->
                            current.copy(sendIfEmpty = value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_send_if_empty)) },
                    minLines = 2,
                )
            }

            StEditorSectionCard(
                title = stringResource(R.string.prompt_page_st_preset_editor_prompt_section_title),
                description = stringResource(R.string.prompt_page_st_preset_editor_prompt_section_desc),
                expanded = promptExpanded,
                onExpandedChange = { promptExpanded = it }
            ) {
                if (missingDefaultPrompts.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_st_preset_editor_restore_common),
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
                        text = stringResource(R.string.prompt_page_st_preset_editor_empty),
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
                                prompt = buildCustomStPrompt(current) { index ->
                                    resources.getString(
                                        R.string.prompt_page_st_preset_editor_custom_prompt_name,
                                        index,
                                    )
                                },
                                enabled = true,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.Plus, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.prompt_page_st_preset_editor_add_custom))
                }
            }
        }
    }

    if (promptEditState.isEditing) {
        promptEditState.currentState?.let { editorState ->
            StPromptEditSheet(
                state = editorState,
                usedIdentifiers = editorTemplate.prompts.map { it.identifier }.toSet(),
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
private fun StFeatureGuideRow(
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StEditorHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
                            Text(stringResource(R.string.prompt_page_st_preset_editor_marker_metadata_tag))
                        }
                    }
                    if (prompt.systemPrompt) {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.prompt_page_st_preset_editor_system_prompt_metadata_tag))
                        }
                    }
                    if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_st_preset_editor_absolute))
                        }
                    }
                    if (prompt.forbidOverrides) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_st_preset_editor_no_override))
                        }
                    }
                    if (prompt.injectionTriggers.isNotEmpty()) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stPromptTriggerLabels(prompt.injectionTriggers))
                        }
                    }
                }
            }

            Switch(
                checked = orderItem.enabled,
                onCheckedChange = onEnabledChange
            )
            IconButton(onClick = onEdit) {
                Icon(Lucide.Settings2, stringResource(R.string.prompt_page_st_preset_editor_edit_item))
            }
        }
    }
}

@Composable
private fun StPromptEditSheet(
    state: StPromptEditorState,
    usedIdentifiers: Set<String>,
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
    val builtInDefinition = remember(state.prompt.identifier, state.originalIdentifier) {
        stBuiltInPromptDefinition(state.originalIdentifier)?.let { originalDefinition ->
            stBuiltInPromptDefinition(state.prompt.identifier) ?: originalDefinition
        }
    }
    val builtInIdentifierOptions = remember(state.originalIdentifier, usedIdentifiers) {
        stBuiltInPromptDefinitions.filter { definition ->
            definition.prompt.identifier == state.originalIdentifier || definition.prompt.identifier !in usedIdentifiers
        }
    }
    val isSourceBackedPrompt = remember(state.prompt.identifier) {
        stIsSourceBackedPrompt(state.prompt.identifier)
    }
    val canEditPromptContent = remember(state.prompt.identifier, state.prompt.marker) {
        stCanEditPromptContent(state.prompt)
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
                    text = stringResource(R.string.prompt_page_st_preset_editor_edit_item),
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
                    label = { Text(stringResource(R.string.enabled)) },
                    description = {
                        Text(stringResource(R.string.prompt_page_st_preset_editor_enabled_desc))
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

                if (builtInDefinition != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_st_preset_editor_identifier),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Select(
                            options = builtInIdentifierOptions,
                            selectedOption = builtInDefinition,
                            onOptionSelected = { selected ->
                                onEdit(
                                    state.copy(
                                        prompt = stApplyBuiltInPromptDefinition(
                                            current = state.prompt,
                                            selected = selected,
                                        )
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            optionToString = { option ->
                                stPromptDisplayName(option.prompt)
                            }
                        )
                        StEditorHint(
                            text = stringResource(R.string.prompt_page_st_preset_editor_builtin_identifier_desc)
                        )
                    }
                } else {
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
                        label = { Text(stringResource(R.string.prompt_page_st_preset_editor_identifier)) },
                        singleLine = true,
                    )

                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_editor_identifier_duplicate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                    label = { Text(stringResource(R.string.prompt_page_st_preset_editor_display_name)) },
                    singleLine = true,
                )
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_display_name_desc)
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
                    optionToString = { stringResource(stRoleLabelRes(it)) }
                )
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_role_desc)
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
                    optionToString = { stringResource(stInjectionPositionLabelRes(it)) }
                )

                if (state.prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
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
                            label = { Text(stringResource(R.string.prompt_page_st_preset_editor_depth)) },
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
                            label = { Text(stringResource(R.string.prompt_page_st_preset_editor_order)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.prompt_page_st_preset_editor_trigger_types),
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
                                Text(stringResource(stPromptGenerationTypeLabelRes(trigger)))
                            }
                        }
                    }
                }
                StEditorHint(
                    text = stringResource(R.string.prompt_page_st_preset_editor_trigger_types_desc)
                )

                if (state.prompt.systemPrompt || state.prompt.marker) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_st_preset_editor_compat_metadata_title),
                            style = MaterialTheme.typography.labelMedium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.prompt.systemPrompt) {
                                Tag(type = TagType.INFO) {
                                    Text(stringResource(R.string.prompt_page_st_preset_editor_system_prompt_metadata_label))
                                }
                            }
                            if (state.prompt.marker) {
                                Tag(type = TagType.WARNING) {
                                    Text(stringResource(R.string.prompt_page_st_preset_editor_marker_metadata_label))
                                }
                            }
                        }
                        StEditorHint(
                            text = stringResource(R.string.prompt_page_st_preset_editor_compat_flags_desc)
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StCheckboxField(
                        label = stringResource(R.string.prompt_page_st_preset_editor_no_override),
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

                if (canEditPromptContent) {
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
                        label = { Text(stringResource(R.string.prompt_page_st_preset_editor_prompt_content)) },
                        minLines = 6,
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_st_preset_editor_prompt_content),
                            style = MaterialTheme.typography.labelMedium
                        )
                        StEditorHint(
                            text = when {
                                isSourceBackedPrompt -> stringResource(
                                    R.string.prompt_page_st_preset_editor_source_backed_desc,
                                    stringResource(stPromptSourceLabelRes(state.prompt.identifier))
                                )
                                state.prompt.marker -> stringResource(R.string.prompt_page_st_preset_editor_marker_preview)
                                else -> stringResource(R.string.prompt_page_st_preset_editor_empty_content_preview)
                            }
                        )
                    }
                }
            }

            TextButton(
                onClick = onDelete
            ) {
                Icon(Lucide.Trash2, null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.prompt_page_st_preset_editor_delete_item))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.save))
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

private fun buildCustomStPrompt(
    template: SillyTavernPromptTemplate,
    nameForIndex: (Int) -> String,
): SillyTavernPromptItem {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    var index = 1
    var identifier = "customPrompt$index"
    while (normalized.findPrompt(identifier) != null) {
        index++
        identifier = "customPrompt$index"
    }
    return SillyTavernPromptItem(
        identifier = identifier,
        name = nameForIndex(index),
        role = MessageRole.SYSTEM,
        systemPrompt = false,
    )
}

private fun defaultStPromptDefinition(identifier: String): SillyTavernPromptItem {
    return stBuiltInPromptDefinition(identifier)?.prompt
        ?: defaultSillyTavernPromptTemplate().findPrompt(identifier)
        ?: SillyTavernPromptItem(
            identifier = identifier,
            name = identifier.replaceFirstChar { it.uppercase() },
            role = MessageRole.SYSTEM,
            systemPrompt = false,
        )
}

private fun stPromptDisplayName(prompt: SillyTavernPromptItem): String {
    return prompt.name.ifBlank { defaultStPromptDefinition(prompt.identifier).name.ifBlank { prompt.identifier } }
}

@Composable
private fun stPromptSummary(prompt: SillyTavernPromptItem): String {
    return buildList {
        add(prompt.identifier)
        add(stringResource(stRoleLabelRes(prompt.role)))
        if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
            add(stringResource(R.string.prompt_page_st_preset_editor_depth_value, prompt.injectionDepth))
            add(stringResource(R.string.prompt_page_st_preset_editor_order_value, prompt.injectionOrder))
        }
    }.joinToString(" · ")
}

@Composable
private fun stPromptPreview(prompt: SillyTavernPromptItem): String {
    return when {
        stIsSourceBackedPrompt(prompt.identifier) -> stringResource(
            R.string.prompt_page_st_preset_editor_source_preview,
            stringResource(stPromptSourceLabelRes(prompt.identifier))
        )
        prompt.content.isNotBlank() -> prompt.content
        prompt.marker -> stringResource(R.string.prompt_page_st_preset_editor_marker_preview)
        else -> stringResource(R.string.prompt_page_st_preset_editor_empty_content_preview)
    }
}

@Composable
private fun stPromptTriggerLabels(triggers: List<String>): String {
    val labels = mutableListOf<String>()
    for (trigger in triggers) {
        labels += stringResource(stPromptGenerationTypeLabelRes(trigger))
    }
    return labels.joinToString(" / ")
}

private fun stBuiltInPromptDefinition(identifier: String): StBuiltInPromptDefinition? {
    return stBuiltInPromptDefinitionMap[identifier]
}

private fun stIsSourceBackedPrompt(identifier: String): Boolean {
    return stBuiltInPromptDefinition(identifier)?.sourceKind != null
}

private fun stCanEditPromptContent(prompt: SillyTavernPromptItem): Boolean {
    return !prompt.marker && !stIsSourceBackedPrompt(prompt.identifier)
}

private fun stApplyBuiltInPromptDefinition(
    current: SillyTavernPromptItem,
    selected: StBuiltInPromptDefinition,
): SillyTavernPromptItem {
    val currentDefaultName = stBuiltInPromptDefinition(current.identifier)?.prompt?.name.orEmpty()
    val resolvedName = when {
        current.name.isBlank() -> selected.prompt.name
        current.name == currentDefaultName -> selected.prompt.name
        else -> current.name
    }
    val resolvedContent = when {
        selected.prompt.marker || selected.sourceKind != null -> ""
        current.identifier == selected.prompt.identifier -> current.content
        current.content.isBlank() -> selected.prompt.content
        else -> current.content
    }
    val resolvedForbidOverrides = if (selected.prompt.identifier == "main" || selected.prompt.identifier == "jailbreak") {
        current.forbidOverrides
    } else {
        false
    }

    return current.copy(
        identifier = selected.prompt.identifier,
        name = resolvedName,
        content = resolvedContent,
        systemPrompt = selected.prompt.systemPrompt,
        marker = selected.prompt.marker,
        forbidOverrides = resolvedForbidOverrides,
    )
}

@StringRes
private fun stRoleLabelRes(role: MessageRole): Int {
    return when (role) {
        MessageRole.SYSTEM -> R.string.prompt_page_st_preset_editor_role_system
        MessageRole.USER -> R.string.prompt_page_st_preset_editor_role_user
        MessageRole.ASSISTANT -> R.string.prompt_page_st_preset_editor_role_assistant
        else -> R.string.prompt_page_st_preset_editor_role_system
    }
}

@StringRes
private fun stPromptSourceLabelRes(identifier: String): Int {
    return when (stBuiltInPromptDefinition(identifier)?.sourceKind) {
        StBuiltInPromptSourceKind.CHARACTER_DESCRIPTION -> R.string.prompt_page_st_preset_editor_source_character_description
        StBuiltInPromptSourceKind.CHARACTER_PERSONALITY -> R.string.prompt_page_st_preset_editor_source_character_personality
        StBuiltInPromptSourceKind.SCENARIO -> R.string.prompt_page_st_preset_editor_source_character_scenario
        StBuiltInPromptSourceKind.PERSONA_DESCRIPTION -> R.string.prompt_page_st_preset_editor_source_persona_description
        StBuiltInPromptSourceKind.WORLD_INFO -> R.string.prompt_page_st_preset_editor_source_world_info
        StBuiltInPromptSourceKind.DIALOGUE_EXAMPLES -> R.string.prompt_page_st_preset_editor_source_dialogue_examples
        StBuiltInPromptSourceKind.CHAT_HISTORY -> R.string.prompt_page_st_preset_editor_source_chat_history
        null -> R.string.prompt_page_st_preset_editor_prompt_content
    }
}

@StringRes
private fun stInjectionPositionLabelRes(position: StPromptInjectionPosition): Int {
    return when (position) {
        StPromptInjectionPosition.RELATIVE -> R.string.prompt_page_st_preset_editor_position_relative
        StPromptInjectionPosition.ABSOLUTE -> R.string.prompt_page_st_preset_editor_position_absolute
    }
}

@StringRes
private fun stPromptGenerationTypeLabelRes(trigger: String): Int {
    return when (trigger) {
        "normal" -> R.string.prompt_page_st_preset_editor_trigger_normal
        "continue" -> R.string.prompt_page_st_preset_editor_trigger_continue
        "quiet" -> R.string.prompt_page_st_preset_editor_trigger_quiet
        "impersonate" -> R.string.prompt_page_st_preset_editor_trigger_impersonate
        else -> R.string.prompt_page_st_preset_editor_trigger_normal
    }
}

@StringRes
private fun stNamesBehaviorLabelRes(value: Int?): Int {
    return when (value) {
        null -> R.string.prompt_page_st_preset_editor_names_behavior_unspecified
        -1 -> R.string.prompt_page_st_preset_editor_names_behavior_none
        0 -> R.string.prompt_page_st_preset_editor_names_behavior_default
        1 -> R.string.prompt_page_st_preset_editor_names_behavior_completion
        2 -> R.string.prompt_page_st_preset_editor_names_behavior_content
        else -> R.string.prompt_page_st_preset_editor_names_behavior_unspecified
    }
}
