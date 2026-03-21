package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
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
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
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
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val enabledTools = LocalToolCatalog.all.filter { assistant.localTools.contains(it.option) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            LocalToolCatalog.all.forEach { tool ->
                item(
                    headlineContent = {
                        Text(stringResource(tool.titleRes))
                    },
                    supportingContent = {
                        Text(stringResource(tool.descRes))
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.localTools.contains(tool.option),
                            onCheckedChange = { enabled ->
                                val newLocalTools = if (enabled) {
                                    (assistant.localTools + tool.option).distinct()
                                } else {
                                    assistant.localTools - tool.option
                                }
                                onUpdate(assistant.copy(localTools = newLocalTools))
                            }
                        )
                    }
                )
            }
        }

        if (enabledTools.isNotEmpty()) {
            Text(
                text = "Custom Tool Prompts",
                style = MaterialTheme.typography.titleMedium
            )

            enabledTools.forEach { tool ->
                Card(
                    colors = CustomColors.cardColorsOnSurfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(tool.titleRes),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Optional text appended to this tool's prompt for the current assistant.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = assistant.localToolPrompts[tool.toolName].orEmpty(),
                            onValueChange = { prompt ->
                                val prompts = assistant.localToolPrompts.toMutableMap().apply {
                                    if (prompt.isBlank()) {
                                        remove(tool.toolName)
                                    } else {
                                        put(tool.toolName, prompt)
                                    }
                                }
                                onUpdate(assistant.copy(localToolPrompts = prompts))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            label = { Text("Tool Prompt") },
                            placeholder = { Text("Optional") },
                        )
                    }
                }
            }
        }
    }
}
