package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.transformers.PlaceholderTransformer
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.TemplateTransformer
import me.rerere.rikkahub.data.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.chat.ChatMessage
import me.rerere.rikkahub.ui.components.chat.McpPicker
import me.rerere.rikkahub.ui.components.chat.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun AssistantDetailPage(vm: AssistantDetailVM = koinViewModel()) {
    val scope = rememberCoroutineScope()

    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            vm.addMemory(it)
        } else {
            vm.updateMemory(it)
        }
    }
    val providers by vm.providers.collectAsStateWithLifecycle()

    fun onUpdate(assistant: Assistant) {
        vm.update(assistant)
    }

    val tabs = listOf(
        stringResource(R.string.assistant_page_tab_basic),
        stringResource(R.string.assistant_page_tab_prompt),
        stringResource(R.string.assistant_page_tab_memory),
        stringResource(R.string.assistant_page_tab_request),
        "MCP"
    )
    val pagerState = rememberPagerState { tabs.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = assistant.name.ifBlank {
                            stringResource(R.string.assistant_page_default_assistant)
                        }
                    )
                },
                navigationIcon = {
                    BackButton()
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            SecondaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 24.dp,
            ) {
                tabs.fastForEachIndexed { index, tab ->
                    Tab(
                        selected = index == pagerState.currentPage,
                        onClick = { scope.launch { pagerState.scrollToPage(index) } },
                        text = {
                            Text(tab)
                        }
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> {
                        AssistantBasicSettings(
                            assistant = assistant,
                            providers = providers,
                        ) {
                            onUpdate(it)
                        }
                    }

                    1 -> {
                        AssistantPromptSettings(assistant = assistant) {
                            onUpdate(it)
                        }
                    }

                    2 -> {
                        AssistantMemorySettings(
                            assistant = assistant,
                            memories = memories,
                            onUpdate = { onUpdate(it) },
                            onAddMemory = { memoryDialogState.open(AssistantMemory(0, "")) },
                            onEditMemory = { memoryDialogState.open(it) },
                            onDeleteMemory = { vm.deleteMemory(it) }
                        )
                    }

                    3 -> {
                        AssistantCustomRequestSettings(assistant = assistant) {
                            onUpdate(it)
                        }
                    }

                    4 -> {
                        AssistantMcpSettings(
                            assistant = assistant,
                            onUpdate = {
                                onUpdate(it)
                            },
                            mcpServerConfigs = mcpServerConfigs
                        )
                    }
                }
            }
        }
    }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 1,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun AssistantBasicSettings(
    assistant: Assistant,
    providers: List<ProviderSetting>,
    onUpdate: (Assistant) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(16.dp),
            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
            ) {
                Slider(
                    value = assistant.temperature,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                temperature = it.toFixed(2).toFloatOrNull() ?: 0.6f
                            )
                        )
                    },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentTemperature = assistant.temperature
                    val tagType = when (currentTemperature) {
                        in 0.0f..0.3f -> TagType.INFO
                        in 0.3f..1.0f -> TagType.SUCCESS
                        in 1.0f..1.5f -> TagType.WARNING
                        in 1.5f..2.0f -> TagType.ERROR
                        else -> TagType.ERROR
                    }
                    Tag(
                        type = TagType.INFO
                    ) {
                        Text(
                            text = "$currentTemperature"
                        )
                    }

                    Tag(
                        type = tagType
                    ) {
                        Text(
                            text = when (currentTemperature) {
                                in 0.0f..0.3f -> stringResource(R.string.assistant_page_strict)
                                in 0.3f..1.0f -> stringResource(R.string.assistant_page_balanced)
                                in 1.0f..1.5f -> stringResource(R.string.assistant_page_creative)
                                in 1.5f..2.0f -> stringResource(R.string.assistant_page_chaotic)
                                else -> "?"
                            }
                        )
                    }
                }
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                }
            ) {
                Slider(
                    value = assistant.topP,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                topP = it.toFixed(2).toFloatOrNull() ?: 1.0f
                            )
                        )
                    },
                    valueRange = 0f..1f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.assistant_page_top_p_value,
                        assistant.topP.toString()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_size))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_desc),
                    )
                }
            ) {
                Slider(
                    value = assistant.contextMessageSize.toFloat(),
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                contextMessageSize = it.roundToInt()
                            )
                        )
                    },
                    valueRange = 1f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.assistant_page_context_message_count,
                        assistant.contextMessageSize
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_thinking_budget_desc))
                    Text(
                        text = stringResource(R.string.assistant_page_thinking_budget_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            ) {
                var input by remember(assistant) {
                    mutableStateOf(assistant.thinkingBudget?.toString() ?: "")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            onUpdate(
                                assistant.copy(
                                    thinkingBudget = if (it.isBlank()) null else it.toIntOrNull()
                                )
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    input = ""
                                    onUpdate(
                                        assistant.copy(
                                            thinkingBudget = null
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    Lucide.X,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_thinking_budget_tokens,
                            assistant.thinkingBudget?.toString()
                                ?: stringResource(R.string.assistant_page_thinking_budget_default)
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantPromptSettings(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val templateTransformer = koinInject<TemplateTransformer>()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_system_prompt))
                },
            ) {
                OutlinedTextField(
                    value = assistant.systemPrompt,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                systemPrompt = it
                            )
                        )
                    },
                    minLines = 6,
                    maxLines = 15,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.assistant_page_available_variables))
                        PlaceholderTransformer.Placeholders.entries.forEach { (k, v) ->
                            append(v)
                            append(": ")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = k,
                                    linkInteractionListener = {
                                        onUpdate(
                                            assistant.copy(
                                                systemPrompt = assistant.systemPrompt + k
                                            )
                                        )
                                    }
                                )) {
                                withStyle(SpanStyle(color = MaterialTheme.extendColors.blue6)) {
                                    append(k)
                                }
                            }
                            append(", ")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_message_template))
                },
                content = {
                    OutlinedTextField(
                        value = assistant.messageTemplate,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    messageTemplate = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 15,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = JetbrainsMono,
                            lineHeight = 16.sp
                        )
                    )
                },
                description = {
                    Text(stringResource(R.string.assistant_page_message_template_desc))
                    Text(buildAnnotatedString {
                        append(stringResource(R.string.assistant_page_template_variables_label))
                        append(" ")
                        append(stringResource(R.string.assistant_page_template_variable_role))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ role }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_message))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ message }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_time))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ time }}")
                        }
                        append(", ")
                        append(stringResource(R.string.assistant_page_template_variable_date))
                        append(": ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("{{ date }}")
                        }
                    })
                }
            )
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_template_preview),
                    style = MaterialTheme.typography.titleSmall
                )
                val rawMessages = listOf(
                    UIMessage.user("你好啊"),
                    UIMessage.assistant("你好，有什么我可以帮你的吗？"),
                )
                val preview by produceState<UiState<List<UIMessage>>>(
                    UiState.Success(rawMessages),
                    assistant
                ) {
                    value = runCatching {
                        UiState.Success(
                            templateTransformer.transform(
                                context = context,
                                messages = rawMessages,
                                model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
                            )
                        )
                    }.getOrElse {
                        UiState.Error(it)
                    }
                }
                preview.onError {
                    Text(
                        text = it.message ?: it.javaClass.name,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                preview.onSuccess {
                    it.fastForEach { message ->
                        ChatMessage(
                            node = message.toMessageNode(),
                            showActions = true,
                            onClick = {}, 
                            onFork = {},
                            onRegenerate = {},
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onUpdate = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMemorySettings(
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdate: (Assistant) -> Unit,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            FormItem(
                modifier = Modifier.padding(16.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_memory))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = onAddMemory,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Lucide.Plus,
                    contentDescription = null
                )
            }
        }

        memories.forEach { memory ->
            key(memory.id) {
                MemoryItem(memory, onEditMemory, onDeleteMemory)
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = memory.content,
                modifier = Modifier.weight(1f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(Lucide.Pencil, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    Lucide.Trash2,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}

@Composable
private fun AssistantCustomRequestSettings(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AssistantCustomHeaders(assistant = assistant, onUpdate = onUpdate)

        HorizontalDivider()

        AssistantCustomBodies(assistant = assistant, onUpdate = onUpdate)
    }
}

@Composable
private fun AssistantMcpSettings(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    mcpServerConfigs: List<McpServerConfig>
) {
    McpPicker(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        assistant = assistant,
        servers = mcpServerConfigs,
        onUpdateAssistant = onUpdate,
    )
}