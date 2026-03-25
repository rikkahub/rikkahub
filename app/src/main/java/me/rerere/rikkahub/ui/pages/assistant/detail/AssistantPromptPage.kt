package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.DefaultPlaceholderProvider
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.effectiveUserPersona
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.hasExplicitPromptOrder
import me.rerere.rikkahub.data.model.resolvePromptOrder
import me.rerere.rikkahub.data.model.selectedUserPersonaProfile
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withPromptOrder
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.hooks.rememberDebouncedTextState
import me.rerere.rikkahub.ui.pages.extensions.RegexEditorSection
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalThemeTokenOverrides
import me.rerere.rikkahub.ui.theme.ThemeTokenTextScaleGroup
import me.rerere.rikkahub.ui.theme.applyThemeTokenTextScale
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_prompt))
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
        AssistantPromptContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            settings = settings,
            onUpdate = { vm.update(it) },
            onUpdateSettings = { updatedSettings, oldAssistant, newAssistant ->
                vm.updateSettings(
                    settings = updatedSettings,
                    oldAssistant = oldAssistant,
                    newAssistant = newAssistant,
                )
            },
            onUpdateWithLorebooks = { updatedAssistant, lorebooks ->
                vm.updateWithLorebooks(updatedAssistant, lorebooks)
            },
        )
    }
}

