package me.rerere.rikkahub.ui.pages.extensions

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.AssistantRegexSubstituteStrategy
import me.rerere.rikkahub.ui.components.ui.EditorGuideAction
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors

private val regexPlacementOptions = listOf(
    AssistantRegexPlacement.USER_INPUT,
    AssistantRegexPlacement.AI_OUTPUT,
    AssistantRegexPlacement.SLASH_COMMAND,
    AssistantRegexPlacement.WORLD_INFO,
    AssistantRegexPlacement.REASONING,
)

private val regexSubstituteOptions = listOf(
    AssistantRegexSubstituteStrategy.NONE,
    AssistantRegexSubstituteStrategy.RAW,
    AssistantRegexSubstituteStrategy.ESCAPED,
)

@Composable
fun RegexEditorSection(
    regexes: List<AssistantRegex>,
    onUpdate: (List<AssistantRegex>) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.assistant_page_regex_title),
    description: String = stringResource(R.string.assistant_page_regex_desc),
) {
    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        FormItem(
            modifier = Modifier.padding(8.dp),
            label = {
                Text(title)
            },
            description = {
                Text(description)
            },
            tail = {
                EditorGuideAction(
                    title = stringResource(R.string.assistant_page_regex_help_title),
                    body = stringResource(R.string.assistant_page_regex_help_body_markdown),
                )
            }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            regexes.fastForEachIndexed { index, regex ->
                RegexEditorCard(
                    regex = regex,
                    regexes = regexes,
                    index = index,
                    onUpdate = onUpdate,
                )
            }
            Button(
                onClick = {
                    onUpdate(
                        regexes + AssistantRegex(
                            id = Uuid.random()
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(HugeIcons.Add01, null)
                Text(stringResource(R.string.add))
            }
        }
    }
}

@Composable
private fun RegexEditorCard(
    regex: AssistantRegex,
    regexes: List<AssistantRegex>,
    index: Int,
    onUpdate: (List<AssistantRegex>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    fun updateRegex(transform: (AssistantRegex) -> AssistantRegex) {
        onUpdate(
            regexes.mapIndexed { i, reg ->
                if (i == index) {
                    transform(reg)
                } else {
                    reg
                }
            }
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = regex.name.ifBlank { stringResource(R.string.assistant_page_regex_unnamed) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = regex.findRegex.ifBlank { stringResource(R.string.assistant_page_regex_summary_placeholder) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        updateRegex { it.copy(enabled = enabled) }
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
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

            OutlinedTextField(
                value = regex.name,
                onValueChange = { name ->
                    updateRegex { it.copy(name = name) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_name)) }
            )

            OutlinedTextField(
                value = regex.findRegex,
                onValueChange = { findRegex ->
                    updateRegex { it.copy(findRegex = findRegex.trim()) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                placeholder = { Text(stringResource(R.string.assistant_page_regex_find_regex_placeholder)) },
            )

            Select(
                options = regexSubstituteOptions,
                selectedOption = regex.substituteRegex,
                onOptionSelected = { strategy ->
                    updateRegex { it.copy(substituteRegex = strategy) }
                },
                modifier = Modifier.fillMaxWidth(),
                optionToString = { stringResource(regexSubstituteLabelRes(it)) }
            )

            Text(
                text = stringResource(R.string.assistant_page_regex_substitute_regex_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = regex.replaceString,
                onValueChange = { replaceString ->
                    updateRegex { it.copy(replaceString = replaceString) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                placeholder = { Text(stringResource(R.string.assistant_page_regex_replace_string_placeholder)) }
            )

            OutlinedTextField(
                value = regex.trimStrings.joinToString("\n"),
                onValueChange = { value ->
                    updateRegex {
                        it.copy(trimStrings = parseRegexMultilineList(value))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_trim_strings)) },
                minLines = 2,
            )

            Text(
                text = stringResource(R.string.assistant_page_regex_trim_strings_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistantAffectScope.entries.fastForEach { scope ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = scope in regex.affectingScope,
                                onCheckedChange = { checked ->
                                    val newScopes = if (checked) {
                                        regex.affectingScope + scope
                                    } else {
                                        regex.affectingScope - scope
                                    }
                                    updateRegex { it.copy(affectingScope = newScopes) }
                                }
                            )
                            Text(
                                text = stringResource(regexScopeLabelRes(scope)),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_regex_st_placements),
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    regexPlacementOptions.fastForEach { placement ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = placement in regex.stPlacements,
                                onCheckedChange = { checked ->
                                    val updatedPlacements = if (checked) {
                                        regex.stPlacements + placement
                                    } else {
                                        regex.stPlacements - placement
                                    }
                                    updateRegex { it.copy(stPlacements = updatedPlacements) }
                                }
                            )
                            Text(
                                text = stringResource(regexPlacementLabelRes(placement)),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.assistant_page_regex_st_placements_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RegexCheckboxField(
                    label = stringResource(R.string.assistant_page_regex_visual_only),
                    checked = regex.visualOnly,
                    onCheckedChange = { visualOnly ->
                        updateRegex {
                            it.copy(
                                visualOnly = visualOnly,
                                promptOnly = if (visualOnly) false else it.promptOnly
                            )
                        }
                    }
                )
                RegexCheckboxField(
                    label = stringResource(R.string.assistant_page_regex_prompt_only),
                    checked = regex.promptOnly,
                    onCheckedChange = { promptOnly ->
                        updateRegex {
                            it.copy(
                                promptOnly = promptOnly,
                                visualOnly = if (promptOnly) false else it.visualOnly
                            )
                        }
                    }
                )
                RegexCheckboxField(
                    label = stringResource(R.string.assistant_page_regex_run_on_edit),
                    checked = regex.runOnEdit,
                    onCheckedChange = { runOnEdit ->
                        updateRegex { it.copy(runOnEdit = runOnEdit) }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = regex.minDepth?.toString().orEmpty(),
                    onValueChange = { value ->
                        if (value.isNotEmpty() && value.any { !it.isDigit() }) return@OutlinedTextField
                        val minDepth = value.toIntOrNull()?.takeIf { it > 0 }
                        updateRegex { it.copy(minDepth = minDepth) }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_page_regex_min_depth)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = regex.maxDepth?.toString().orEmpty(),
                    onValueChange = { value ->
                        if (value.isNotEmpty() && value.any { !it.isDigit() }) return@OutlinedTextField
                        val maxDepth = value.toIntOrNull()?.takeIf { it > 0 }
                        updateRegex { it.copy(maxDepth = maxDepth) }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.assistant_page_regex_max_depth)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            Text(
                text = stringResource(R.string.assistant_page_regex_depth_desc),
                style = MaterialTheme.typography.labelSmall,
            )

            TextButton(
                onClick = {
                    onUpdate(
                        regexes.filterIndexed { i, _ -> i != index }
                    )
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(HugeIcons.Delete01, null)
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun RegexCheckboxField(
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

private fun parseRegexMultilineList(value: String): List<String> {
    return value.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

@StringRes
private fun regexSubstituteLabelRes(strategy: Int): Int {
    return when (strategy) {
        AssistantRegexSubstituteStrategy.RAW -> R.string.assistant_page_regex_substitute_regex_raw
        AssistantRegexSubstituteStrategy.ESCAPED -> R.string.assistant_page_regex_substitute_regex_escaped
        else -> R.string.assistant_page_regex_substitute_regex_none
    }
}

@StringRes
private fun regexPlacementLabelRes(placement: Int): Int {
    return when (placement) {
        AssistantRegexPlacement.USER_INPUT -> R.string.assistant_page_regex_st_placement_user_input
        AssistantRegexPlacement.AI_OUTPUT -> R.string.assistant_page_regex_st_placement_ai_output
        AssistantRegexPlacement.SLASH_COMMAND -> R.string.assistant_page_regex_st_placement_slash_command
        AssistantRegexPlacement.WORLD_INFO -> R.string.assistant_page_regex_st_placement_world_info
        AssistantRegexPlacement.REASONING -> R.string.assistant_page_regex_st_placement_reasoning
        else -> R.string.assistant_page_regex_st_placement_ai_output
    }
}

@StringRes
private fun regexScopeLabelRes(scope: AssistantAffectScope): Int {
    return when (scope) {
        AssistantAffectScope.SYSTEM -> R.string.assistant_page_regex_scope_system
        AssistantAffectScope.USER -> R.string.assistant_page_regex_scope_user
        AssistantAffectScope.ASSISTANT -> R.string.assistant_page_regex_scope_assistant
    }
}
