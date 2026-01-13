package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.TextArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantThinkingModePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_thinking_mode))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { innerPadding ->
        AssistantThinkingModeContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantThinkingModeContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 启用开关
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_thinking_mode_enabled))
                },
                description = {
                    Text(stringResource(R.string.assistant_thinking_mode_enabled_desc))
                },
                content = {
                    Switch(
                        checked = assistant.thinkingMode.enabled,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    thinkingMode = assistant.thinkingMode.copy(enabled = enabled)
                                )
                            )
                        }
                    )
                }
            )
        }

        // 标签设置
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.assistant_thinking_mode_tags),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.assistant_thinking_mode_tags_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = assistant.thinkingMode.startTag,
                        onValueChange = { value ->
                            onUpdate(
                                assistant.copy(
                                    thinkingMode = assistant.thinkingMode.copy(startTag = value)
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.assistant_thinking_mode_start_tag)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = assistant.thinkingMode.endTag,
                        onValueChange = { value ->
                            onUpdate(
                                assistant.copy(
                                    thinkingMode = assistant.thinkingMode.copy(endTag = value)
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.assistant_thinking_mode_end_tag)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }

        // 显示名称
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = assistant.thinkingMode.displayName,
                    onValueChange = { value ->
                        onUpdate(
                            assistant.copy(
                                thinkingMode = assistant.thinkingMode.copy(displayName = value)
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.assistant_thinking_mode_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = stringResource(R.string.assistant_thinking_mode_display_name_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 思考模式提示词
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val promptState = rememberTextFieldState(
                    initialText = assistant.thinkingMode.prompt,
                )
                LaunchedEffect(Unit) {
                    snapshotFlow { promptState.text }.collect {
                        onUpdate(
                            assistant.copy(
                                thinkingMode = assistant.thinkingMode.copy(prompt = it.toString())
                            )
                        )
                    }
                }

                TextArea(
                    state = promptState,
                    label = stringResource(R.string.assistant_thinking_mode_prompt),
                    minLines = 5,
                    maxLines = 10
                )
                Text(
                    text = stringResource(R.string.assistant_thinking_mode_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