@Composable
private fun AssistantPromptContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    onUpdateSettings: (Settings, Assistant?, Assistant?) -> Unit,
    onUpdateWithLorebooks: (Assistant, List<me.rerere.rikkahub.data.model.Lorebook>) -> Unit,
) {
    val context = LocalContext.current
    val templateTransformer = koinInject<TemplateTransformer>()
    val themeTokens = LocalThemeTokenOverrides.current
    val latestAssistant by rememberUpdatedState(assistant)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val latestSettings by rememberUpdatedState(settings)
    val latestOnUpdateSettings by rememberUpdatedState(onUpdateSettings)
    val latestOnUpdateWithLorebooks by rememberUpdatedState(onUpdateWithLorebooks)
    val selectedPersonaProfile = settings.selectedUserPersonaProfile()
    val effectiveUserPersona = settings.effectiveUserPersona(assistant)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SillyTavern 导入",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "可导入预设 JSON、角色卡 PNG/JSON，以及角色卡内嵌世界书。预设会写入全局 ST 预设，角色卡与配套 lorebook/regex 仍按当前助手合并。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AssistantImporter(
                    onImport = { payload, includeRegexes ->
                        val application = applyImportedAssistantToExisting(
                            currentAssistant = assistant,
                            payload = payload,
                            existingLorebooks = settings.lorebooks,
                            existingGlobalRegexes = settings.regexes,
                            includeRegexes = includeRegexes,
                        )
                        val importedLorebooks = application.lorebooks.filter { imported ->
                            latestSettings.lorebooks.none { it.id == imported.id }
                        }
                        val nextAssistant = when (payload.kind) {
                            AssistantImportKind.PRESET -> application.assistant.copy(
                                stPromptTemplate = latestAssistant.stPromptTemplate,
                            )

                            AssistantImportKind.CHARACTER_CARD -> application.assistant
                        }
                        val shouldSeedGlobalPreset = when (payload.kind) {
                            AssistantImportKind.PRESET -> true
                            AssistantImportKind.CHARACTER_CARD -> latestSettings.stPresetTemplate == null
                        }
                        val nextGlobalPreset = if (shouldSeedGlobalPreset) {
                            payload.assistant.stPromptTemplate ?: latestSettings.stPresetTemplate
                        } else {
                            latestSettings.stPresetTemplate
                        }
                        latestOnUpdateSettings(
                            latestSettings.copy(
                                lorebooks = latestSettings.lorebooks + importedLorebooks,
                                assistants = latestSettings.assistants.map { existing ->
                                    if (existing.id == nextAssistant.id) {
                                        nextAssistant
                                    } else {
                                        existing
                                    }
                                },
                                stPresetEnabled = when {
                                    payload.kind == AssistantImportKind.PRESET && nextGlobalPreset != null -> true
                                    shouldSeedGlobalPreset && nextGlobalPreset != null -> true
                                    else -> latestSettings.stPresetEnabled
                                },
                                regexes = application.globalRegexes,
                                stPresetTemplate = nextGlobalPreset,
                            ),
                            latestAssistant,
                            nextAssistant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (settings.stPresetTemplate != null || assistant.stCharacterData != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "当前运行时映射",
                            style = MaterialTheme.typography.labelLarge
                        )
                        settings.stPresetTemplate?.let { template ->
                            Text(
                                text = "全局预设: ${template.sourceName.ifBlank { "SillyTavern" }}${if (settings.stPresetEnabled) "" else "（已关闭）"}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        assistant.stCharacterData?.let { character ->
                            Text(
                                text = "角色卡: ${character.sourceName.ifBlank { character.name.ifBlank { "SillyTavern" } }}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (selectedPersonaProfile != null) {
                            Text(
                                text = "全局 Persona: ${selectedPersonaProfile.name.ifBlank { "未命名 Persona" }}${if (effectiveUserPersona.isBlank()) "（内容为空）" else ""}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else if (effectiveUserPersona.isNotBlank()) {
                            Text(
                                text = "全局 Persona: 兼容回退旧助手 Persona",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "全局 Regex ${settings.regexes.size} 条，助手 Regex ${assistant.regexes.size} 条，关联世界书 ${assistant.lorebookIds.size} 本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (assistant.stCharacterData != null) {
                                TextButton(
                                    onClick = {
                                        latestOnUpdate(
                                            latestAssistant.copy(
                                                stCharacterData = null
                                            )
                                        )
                                    }
                                ) {
                                    Text("清除角色卡信息")
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val systemPromptValue = rememberTextFieldState(
                    initialText = assistant.systemPrompt,
                )
                var lastExternalSystemPrompt by remember { mutableStateOf(assistant.systemPrompt) }
                var lastDispatchedSystemPrompt by remember { mutableStateOf(assistant.systemPrompt) }
                val systemPromptText = systemPromptValue.text.toString()

                LaunchedEffect(assistant.systemPrompt) {
                    val currentText = systemPromptValue.text.toString()
                    val shouldSyncText =
                        currentText == lastExternalSystemPrompt ||
                            assistant.systemPrompt != lastDispatchedSystemPrompt ||
                            currentText == assistant.systemPrompt
                    lastExternalSystemPrompt = assistant.systemPrompt

                    if (shouldSyncText && currentText != assistant.systemPrompt) {
                        systemPromptValue.edit {
                            replace(0, length, assistant.systemPrompt)
                        }
                    }
                    if (shouldSyncText) {
                        lastDispatchedSystemPrompt = assistant.systemPrompt
                    }
                }
                LaunchedEffect(systemPromptText) {
                    if (
                        systemPromptText == latestAssistant.systemPrompt ||
                        systemPromptText == lastDispatchedSystemPrompt
                    ) {
                        return@LaunchedEffect
                    }
                    delay(400)
                    val latestText = systemPromptValue.text.toString()
                    if (
                        latestText == systemPromptText &&
                        latestText != latestAssistant.systemPrompt &&
                        latestText != lastDispatchedSystemPrompt
                    ) {
                        lastDispatchedSystemPrompt = latestText
                        latestOnUpdate(
                            latestAssistant.copy(
                                systemPrompt = latestText
                            )
                        )
                    }
                }
                DisposableEffect(Unit) {
                    onDispose {
                        val currentText = systemPromptValue.text.toString()
                        if (
                            currentText != latestAssistant.systemPrompt &&
                            currentText != lastDispatchedSystemPrompt
                        ) {
                            lastDispatchedSystemPrompt = currentText
                            latestOnUpdate(
                                latestAssistant.copy(
                                    systemPrompt = currentText
                                )
                            )
                        }
                    }
                }

                TextArea(
                    state = systemPromptValue,
                    label = stringResource(R.string.assistant_page_system_prompt),
                    minLines = 5,
                    maxLines = 10
                )

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

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            val messageTemplateState = rememberDebouncedTextState(
                value = assistant.messageTemplate,
                onDebouncedValueChange = { value ->
                    latestOnUpdate(
                        latestAssistant.copy(
                            messageTemplate = value
                        )
                    )
                }
            )
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_message_template))
                },
                content = {
                    OutlinedTextField(
                        value = messageTemplateState.value,
                        onValueChange = { messageTemplateState.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 15,
                        textStyle = themeTokens.applyThemeTokenTextScale(
                            style = TextStyle(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontFamily = JetbrainsMono,
                            ),
                            group = ThemeTokenTextScaleGroup.BODY,
                        ).copy(
                            fontFamily = JetbrainsMono,
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
                                    assistant = assistant,
                                    settings = settings
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
                            onContinue = {},
                            onEdit = {},
                            onShare = {},
                            onDelete = {},
                            onUpdate = {},
                        )
                    }
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
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
                                Icon(HugeIcons.Cancel01, null)
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
                    Icon(HugeIcons.Add01, null)
                }
            }
        }

        RegexEditorSection(
            regexes = assistant.regexes,
            onUpdate = { regexes ->
                onUpdate(
                    assistant.copy(regexes = regexes)
                )
            },
            title = "助手级 Regex",
            description = "仅对当前助手生效。适合角色卡自带的格式化、美化和卡片专属规则。",
        )
    }
}

@Composable
private fun SillyTavernPresetEditorCard(
    assistant: Assistant,
    template: SillyTavernPromptTemplate,
    onUpdate: (Assistant) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedPromptIds by remember { mutableStateOf(setOf<String>()) }
    val editorTemplate = remember(template) {
        normalizeSillyTavernTemplateForEditor(template)
    }
    val promptOrder = editorTemplate.resolvePromptOrder()
    val enabledPromptCount = promptOrder.count { it.enabled }
    val missingDefaultPrompts = remember(editorTemplate) {
        defaultSillyTavernPromptTemplate().prompts.filter { prompt ->
            editorTemplate.findPrompt(prompt.identifier) == null
        }
    }

    fun updateTemplate(transform: (SillyTavernPromptTemplate) -> SillyTavernPromptTemplate) {
        val currentTemplate = assistant.stPromptTemplate ?: template
        onUpdate(
            assistant.copy(
                stPromptTemplate = transform(normalizeSillyTavernTemplateForEditor(currentTemplate))
            )
        )
    }

    Card(
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "SillyTavern 预设编辑",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "共 ${promptOrder.size} 项，已启用 $enabledPromptCount 项。这里按 ST 的模板字段、prompt order 和 prompt definitions 组织。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            Text(
                text = "模板与格式",
                style = MaterialTheme.typography.labelLarge
            )

            OutlinedTextField(
                value = editorTemplate.sourceName,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(sourceName = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("预设名称") },
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
                label = { Text("Scenario 格式") },
            )

            OutlinedTextField(
                value = editorTemplate.personalityFormat,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(personalityFormat = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Personality 格式") },
            )

            OutlinedTextField(
                value = editorTemplate.wiFormat,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(wiFormat = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("World Info 格式") },
            )

            Text(
                text = "运行时选项",
                style = MaterialTheme.typography.labelLarge
            )

            StBooleanSettingRow(
                title = "复用系统提示词",
                checked = editorTemplate.useSystemPrompt,
                onCheckedChange = { checked ->
                    updateTemplate { current ->
                        current.copy(useSystemPrompt = checked)
                    }
                }
            )

            StBooleanSettingRow(
                title = "压缩连续 system 消息",
                checked = editorTemplate.squashSystemMessages,
                onCheckedChange = { checked ->
                    updateTemplate { current ->
                        current.copy(squashSystemMessages = checked)
                    }
                }
            )

            StBooleanSettingRow(
                title = "Continue 时使用 assistant prefill",
                checked = editorTemplate.continuePrefill,
                onCheckedChange = { checked ->
                    updateTemplate { current ->
                        current.copy(continuePrefill = checked)
                    }
                }
            )

            OutlinedTextField(
                value = editorTemplate.newChatPrompt,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(newChatPrompt = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新聊天提示") },
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
                label = { Text("新群聊提示") },
                minLines = 2,
            )

            OutlinedTextField(
                value = editorTemplate.newExampleChatPrompt,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(newExampleChatPrompt = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("示例聊天提示") },
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
                label = { Text("Continue Nudge Prompt") },
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
                label = { Text("Group Nudge Prompt") },
                minLines = 2,
            )

            OutlinedTextField(
                value = editorTemplate.impersonationPrompt,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(impersonationPrompt = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Impersonation Prompt") },
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
                label = { Text("Assistant Prefill") },
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
                label = { Text("Assistant Impersonation") },
                minLines = 2,
            )

            OutlinedTextField(
                value = editorTemplate.continuePostfix,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(continuePostfix = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Continue Postfix") },
            )

            OutlinedTextField(
                value = editorTemplate.sendIfEmpty,
                onValueChange = { value ->
                    updateTemplate { current ->
                        current.copy(sendIfEmpty = value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Send If Empty") },
                minLines = 2,
            )

            Text(
                text = "Prompt 顺序与定义",
                style = MaterialTheme.typography.labelLarge
            )

            if (missingDefaultPrompts.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "快速补回常见段落",
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

            promptOrder.fastForEachIndexed { index, orderItem ->
                val prompt = editorTemplate.findPrompt(orderItem.identifier)
                    ?: defaultStPromptDefinition(orderItem.identifier)
                val expandedPrompt = orderItem.identifier in expandedPromptIds

                SillyTavernPromptCard(
                    prompt = prompt,
                    orderItem = orderItem,
                    expanded = expandedPrompt,
                    canMoveUp = index > 0,
                    canMoveDown = index < promptOrder.lastIndex,
                    onToggleExpanded = {
                        expandedPromptIds = if (expandedPrompt) {
                            expandedPromptIds - orderItem.identifier
                        } else {
                            expandedPromptIds + orderItem.identifier
                        }
                    },
                    onEnabledChange = { enabled ->
                        updateTemplate { current ->
                            updateStPromptOrderEnabled(current, orderItem.identifier, enabled)
                        }
                    },
                    onMoveUp = {
                        updateTemplate { current ->
                            moveStPromptOrder(current, orderItem.identifier, -1)
                        }
                    },
                    onMoveDown = {
                        updateTemplate { current ->
                            moveStPromptOrder(current, orderItem.identifier, 1)
                        }
                    },
                    onRenameIdentifier = { newIdentifier ->
                        updateTemplate { current ->
                            renameStPromptIdentifier(current, orderItem.identifier, newIdentifier)
                        }
                    },
                    onUpdatePrompt = { updatedPrompt ->
                        updateTemplate { current ->
                            updateStPrompt(current, orderItem.identifier) {
                                updatedPrompt
                            }
                        }
                    },
                    onDelete = {
                        expandedPromptIds -= orderItem.identifier
                        updateTemplate { current ->
                            removeStPromptDefinition(current, orderItem.identifier)
                        }
                    }
                )
            }

            Button(
                onClick = {
                    updateTemplate { current ->
                        appendStPromptDefinition(
                            template = current,
                            prompt = buildCustomStPrompt(current),
                            enabled = true,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(HugeIcons.Add01, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加自定义提示项")
            }
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
private fun SillyTavernPromptCard(
    prompt: SillyTavernPromptItem,
    orderItem: SillyTavernPromptOrderItem,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRenameIdentifier: (String) -> Unit,
    onUpdatePrompt: (SillyTavernPromptItem) -> Unit,
    onDelete: () -> Unit,
) {
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stPromptDisplayName(prompt),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Tag { Text(prompt.identifier) }
                        Tag(type = TagType.INFO) { Text(stRoleLabel(prompt.role)) }
                        if (prompt.marker) {
                            Tag(type = TagType.WARNING) { Text("Marker") }
                        }
                        if (prompt.systemPrompt) {
                            Tag(type = TagType.INFO) { Text("System Prompt") }
                        }
                        if (prompt.injectionPosition == StPromptInjectionPosition.ABSOLUTE) {
                            Tag(type = TagType.WARNING) { Text("Absolute") }
                        }
                        if (prompt.injectionTriggers.isNotEmpty()) {
                            Tag(type = TagType.SUCCESS) {
                                Text(prompt.injectionTriggers.joinToString(" / "))
                            }
                        }
                    }
                }

                Switch(
                    checked = orderItem.enabled,
                    onCheckedChange = onEnabledChange
                )

                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp
                ) {
                    Icon(HugeIcons.ArrowUp01, null)
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown
                ) {
                    Icon(HugeIcons.ArrowDown01, null)
                }

                IconButton(
                    onClick = onToggleExpanded
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
                value = prompt.identifier,
                onValueChange = onRenameIdentifier,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Identifier") },
                singleLine = true,
            )

            OutlinedTextField(
                value = prompt.name,
                onValueChange = { value ->
                    onUpdatePrompt(prompt.copy(name = value))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                singleLine = true,
            )

            Select(
                options = MessageRole.entries.toList(),
                selectedOption = prompt.role,
                onOptionSelected = { role ->
                    onUpdatePrompt(prompt.copy(role = role))
                },
                modifier = Modifier.fillMaxWidth(),
                optionToString = { stRoleLabel(it) }
            )

            Select(
                options = StPromptInjectionPosition.entries.toList(),
                selectedOption = prompt.injectionPosition,
                onOptionSelected = { position ->
                    onUpdatePrompt(prompt.copy(injectionPosition = position))
                },
                modifier = Modifier.fillMaxWidth(),
                optionToString = { stInjectionPositionLabel(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = prompt.injectionDepth.toString(),
                    onValueChange = { value ->
                        val depth = value.toIntOrNull() ?: return@OutlinedTextField
                        onUpdatePrompt(prompt.copy(injectionDepth = depth))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Depth") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = prompt.injectionOrder.toString(),
                    onValueChange = { value ->
                        val order = value.toIntOrNull() ?: return@OutlinedTextField
                        onUpdatePrompt(prompt.copy(injectionOrder = order))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Order") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "触发类型",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stPromptGenerationTypes.fastForEach { trigger ->
                        val selected = trigger in prompt.injectionTriggers
                        Tag(
                            type = if (selected) TagType.INFO else TagType.DEFAULT,
                            onClick = {
                                val updatedTriggers = if (selected) {
                                    prompt.injectionTriggers - trigger
                                } else {
                                    (prompt.injectionTriggers + trigger).distinct()
                                }
                                onUpdatePrompt(prompt.copy(injectionTriggers = updatedTriggers))
                            }
                        ) {
                            Text(stPromptGenerationTypeLabel(trigger))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StCheckboxField(
                    label = "System Prompt",
                    checked = prompt.systemPrompt,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(systemPrompt = checked))
                    }
                )
                StCheckboxField(
                    label = "Marker",
                    checked = prompt.marker,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(marker = checked))
                    }
                )
                StCheckboxField(
                    label = "禁止覆盖",
                    checked = prompt.forbidOverrides,
                    onCheckedChange = { checked ->
                        onUpdatePrompt(prompt.copy(forbidOverrides = checked))
                    }
                )
            }

            OutlinedTextField(
                value = prompt.content,
                onValueChange = { value ->
                    onUpdatePrompt(prompt.copy(content = value))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt 内容") },
                minLines = 4,
                maxLines = 10,
            )

            TextButton(
                onClick = onDelete
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(HugeIcons.Delete01, null)
                    Text("删除该项")
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
    identifier: String,
    delta: Int,
): SillyTavernPromptTemplate {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    val order = normalized.resolvePromptOrder().toMutableList()
    val currentIndex = order.indexOfFirst { it.identifier == identifier }
    if (currentIndex < 0) return normalized
    val targetIndex = (currentIndex + delta).coerceIn(0, order.lastIndex)
    if (targetIndex == currentIndex) return normalized
    val item = order.removeAt(currentIndex)
    order.add(targetIndex, item)
    return normalized.withPromptOrder(order)
}

private fun buildCustomStPrompt(template: SillyTavernPromptTemplate): SillyTavernPromptItem {
    val normalized = normalizeSillyTavernTemplateForEditor(template)
    var index = 1
    var identifier = "customPrompt$index"
    while (normalized.findPrompt(identifier) != null) {
        index++
        identifier = "customPrompt$index"
    }
    return SillyTavernPromptItem(
        identifier = identifier,
        name = "Custom Prompt $index",
        role = MessageRole.SYSTEM,
        systemPrompt = true,
    )
}

private fun defaultStPromptDefinition(identifier: String): SillyTavernPromptItem {
    return defaultSillyTavernPromptTemplate().findPrompt(identifier)
        ?: SillyTavernPromptItem(
            identifier = identifier,
            name = identifier.replaceFirstChar { it.uppercase() },
            role = MessageRole.SYSTEM,
            systemPrompt = true,
        )
}

private fun stPromptDisplayName(prompt: SillyTavernPromptItem): String {
    return prompt.name.ifBlank { defaultStPromptDefinition(prompt.identifier).name.ifBlank { prompt.identifier } }
}

private fun stRoleLabel(role: MessageRole): String {
    return when (role) {
        MessageRole.SYSTEM -> "System"
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        else -> role.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun stInjectionPositionLabel(position: StPromptInjectionPosition): String {
    return when (position) {
        StPromptInjectionPosition.RELATIVE -> "Relative"
        StPromptInjectionPosition.ABSOLUTE -> "Absolute"
    }
}

private fun stPromptGenerationTypeLabel(trigger: String): String {
    return when (trigger) {
        "normal" -> "Normal"
        "continue" -> "Continue"
        "quiet" -> "Quiet"
        "impersonate" -> "Impersonate"
        else -> trigger
    }
}

private val stPromptGenerationTypes = listOf(
    "normal",
    "continue",
    "quiet",
    "impersonate",
)

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
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
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
                                            reg.copy(
                                                visualOnly = visualOnly,
                                                promptOnly = if (visualOnly) false else reg.promptOnly
                                            )
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = regex.promptOnly,
                        onCheckedChange = { promptOnly ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
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
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(minDepth = minDepth)
                                        } else {
                                            reg
                                        }
                                    }
                                )
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
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) {
                                            reg.copy(maxDepth = maxDepth)
                                        } else {
                                            reg
                                        }
                                    }
                                )
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
                        Icon(HugeIcons.Delete01, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}
