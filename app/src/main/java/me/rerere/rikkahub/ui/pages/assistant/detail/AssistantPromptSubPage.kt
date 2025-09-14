package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Fullscreen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.DefaultPlaceholderProvider
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun AssistantPromptSubPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val templateTransformer = koinInject<TemplateTransformer>()
    var isFocused by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }

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
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_system_prompt))
                },
            ) {
                val systemPromptValue = rememberTextFieldState(
                    initialText = assistant.systemPrompt,
                )
                LaunchedEffect(Unit) {
                    snapshotFlow { systemPromptValue.text }.collect {
                        onUpdate(
                            assistant.copy(
                                systemPrompt = it.toString()
                            )
                        )
                    }
                }
                OutlinedTextField(
                    state = systemPromptValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            isFocused = it.isFocused
                        },
                    trailingIcon = {
                        if (isFocused) {
                            IconButton(
                                onClick = {
                                    isFullScreen = !isFullScreen
                                }
                            ) {
                                Icon(Lucide.Fullscreen, null)
                            }
                        }
                    },
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 5,
                        maxHeightInLines = 10,
                    ),
                    textStyle = MaterialTheme.typography.bodySmall,
                )

                if (isFullScreen) {
                    FullScreenSystemPromptEditor(
                        systemPrompt = assistant.systemPrompt,
                        onUpdate = { newSystemPrompt ->
                            onUpdate(
                                assistant.copy(
                                    systemPrompt = newSystemPrompt
                                )
                            )
                        }
                    ) {
                        isFullScreen = false
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.assistant_page_available_variables),
                        style = MaterialTheme.typography.labelSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DefaultPlaceholderProvider.placeholders.forEach { (k, info) ->
                            Tag(
                                onClick = {
                                    systemPromptValue.insertAtCursor("{{$k}}")
                                }
                            ) {
                                info.displayName()
                                Text(": {{$k}}")
                            }
                        }
                    }
                }
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
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
                    .padding(8.dp)
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
                                ctx = TransformerContext(
                                    context = context,
                                    model = Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                                    assistant = assistant
                                ),
                                messages = rawMessages
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
                            onFork = {},
                            onRegenerate = {},
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onUpdate = {},
                            conversation = Conversation.ofId(Uuid.random())
                        )
                    }
                }
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_preset_messages))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_preset_messages_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                assistant.presetMessages.fastForEachIndexed { index, presetMessage ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Select(
                                options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                                selectedOption = presetMessage.role,
                                onOptionSelected = { role ->
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                                if (i == index) {
                                                    msg.copy(role = role)
                                                } else {
                                                    msg
                                                }
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.width(160.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onUpdate(
                                        assistant.copy(
                                            presetMessages = assistant.presetMessages.filterIndexed { i, _ ->
                                                i != index
                                            }
                                        )
                                    )
                                }
                            ) {
                                Icon(Lucide.X, null)
                            }
                        }
                        OutlinedTextField(
                            value = presetMessage.toText(),
                            onValueChange = { text ->
                                onUpdate(
                                    assistant.copy(
                                        presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                            if (i == index) {
                                                msg.copy(parts = listOf(UIMessagePart.Text(text)))
                                            } else {
                                                msg
                                            }
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6
                        )
                    }
                }
                Button(
                    onClick = {
                        val lastRole = assistant.presetMessages.lastOrNull()?.role ?: MessageRole.ASSISTANT
                        val nextRole = when (lastRole) {
                            MessageRole.USER -> MessageRole.ASSISTANT
                            MessageRole.ASSISTANT -> MessageRole.USER
                            else -> MessageRole.USER
                        }
                        onUpdate(
                            assistant.copy(
                                presetMessages = assistant.presetMessages + UIMessage(
                                    role = nextRole,
                                    parts = listOf(UIMessagePart.Text(""))
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.Plus, null)
                }
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_quick_messages))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_quick_messages_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                assistant.quickMessages.fastForEachIndexed { index, quickMessage ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = quickMessage.title,
                                onValueChange = { title ->
                                    onUpdate(
                                        assistant.copy(
                                            quickMessages = assistant.quickMessages.mapIndexed { i, msg ->
                                                if (i == index) {
                                                    msg.copy(title = title)
                                                } else {
                                                    msg
                                                }
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.assistant_page_quick_message_title)) }
                            )
                            IconButton(
                                onClick = {
                                    onUpdate(
                                        assistant.copy(
                                            quickMessages = assistant.quickMessages.filterIndexed { i, _ ->
                                                i != index
                                            }
                                        )
                                    )
                                }
                            ) {
                                Icon(Lucide.X, null)
                            }
                        }
                        OutlinedTextField(
                            value = quickMessage.content,
                            onValueChange = { text ->
                                onUpdate(
                                    assistant.copy(
                                        quickMessages = assistant.quickMessages.mapIndexed { i, msg ->
                                            if (i == index) {
                                                msg.copy(content = text)
                                            } else {
                                                msg
                                            }
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6,
                            label = { Text(stringResource(R.string.assistant_page_quick_message_content)) }
                        )
                    }
                }
                Button(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                quickMessages = assistant.quickMessages + QuickMessage()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.Plus, null)
                }
            }
        }

        Card {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_regex_title))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_regex_desc))
                }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                assistant.regexes.fastForEachIndexed { index, regex ->
                    AssistantRegexCard(
                        regex = regex,
                        onUpdate = onUpdate,
                        assistant = assistant,
                        index = index
                    )
                }
                Button(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes + AssistantRegex(
                                    id = Uuid.random()
                                )
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.Plus, null)
                }
            }
        }
    }
}

@Composable
private fun AssistantRegexCard(
    regex: AssistantRegex,
    onUpdate: (Assistant) -> Unit,
    assistant: Assistant,
    index: Int
) {
    var expanded by remember {
        mutableStateOf(false)
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
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(enabled = enabled)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
                IconButton(
                    onClick = {
                        expanded = !expanded
                    }
                ) {
                    Icon(
                        imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = null
                    )
                }
            }

            if (expanded) {

                OutlinedTextField(
                    value = regex.name,
                    onValueChange = { name ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(name = name)
                                    } else {
                                        reg
                                    }
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_name)) }
                )

                OutlinedTextField(
                    value = regex.findRegex,
                    onValueChange = { findRegex ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(findRegex = findRegex.trim())
                                    } else {
                                        reg
                                    }
                                }
                            )
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
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) {
                                        reg.copy(replaceString = replaceString)
                                    } else {
                                        reg
                                    }
                                }
                            )
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
                                            assistant.copy(
                                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                                    if (i == index) {
                                                        reg.copy(affectingScope = newScopes)
                                                    } else {
                                                        reg
                                                    }
                                                }
                                            )
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
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(visualOnly = visualOnly)
                                        } else {
                                            reg
                                        }
                                    }
                                )
                            )
                        }
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_regex_visual_only),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                TextButton(
                    onClick = {
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.filterIndexed { i, _ ->
                                    i != index
                                }
                            )
                        )
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Lucide.Trash2, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenSystemPromptEditor(
    systemPrompt: String,
    onUpdate: (String) -> Unit,
    onDone: () -> Unit
) {
    var editingText by remember(systemPrompt) { mutableStateOf(systemPrompt) }

    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onUpdate(editingText)
                                onDone()
                            }
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    }
                    TextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier
                            .imePadding()
                            .fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = {
                            Text(stringResource(R.string.assistant_page_system_prompt))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
