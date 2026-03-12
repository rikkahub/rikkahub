package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillInvalidEntry
import me.rerere.rikkahub.data.skills.SkillsCatalogState
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import org.koin.compose.koinInject

@Composable
fun SkillsPickerButton(
    assistant: Assistant,
    modelSupportsTools: Boolean,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    val skillsRepository = koinInject<SkillsRepository>()
    val skillsState by skillsRepository.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val enabledCount = if (assistant.skillsEnabled) {
        assistant.selectedSkills.count { it in skillsState.entryNames }
    } else {
        0
    }

    LaunchedEffect(showPicker) {
        if (showPicker) {
            skillsRepository.requestRefresh()
        }
    }

    ToggleSurface(
        modifier = modifier,
        checked = assistant.skillsEnabled,
        onClick = {
            showPicker = true
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (skillsState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    BadgedBox(
                        badge = {
                            if (enabledCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text(text = enabledCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Package01,
                            contentDescription = "Skills",
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Skills",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                SkillsPicker(
                    assistant = assistant,
                    skillsState = skillsState,
                    modelSupportsTools = modelSupportsTools,
                    onRefresh = skillsRepository::requestRefresh,
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
fun SkillsPicker(
    assistant: Assistant,
    skillsState: SkillsCatalogState,
    modelSupportsTools: Boolean,
    onRefresh: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val termuxToolEnabled = assistant.localTools.contains(LocalToolOption.TermuxExec)
    val missingSelections = remember(assistant.selectedSkills, skillsState.entryNames) {
        assistant.selectedSkills
            .filterNot { it in skillsState.entryNames }
            .sorted()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("controls") {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Enable skills catalog",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Expose selected skills from ${skillsState.rootPath.ifBlank { "(not resolved)" }} to the model as a lightweight catalog.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = assistant.skillsEnabled,
                            onCheckedChange = {
                                onUpdateAssistant(assistant.copy(skillsEnabled = it))
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Selected: ${assistant.selectedSkills.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefresh) {
                            Text("Refresh")
                        }
                    }
                }
            }
        }

        if (!termuxToolEnabled) {
            item("termux-warning") {
                SkillsInfoCard(
                    title = "Termux tool required",
                    text = "Enable Local Tools > Termux Command before using skills in chat.",
                    isError = true,
                )
            }
        }

        if (!modelSupportsTools) {
            item("model-warning") {
                SkillsInfoCard(
                    title = "Current model cannot use tools",
                    text = "Selections are saved, but the skills catalog will not be injected until the active model supports tool calls.",
                    isError = false,
                )
            }
        }

        if (skillsState.error != null) {
            item("error") {
                SkillsInfoCard(
                    title = "Failed to refresh skills",
                    text = skillsState.error.orEmpty(),
                    isError = true,
                )
            }
        }

        if (skillsState.isLoading) {
            item("loading") {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Refreshing skills from ${skillsState.rootPath.ifBlank { "workdir/skills" }}")
                    }
                }
            }
        }

        if (!skillsState.isLoading && skillsState.entries.isEmpty() && skillsState.invalidEntries.isEmpty()) {
            item("empty") {
                SkillsInfoCard(
                    title = "No skills found",
                    text = "Create subdirectories under ${skillsState.rootPath.ifBlank { "workdir/skills" }} and add a SKILL.md with name and description frontmatter.",
                    isError = false,
                )
            }
        }

        if (skillsState.entries.isNotEmpty()) {
            item("available-header") {
                Text(
                    text = "Available skills",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = skillsState.entries,
                key = { it.directoryName },
            ) { entry ->
                SkillEntryCard(
                    entry = entry,
                    checked = entry.directoryName in assistant.selectedSkills,
                    onCheckedChange = { checked ->
                        val nextSelection = assistant.selectedSkills.toMutableSet().apply {
                            if (checked) add(entry.directoryName) else remove(entry.directoryName)
                        }
                        onUpdateAssistant(assistant.copy(selectedSkills = nextSelection))
                    },
                )
            }
        }

        if (missingSelections.isNotEmpty()) {
            item("missing-header") {
                Text(
                    text = "Missing selections",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = missingSelections,
                key = { it },
            ) { directoryName ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Alert01, contentDescription = null)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = directoryName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "This skill is still selected on the assistant, but it no longer exists in ${skillsState.rootPath.ifBlank { "workdir/skills" }}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = true,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    onUpdateAssistant(
                                        assistant.copy(
                                            selectedSkills = assistant.selectedSkills - directoryName
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        if (skillsState.invalidEntries.isNotEmpty()) {
            item("invalid-header") {
                Text(
                    text = "Ignored directories",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = skillsState.invalidEntries,
                key = { "${it.directoryName}:${it.reason}" },
            ) { entry ->
                InvalidSkillEntryCard(entry = entry)
            }
        }
    }
}

@Composable
private fun SkillEntryCard(
    entry: SkillCatalogEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Package01, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.name != entry.directoryName) {
                    Text(
                        text = entry.directoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun InvalidSkillEntryCard(entry: SkillInvalidEntry) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Alert01, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.directoryName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SkillsInfoCard(
    title: String,
    text: String,
    isError: Boolean,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
