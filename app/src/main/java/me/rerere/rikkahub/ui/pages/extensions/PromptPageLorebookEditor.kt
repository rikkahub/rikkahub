package me.rerere.rikkahub.ui.pages.extensions

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.stExtension
import me.rerere.rikkahub.data.model.updateStExtension
import me.rerere.rikkahub.ui.components.ui.EditorGuideAction
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegexInjectionEditDialog(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var newKeyword by remember(entry.id) { mutableStateOf("") }
    var newSecondaryKeyword by remember(entry.id) { mutableStateOf("") }
    val placement = entry.toLorebookInjectionPlacement()
    val useProbability = entry.metadataBoolean("useProbability", default = true)
    val triggers = entry.metadataList("triggers")

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
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_edit_entry),
                    style = MaterialTheme.typography.titleLarge,
                )
                EditorGuideAction(
                    title = stringResource(R.string.prompt_page_lorebook_entry_help_title),
                    body = stringResource(R.string.prompt_page_lorebook_entry_help_body_markdown),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onEdit(entry.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onEdit(entry.copy(enabled = it)) },
                        )
                    },
                )

                OutlinedTextField(
                    value = entry.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(entry.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Text(
                    text = stringResource(R.string.prompt_page_lorebook_entry_trigger_section),
                    style = MaterialTheme.typography.titleSmall,
                )

                EditableStringChipField(
                    label = stringResource(R.string.prompt_page_keywords_label),
                    values = entry.keywords,
                    inputValue = newKeyword,
                    onInputChange = { newKeyword = it },
                    onAdd = { keyword ->
                        onEdit(entry.copy(keywords = (entry.keywords + keyword).distinct()))
                        newKeyword = ""
                    },
                    onRemove = { keyword ->
                        onEdit(entry.copy(keywords = entry.keywords - keyword))
                    },
                    newItemLabel = stringResource(R.string.prompt_page_new_keyword),
                    description = stringResource(R.string.prompt_page_lorebook_entry_primary_keywords_desc),
                )

                EditableStringChipField(
                    label = stringResource(R.string.prompt_page_lorebook_secondary_keywords),
                    values = entry.secondaryKeywords,
                    inputValue = newSecondaryKeyword,
                    onInputChange = { newSecondaryKeyword = it },
                    onAdd = { keyword ->
                        onEdit(entry.copy(secondaryKeywords = (entry.secondaryKeywords + keyword).distinct()))
                        newSecondaryKeyword = ""
                    },
                    onRemove = { keyword ->
                        onEdit(entry.copy(secondaryKeywords = entry.secondaryKeywords - keyword))
                    },
                    newItemLabel = stringResource(R.string.prompt_page_lorebook_secondary_keyword_new),
                    description = stringResource(R.string.prompt_page_lorebook_secondary_keywords_desc),
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                    tail = {
                        Switch(
                            checked = entry.useRegex,
                            onCheckedChange = { onEdit(entry.copy(useRegex = it)) },
                        )
                    },
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_case_sensitive),
                        checked = entry.caseSensitive,
                        onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_whole_words),
                        checked = entry.matchWholeWords,
                        onCheckedChange = { onEdit(entry.copy(matchWholeWords = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_constant_active),
                        checked = entry.constantActive,
                        onCheckedChange = { onEdit(entry.copy(constantActive = it)) },
                    )
                }

                Text(
                    text = stringResource(R.string.prompt_page_constant_active_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_lorebook_selective)) },
                    description = { Text(stringResource(R.string.prompt_page_lorebook_selective_desc)) },
                    tail = {
                        Switch(
                            checked = entry.selective,
                            onCheckedChange = { onEdit(entry.copy(selective = it)) },
                        )
                    },
                )

                AnimatedVisibility(visible = entry.selective) {
                    Select(
                        options = lorebookSelectiveLogicOptions,
                        selectedOption = entry.selectiveLogic,
                        onOptionSelected = { onEdit(entry.copy(selectiveLogic = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        optionToString = { stringResource(lorebookSelectiveLogicLabelRes(it)) },
                    )
                }

                OutlinedTextField(
                    value = entry.scanDepth.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { d -> onEdit(entry.copy(scanDepth = d)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_lorebook_use_probability)) },
                    description = { Text(stringResource(R.string.prompt_page_lorebook_use_probability_desc)) },
                    tail = {
                        Switch(
                            checked = useProbability,
                            onCheckedChange = {
                                onEdit(entry.withMetadataBoolean("useProbability", it, persistFalse = true))
                            },
                        )
                    },
                )

                AnimatedVisibility(visible = useProbability) {
                    OutlinedTextField(
                        value = entry.probability?.toString().orEmpty(),
                        onValueChange = { value ->
                            val normalized = value.trim()
                            onEdit(
                                entry.copy(
                                    probability = normalized.toIntOrNull()?.coerceIn(0, 100),
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.prompt_page_lorebook_probability)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text(
                    text = stringResource(R.string.prompt_page_lorebook_trigger_sources),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_character_description),
                        checked = entry.matchCharacterDescription,
                        onCheckedChange = { onEdit(entry.copy(matchCharacterDescription = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_character_personality),
                        checked = entry.matchCharacterPersonality,
                        onCheckedChange = { onEdit(entry.copy(matchCharacterPersonality = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_persona_description),
                        checked = entry.matchPersonaDescription,
                        onCheckedChange = { onEdit(entry.copy(matchPersonaDescription = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_scenario),
                        checked = entry.matchScenario,
                        onCheckedChange = { onEdit(entry.copy(matchScenario = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_creator_notes),
                        checked = entry.matchCreatorNotes,
                        onCheckedChange = { onEdit(entry.copy(matchCreatorNotes = it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_match_depth_prompt),
                        checked = entry.matchCharacterDepthPrompt,
                        onCheckedChange = { onEdit(entry.copy(matchCharacterDepthPrompt = it)) },
                    )
                }

                Text(
                    text = stringResource(R.string.prompt_page_lorebook_generation_triggers),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lorebookGenerationTypes.forEach { trigger ->
                        val selected = trigger in triggers
                        Tag(
                            type = if (selected) TagType.INFO else TagType.DEFAULT,
                            onClick = {
                                val updatedTriggers = if (selected) {
                                    triggers - trigger
                                } else {
                                    (triggers + trigger).distinct()
                                }
                                onEdit(entry.withMetadataList("triggers", updatedTriggers))
                            },
                        ) {
                            Text(stringResource(lorebookGenerationTypeLabelRes(trigger)))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.prompt_page_lorebook_entry_injection_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                LorebookInjectionPlacementSelector(
                    placement = placement,
                    onSelect = { onEdit(entry.withLorebookInjectionPlacement(it)) },
                )

                Select(
                    options = lorebookEntryRoles,
                    selectedOption = entry.role,
                    onOptionSelected = { onEdit(entry.copy(role = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { stringResource(lorebookRoleLabelRes(it)) },
                )

                AnimatedVisibility(visible = placement.isDepthPlacement()) {
                    OutlinedTextField(
                        value = entry.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(entry.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text(
                    text = stringResource(R.string.prompt_page_lorebook_advanced_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.prompt_page_lorebook_advanced_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = entry.metadataText("group"),
                    onValueChange = { onEdit(entry.withMetadataText("group", it)) },
                    label = { Text(stringResource(R.string.prompt_page_lorebook_group_names)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = entry.metadataInt("group_weight")?.toString().orEmpty(),
                        onValueChange = { value ->
                            onEdit(entry.withMetadataInt("group_weight", value.toIntOrNull()))
                        },
                        label = { Text(stringResource(R.string.prompt_page_lorebook_group_weight)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = entry.metadataInt("delay")?.toString().orEmpty(),
                        onValueChange = { value ->
                            onEdit(entry.withMetadataInt("delay", value.toIntOrNull()))
                        },
                        label = { Text(stringResource(R.string.prompt_page_lorebook_delay_messages)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = entry.metadataInt("sticky")?.toString().orEmpty(),
                        onValueChange = { value ->
                            onEdit(entry.withMetadataInt("sticky", value.toIntOrNull()))
                        },
                        label = { Text(stringResource(R.string.prompt_page_lorebook_sticky_messages)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = entry.metadataInt("cooldown")?.toString().orEmpty(),
                        onValueChange = { value ->
                            onEdit(entry.withMetadataInt("cooldown", value.toIntOrNull()))
                        },
                        label = { Text(stringResource(R.string.prompt_page_lorebook_cooldown_messages)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                OutlinedTextField(
                    value = entry.metadataText("delay_until_recursion"),
                    onValueChange = { onEdit(entry.withMetadataText("delay_until_recursion", it)) },
                    label = { Text(stringResource(R.string.prompt_page_lorebook_delay_until_recursion)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_group_override),
                        checked = entry.metadataBoolean("group_override"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("group_override", it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_use_group_scoring),
                        checked = entry.metadataBoolean("use_group_scoring"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("use_group_scoring", it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_exclude_recursion),
                        checked = entry.metadataBoolean("exclude_recursion"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("exclude_recursion", it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_prevent_recursion),
                        checked = entry.metadataBoolean("prevent_recursion"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("prevent_recursion", it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_ignore_budget),
                        checked = entry.metadataBoolean("ignore_budget"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("ignore_budget", it)) },
                    )
                    LorebookCheckboxField(
                        label = stringResource(R.string.prompt_page_lorebook_vectorized),
                        checked = entry.metadataBoolean("vectorized"),
                        onCheckedChange = { onEdit(entry.withMetadataBoolean("vectorized", it)) },
                    )
                }

                OutlinedTextField(
                    value = entry.metadataText("outlet_name"),
                    onValueChange = { onEdit(entry.withMetadataText("outlet_name", it)) },
                    label = { Text(stringResource(R.string.prompt_page_lorebook_outlet_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = entry.metadataText("automation_id"),
                    onValueChange = { onEdit(entry.withMetadataText("automation_id", it)) },
                    label = { Text(stringResource(R.string.prompt_page_lorebook_automation_id)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { onEdit(entry.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    minLines = 5,
                )
            }

            val canSave = entry.keywords.isNotEmpty() || entry.constantActive
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = canSave,
                ) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

private enum class LorebookInjectionPlacement {
    BEFORE_SYSTEM_PROMPT,
    AFTER_SYSTEM_PROMPT,
    AUTHOR_NOTE_TOP,
    AUTHOR_NOTE_BOTTOM,
    SYSTEM_DEPTH,
    USER_DEPTH,
    ASSISTANT_DEPTH,
    EXAMPLE_MESSAGES_TOP,
    EXAMPLE_MESSAGES_BOTTOM,
    OUTLET,
}

private val lorebookInjectionPlacements = listOf(
    LorebookInjectionPlacement.BEFORE_SYSTEM_PROMPT,
    LorebookInjectionPlacement.AFTER_SYSTEM_PROMPT,
    LorebookInjectionPlacement.AUTHOR_NOTE_TOP,
    LorebookInjectionPlacement.AUTHOR_NOTE_BOTTOM,
    LorebookInjectionPlacement.SYSTEM_DEPTH,
    LorebookInjectionPlacement.USER_DEPTH,
    LorebookInjectionPlacement.ASSISTANT_DEPTH,
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_TOP,
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_BOTTOM,
    LorebookInjectionPlacement.OUTLET,
)

private val lorebookSelectiveLogicOptions = listOf(0, 1, 2, 3)
private val lorebookGenerationTypes = listOf("normal", "continue", "quiet", "impersonate")
private val lorebookEntryRoles = listOf(
    MessageRole.SYSTEM,
    MessageRole.USER,
    MessageRole.ASSISTANT,
)

private fun PromptInjection.RegexInjection.toLorebookInjectionPlacement(): LorebookInjectionPlacement = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> LorebookInjectionPlacement.BEFORE_SYSTEM_PROMPT
    InjectionPosition.AFTER_SYSTEM_PROMPT -> LorebookInjectionPlacement.AFTER_SYSTEM_PROMPT
    InjectionPosition.AUTHOR_NOTE_TOP,
    InjectionPosition.TOP_OF_CHAT,
    -> LorebookInjectionPlacement.AUTHOR_NOTE_TOP
    InjectionPosition.AUTHOR_NOTE_BOTTOM,
    InjectionPosition.BOTTOM_OF_CHAT,
    -> LorebookInjectionPlacement.AUTHOR_NOTE_BOTTOM
    InjectionPosition.AT_DEPTH -> when (role) {
        MessageRole.USER -> LorebookInjectionPlacement.USER_DEPTH
        MessageRole.ASSISTANT -> LorebookInjectionPlacement.ASSISTANT_DEPTH
        else -> LorebookInjectionPlacement.SYSTEM_DEPTH
    }

    InjectionPosition.EXAMPLE_MESSAGES_TOP -> LorebookInjectionPlacement.EXAMPLE_MESSAGES_TOP
    InjectionPosition.EXAMPLE_MESSAGES_BOTTOM -> LorebookInjectionPlacement.EXAMPLE_MESSAGES_BOTTOM
    InjectionPosition.OUTLET -> LorebookInjectionPlacement.OUTLET
}

private fun PromptInjection.RegexInjection.withLorebookInjectionPlacement(
    placement: LorebookInjectionPlacement,
): PromptInjection.RegexInjection = when (placement) {
    LorebookInjectionPlacement.BEFORE_SYSTEM_PROMPT -> copy(position = InjectionPosition.BEFORE_SYSTEM_PROMPT)
    LorebookInjectionPlacement.AFTER_SYSTEM_PROMPT -> copy(position = InjectionPosition.AFTER_SYSTEM_PROMPT)
    LorebookInjectionPlacement.AUTHOR_NOTE_TOP -> copy(position = InjectionPosition.AUTHOR_NOTE_TOP)
    LorebookInjectionPlacement.AUTHOR_NOTE_BOTTOM -> copy(position = InjectionPosition.AUTHOR_NOTE_BOTTOM)
    LorebookInjectionPlacement.SYSTEM_DEPTH -> copy(
        position = InjectionPosition.AT_DEPTH,
        role = MessageRole.SYSTEM,
    )
    LorebookInjectionPlacement.USER_DEPTH -> copy(
        position = InjectionPosition.AT_DEPTH,
        role = MessageRole.USER,
    )
    LorebookInjectionPlacement.ASSISTANT_DEPTH -> copy(
        position = InjectionPosition.AT_DEPTH,
        role = MessageRole.ASSISTANT,
    )
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_TOP -> copy(position = InjectionPosition.EXAMPLE_MESSAGES_TOP)
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_BOTTOM -> copy(position = InjectionPosition.EXAMPLE_MESSAGES_BOTTOM)
    LorebookInjectionPlacement.OUTLET -> copy(position = InjectionPosition.OUTLET)
}

private fun LorebookInjectionPlacement.isDepthPlacement(): Boolean = when (this) {
    LorebookInjectionPlacement.SYSTEM_DEPTH,
    LorebookInjectionPlacement.USER_DEPTH,
    LorebookInjectionPlacement.ASSISTANT_DEPTH,
    -> true
    else -> false
}

@Composable
private fun LorebookInjectionPlacementSelector(
    placement: LorebookInjectionPlacement,
    onSelect: (LorebookInjectionPlacement) -> Unit,
) {
    Select(
        options = lorebookInjectionPlacements,
        selectedOption = placement,
        onOptionSelected = onSelect,
        optionToString = { getLorebookInjectionPlacementLabel(it) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun getLorebookInjectionPlacementLabel(placement: LorebookInjectionPlacement): String = when (placement) {
    LorebookInjectionPlacement.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    LorebookInjectionPlacement.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    LorebookInjectionPlacement.AUTHOR_NOTE_TOP -> stringResource(R.string.prompt_page_position_author_note_top)
    LorebookInjectionPlacement.AUTHOR_NOTE_BOTTOM -> stringResource(R.string.prompt_page_position_author_note_bottom)
    LorebookInjectionPlacement.SYSTEM_DEPTH -> stringResource(R.string.prompt_page_position_system_depth)
    LorebookInjectionPlacement.USER_DEPTH -> stringResource(R.string.prompt_page_position_user_depth)
    LorebookInjectionPlacement.ASSISTANT_DEPTH -> stringResource(R.string.prompt_page_position_assistant_depth)
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_TOP -> stringResource(R.string.prompt_page_position_example_messages_top)
    LorebookInjectionPlacement.EXAMPLE_MESSAGES_BOTTOM -> stringResource(R.string.prompt_page_position_example_messages_bottom)
    LorebookInjectionPlacement.OUTLET -> stringResource(R.string.prompt_page_position_outlet)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditableStringChipField(
    label: String,
    values: List<String>,
    inputValue: String,
    onInputChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    newItemLabel: String,
    description: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            values.forEach { value ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(value) },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemove(value) },
                            modifier = Modifier.size(16.dp),
                        ) {
                            Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                        }
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputValue,
                onValueChange = onInputChange,
                label = { Text(newItemLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    inputValue.trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(onAdd)
                },
            ) {
                Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
            }
        }
    }
}

@Composable
private fun LorebookCheckboxField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun PromptInjection.RegexInjection.metadataBoolean(
    key: String,
    default: Boolean = false,
): Boolean {
    val extension = stExtension()
    return when (key) {
        "useProbability" -> extension.useProbability ?: default
        "group_override" -> extension.groupOverride
        "use_group_scoring" -> extension.useGroupScoring
        "exclude_recursion" -> extension.excludeRecursion
        "prevent_recursion" -> extension.preventRecursion
        "ignore_budget" -> extension.ignoreBudget
        "vectorized" -> extension.vectorized
        else -> stMetadata[key]?.trim()?.let { value ->
            value.equals("true", ignoreCase = true) || value == "1"
        } ?: default
    }
}

private fun PromptInjection.RegexInjection.metadataInt(key: String): Int? {
    val extension = stExtension()
    return when (key) {
        "group_weight" -> extension.groupWeight
        "delay" -> extension.delay
        "sticky" -> extension.sticky
        "cooldown" -> extension.cooldown
        else -> stMetadata[key]?.trim()?.toIntOrNull()
    }
}

private fun PromptInjection.RegexInjection.metadataText(key: String): String {
    val extension = stExtension()
    return when (key) {
        "group" -> extension.group
        "delay_until_recursion" -> extension.delayUntilRecursion
        "outlet_name" -> extension.outletName
        "automation_id" -> extension.automationId
        else -> stMetadata[key].orEmpty()
    }
}

private fun PromptInjection.RegexInjection.metadataList(key: String): List<String> {
    return when (key) {
        "triggers" -> stExtension().triggers
        else -> Regex("[,\\n]")
            .split(stMetadata[key].orEmpty())
            .mapNotNull { value ->
                value
                    .trim()
                    .removePrefix("[")
                    .removeSuffix("]")
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
    }
}

private fun PromptInjection.RegexInjection.withMetadataText(
    key: String,
    value: String,
): PromptInjection.RegexInjection {
    val normalized = value.trim()
    return when (key) {
        "group" -> updateStExtension { it.copy(group = normalized) }
        "delay_until_recursion" -> updateStExtension { it.copy(delayUntilRecursion = normalized) }
        "outlet_name" -> updateStExtension { it.copy(outletName = normalized) }
        "automation_id" -> updateStExtension { it.copy(automationId = normalized) }
        else -> {
            val updated = stMetadata.toMutableMap()
            if (normalized.isEmpty()) {
                updated.remove(key)
            } else {
                updated[key] = normalized
            }
            copy(stMetadata = updated)
        }
    }
}

private fun PromptInjection.RegexInjection.withMetadataBoolean(
    key: String,
    value: Boolean,
    persistFalse: Boolean = false,
): PromptInjection.RegexInjection {
    return when (key) {
        "useProbability" -> updateStExtension {
            it.copy(useProbability = if (value || persistFalse) value else null)
        }
        "group_override" -> updateStExtension { it.copy(groupOverride = value) }
        "use_group_scoring" -> updateStExtension { it.copy(useGroupScoring = value) }
        "exclude_recursion" -> updateStExtension { it.copy(excludeRecursion = value) }
        "prevent_recursion" -> updateStExtension { it.copy(preventRecursion = value) }
        "ignore_budget" -> updateStExtension { it.copy(ignoreBudget = value) }
        "vectorized" -> updateStExtension { it.copy(vectorized = value) }
        else -> {
            val updated = stMetadata.toMutableMap()
            when {
                value -> updated[key] = "true"
                persistFalse -> updated[key] = "false"
                else -> updated.remove(key)
            }
            copy(stMetadata = updated)
        }
    }
}

private fun PromptInjection.RegexInjection.withMetadataInt(
    key: String,
    value: Int?,
): PromptInjection.RegexInjection {
    val normalized = value?.takeIf { it > 0 }
    return when (key) {
        "group_weight" -> updateStExtension { it.copy(groupWeight = normalized) }
        "delay" -> updateStExtension { it.copy(delay = normalized) }
        "sticky" -> updateStExtension { it.copy(sticky = normalized) }
        "cooldown" -> updateStExtension { it.copy(cooldown = normalized) }
        else -> {
            val updated = stMetadata.toMutableMap()
            if (normalized == null) {
                updated.remove(key)
            } else {
                updated[key] = normalized.toString()
            }
            copy(stMetadata = updated)
        }
    }
}

private fun PromptInjection.RegexInjection.withMetadataList(
    key: String,
    values: List<String>,
): PromptInjection.RegexInjection {
    val normalized = values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    return when (key) {
        "triggers" -> updateStExtension { it.copy(triggers = normalized) }
        else -> {
            val updated = stMetadata.toMutableMap()
            if (normalized.isEmpty()) {
                updated.remove(key)
            } else {
                updated[key] = buildJsonArray {
                    normalized.forEach { add(JsonPrimitive(it)) }
                }.toString()
            }
            copy(stMetadata = updated)
        }
    }
}

@StringRes
private fun lorebookSelectiveLogicLabelRes(value: Int): Int {
    return when (value) {
        1 -> R.string.prompt_page_lorebook_selective_logic_not_all
        2 -> R.string.prompt_page_lorebook_selective_logic_not_any
        3 -> R.string.prompt_page_lorebook_selective_logic_all
        else -> R.string.prompt_page_lorebook_selective_logic_any
    }
}

@StringRes
private fun lorebookGenerationTypeLabelRes(value: String): Int {
    return when (value) {
        "continue" -> R.string.prompt_page_st_preset_editor_trigger_continue
        "quiet" -> R.string.prompt_page_st_preset_editor_trigger_quiet
        "impersonate" -> R.string.prompt_page_st_preset_editor_trigger_impersonate
        else -> R.string.prompt_page_st_preset_editor_trigger_normal
    }
}

@StringRes
private fun lorebookRoleLabelRes(role: MessageRole): Int {
    return when (role) {
        MessageRole.USER -> R.string.prompt_page_st_preset_editor_role_user
        MessageRole.ASSISTANT -> R.string.prompt_page_st_preset_editor_role_assistant
        else -> R.string.prompt_page_st_preset_editor_role_system
    }
}
