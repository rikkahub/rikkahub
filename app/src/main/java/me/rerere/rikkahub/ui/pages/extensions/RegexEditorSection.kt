package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlin.uuid.Uuid

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
                Text(
                    text = regex.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 200.dp)
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            regexes.mapIndexed { i, reg ->
                                if (i == index) reg.copy(enabled = enabled) else reg
                            }
                        )
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
                    onUpdate(
                        regexes.mapIndexed { i, reg ->
                            if (i == index) reg.copy(name = name) else reg
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_name)) }
            )

            OutlinedTextField(
                value = regex.findRegex,
                onValueChange = { findRegex ->
                    onUpdate(
                        regexes.mapIndexed { i, reg ->
                            if (i == index) reg.copy(findRegex = findRegex.trim()) else reg
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                placeholder = { Text("e.g., \\b\\w+@\\w+\\.\\w+\\b") },
            )

            OutlinedTextField(
                value = regex.replaceString,
                onValueChange = { replaceString ->
                    onUpdate(
                        regexes.mapIndexed { i, reg ->
                            if (i == index) reg.copy(replaceString = replaceString) else reg
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                placeholder = { Text("e.g., [EMAIL]") }
            )

            Column {
                Text(
                    text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AssistantAffectScope.entries.forEach { scope ->
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
                                    onUpdate(
                                        regexes.mapIndexed { i, reg ->
                                            if (i == index) reg.copy(affectingScope = newScopes) else reg
                                        }
                                    )
                                }
                            )
                            Text(
                                text = scope.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = regex.visualOnly,
                    onCheckedChange = { visualOnly ->
                        onUpdate(
                            regexes.mapIndexed { i, reg ->
                                if (i == index) {
                                    reg.copy(
                                        visualOnly = visualOnly,
                                        promptOnly = if (visualOnly) false else reg.promptOnly
                                    )
                                } else {
                                    reg
                                }
                            }
                        )
                    }
                )
                Text(
                    text = stringResource(R.string.assistant_page_regex_visual_only),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = regex.promptOnly,
                    onCheckedChange = { promptOnly ->
                        onUpdate(
                            regexes.mapIndexed { i, reg ->
                                if (i == index) {
                                    reg.copy(
                                        promptOnly = promptOnly,
                                        visualOnly = if (promptOnly) false else reg.visualOnly
                                    )
                                } else {
                                    reg
                                }
                            }
                        )
                    }
                )
                Text(
                    text = stringResource(R.string.assistant_page_regex_prompt_only),
                    style = MaterialTheme.typography.labelMedium
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
                        onUpdate(
                            regexes.mapIndexed { i, reg ->
                                if (i == index) reg.copy(minDepth = minDepth) else reg
                            }
                        )
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
                        onUpdate(
                            regexes.mapIndexed { i, reg ->
                                if (i == index) reg.copy(maxDepth = maxDepth) else reg
                            }
                        )
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
