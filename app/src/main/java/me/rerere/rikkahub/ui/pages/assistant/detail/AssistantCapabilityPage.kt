package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.FormSwitch
import me.rerere.rikkahub.ui.components.ui.FormTextField
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantCapabilityPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Capability")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantCapabilityContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantCapabilityContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val knowledgeBases = vm.settings.collectAsStateWithLifecycle().value.knowledgeBases
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val noneLabel = stringResource(R.string.assistant_page_workspace_none)
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace -> workspace?.name ?: noneLabel },
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text("Knowledge Base") },
                description = { Text("Attach a knowledge base to enable retrieval-augmented answers (null = off)") },
            ) {
                KnowledgeBaseSelector(
                    selectedId = assistant.knowledgeBaseId,
                    knowledgeBases = knowledgeBases,
                    onSelect = { kbId -> onUpdate(assistant.copy(knowledgeBaseId = kbId)) },
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_subagent_description))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_subagent_description_desc))
                }
            ) {
                FormTextField(
                    value = assistant.description,
                    externalKey = "${assistant.id}:description",
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                description = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enableFullscreen = true,
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_spawnable))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_spawnable_desc))
                },
                tail = {
                    FormSwitch(
                        checked = assistant.spawnable,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    spawnable = it
                                )
                            )
                        }
                    )
                }
            )

            if (assistant.spawnable) {
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_subagent_max_steps))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_subagent_max_steps_desc))
                    }
                ) {
                    FormTextField(
                        value = assistant.maxSteps?.toString() ?: "",
                        externalKey = "${assistant.id}:maxSteps",
                        onValueChange = { text ->
                            val steps = if (text.isBlank()) {
                                null
                            } else {
                                text.toIntOrNull()?.takeIf { it > 0 }
                            }
                            onUpdate(
                                assistant.copy(
                                    maxSteps = steps
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_ui_automation))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_ui_automation_desc))
                },
                tail = {
                    FormSwitch(
                        checked = assistant.uiAutomationEnabled,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    uiAutomationEnabled = it
                                )
                            )
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun KnowledgeBaseSelector(
    selectedId: Uuid?,
    knowledgeBases: List<KnowledgeBase>,
    onSelect: (Uuid?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = knowledgeBases.find { it.id == selectedId }?.name

    TextButton(onClick = { expanded = true }) {
        Text(selectedName ?: "None")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("None") },
            onClick = { onSelect(null); expanded = false },
        )
        knowledgeBases.forEach { kb ->
            DropdownMenuItem(
                text = { Text(kb.name.ifBlank { "Knowledge Base" }) },
                onClick = { onSelect(kb.id); expanded = false },
            )
        }
    }
}
