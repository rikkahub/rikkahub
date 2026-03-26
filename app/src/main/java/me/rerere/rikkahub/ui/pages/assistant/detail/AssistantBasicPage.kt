package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.rememberCommitOnFinishSliderState
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
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
        AssistantBasicContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            providers = providers,
            globalChatModelId = settings.chatModelId,
            tags = tags,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    providers: List<ProviderSetting>,
    globalChatModelId: Uuid?,
    tags: List<DataTag>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    val currentModelId = assistant.chatModelId ?: globalChatModelId
    val currentModel = providers
        .asSequence()
        .flatMap { it.models.asSequence() }
        .firstOrNull { it.id == currentModelId }
    val currentProvider = currentModel?.findProvider(providers)
    val openAIProvider = currentProvider as? ProviderSetting.OpenAI
    val hasProviderSpecificRequestParams = assistant.hasProviderSpecificRequestParams()
    val isGoogleProvider = currentProvider is ProviderSetting.Google
    val isClaudeProvider = currentProvider is ProviderSetting.Claude
    val showPresencePenaltyField = openAIProvider != null || isGoogleProvider || assistant.presencePenalty != null
    val showFrequencyPenaltyField = openAIProvider != null || isGoogleProvider || assistant.frequencyPenalty != null
    val showMinPField = openAIProvider != null || assistant.minP != null
    val showTopKField = openAIProvider != null || isGoogleProvider || isClaudeProvider || assistant.topK != null
    val showTopAField = openAIProvider != null || assistant.topA != null
    val showRepetitionPenaltyField = openAIProvider != null || assistant.repetitionPenalty != null
    val showStopSequencesField = (openAIProvider != null && !openAIProvider.useResponseApi) ||
        isGoogleProvider ||
        isClaudeProvider ||
        assistant.stopSequences.isNotEmpty()
    val showSeedField = openAIProvider != null || isGoogleProvider || assistant.seed != null
    val showGoogleResponseMimeTypeField = isGoogleProvider || assistant.googleResponseMimeType.isNotBlank()
    val showVerbosityField = openAIProvider != null || assistant.openAIVerbosity.isNotBlank()
    val showAdvancedRequestParams = showPresencePenaltyField ||
        showFrequencyPenaltyField ||
        showMinPField ||
        showTopKField ||
        showTopAField ||
        showRepetitionPenaltyField ||
        showStopSequencesField ||
        showSeedField ||
        showGoogleResponseMimeTypeField ||
        showVerbosityField
    val requestParamsDescription = when {
        openAIProvider != null && openAIProvider.useResponseApi ->
            "Responses API uses only verbosity. Other sampler fields below are kept for compatibility and only affect Chat Completions-compatible endpoints."
        openAIProvider != null ->
            "Sent as OpenAI/OpenAI-compatible request fields."
        isGoogleProvider ->
            "Sent inside generationConfig for Gemini/Vertex-compatible backends."
        isClaudeProvider ->
            "Sent as Anthropic Messages API fields."
        hasProviderSpecificRequestParams ->
            "Only fields supported by the selected backend will be used."
        else ->
            "Only fields supported by the selected backend will be used."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

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

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
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
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    val temperatureSliderState = rememberCommitOnFinishSliderState(assistant.temperature)
                    Slider(
                        value = temperatureSliderState.value,
                        onValueChange = temperatureSliderState::onValueChange,
                        onValueChangeFinished = {
                            temperatureSliderState.onValueChangeFinished(
                                externalValue = assistant.temperature,
                                onValueCommitted = {
                                    onUpdate(
                                        assistant.copy(
                                            temperature = it
                                        )
                                    )
                                },
                                normalize = {
                                    it.toFixed(2).toFloatOrNull() ?: 0.6f
                                }
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
                        val currentTemperature = temperatureSliderState.value
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
                                text = currentTemperature.toFixed(2)
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
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    val topPSliderState = rememberCommitOnFinishSliderState(topP)
                    Slider(
                        value = topPSliderState.value,
                        onValueChange = topPSliderState::onValueChange,
                        onValueChangeFinished = {
                            topPSliderState.onValueChangeFinished(
                                externalValue = topP,
                                onValueCommitted = {
                                    onUpdate(
                                        assistant.copy(
                                            topP = it
                                        )
                                    )
                                },
                                normalize = {
                                    it.toFixed(2).toFloatOrNull() ?: 1.0f
                                }
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_top_p_value,
                            topPSliderState.value.toFixed(2)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_size))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_desc),
                    )
                }
            ) {
                val contextMessageSizeSliderState = rememberCommitOnFinishSliderState(
                    assistant.contextMessageSize.toFloat()
                )
                Slider(
                    value = contextMessageSizeSliderState.value,
                    onValueChange = contextMessageSizeSliderState::onValueChange,
                    onValueChangeFinished = {
                        contextMessageSizeSliderState.onValueChangeFinished(
                            externalValue = assistant.contextMessageSize.toFloat(),
                            onValueCommitted = {
                                onUpdate(
                                    assistant.copy(
                                        contextMessageSize = it.toInt()
                                    )
                                )
                            },
                            normalize = {
                                it.roundToInt().coerceIn(0, 512).toFloat()
                            }
                        )
                    },
                    valueRange = 0f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
                val contextMessageSize = contextMessageSizeSliderState.value.toInt()

                Text(
                    text = if (contextMessageSize > 0) stringResource(
                        R.string.assistant_page_context_message_count,
                        contextMessageSize
                    ) else stringResource(R.string.assistant_page_context_message_unlimited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
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
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningTokens = assistant.thinkingBudget ?: 0,
                    onUpdateReasoningTokens = { tokens ->
                        onUpdate(
                            assistant.copy(
                                thinkingBudget = tokens
                            )
                        )
                    },
                    openAIReasoningEffort = assistant.openAIReasoningEffort,
                    onUpdateOpenAIReasoningEffort = { effort ->
                        onUpdate(
                            assistant.copy(
                                openAIReasoningEffort = effort
                            )
                        )
                    },
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }

            if (showAdvancedRequestParams) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Advanced request params",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = requestParamsDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (showPresencePenaltyField) {
                    HorizontalDivider()
                    OptionalFloatRequestParamField(
                        label = "Presence penalty",
                        value = assistant.presencePenalty,
                        description = "OpenAI/Google penalty for tokens that already appeared.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(presencePenalty = value))
                        }
                    )
                }
                if (showFrequencyPenaltyField) {
                    HorizontalDivider()
                    OptionalFloatRequestParamField(
                        label = "Frequency penalty",
                        value = assistant.frequencyPenalty,
                        description = "OpenAI/Google penalty based on repeated token frequency.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(frequencyPenalty = value))
                        }
                    )
                }
                if (showMinPField) {
                    HorizontalDivider()
                    OptionalFloatRequestParamField(
                        label = "Min P",
                        value = assistant.minP,
                        description = "Alternative sampling floor used by some OpenAI-compatible backends.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(minP = value))
                        }
                    )
                }
                if (showTopKField) {
                    HorizontalDivider()
                    OptionalIntRequestParamField(
                        label = "Top K",
                        value = assistant.topK,
                        description = "Candidate cutoff used by OpenAI-compatible, Google, and Claude-compatible backends.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(topK = value))
                        }
                    )
                }
                if (showTopAField) {
                    HorizontalDivider()
                    OptionalFloatRequestParamField(
                        label = "Top A",
                        value = assistant.topA,
                        description = "Adaptive sampling factor used by some OpenAI-compatible backends.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(topA = value))
                        }
                    )
                }
                if (showRepetitionPenaltyField) {
                    HorizontalDivider()
                    OptionalFloatRequestParamField(
                        label = "Repetition penalty",
                        value = assistant.repetitionPenalty,
                        description = "Discourage repeated tokens on compatible backends.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(repetitionPenalty = value))
                        }
                    )
                }
                if (showStopSequencesField) {
                    HorizontalDivider()
                    OptionalStringLinesRequestParamField(
                        label = "Stop sequences",
                        value = assistant.stopSequences,
                        description = "One per line. Supported by OpenAI Chat Completions, Google, and Claude.",
                        placeholder = "User:",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(stopSequences = value))
                        }
                    )
                }
                if (showSeedField) {
                    HorizontalDivider()
                    OptionalLongRequestParamField(
                        label = "Seed",
                        value = assistant.seed,
                        description = "Supported by OpenAI-compatible and Google-compatible backends. Leave empty for backend default.",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(seed = value))
                        }
                    )
                }
                if (showGoogleResponseMimeTypeField) {
                    HorizontalDivider()
                    OptionalStringRequestParamField(
                        label = "Response MIME type",
                        value = assistant.googleResponseMimeType,
                        description = "Google generationConfig.responseMimeType. Common values: text/plain, application/json.",
                        placeholder = "text/plain / application/json",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(googleResponseMimeType = value))
                        }
                    )
                }
                if (showVerbosityField) {
                    HorizontalDivider()
                    OptionalStringRequestParamField(
                        label = "Verbosity",
                        value = assistant.openAIVerbosity,
                        description = "OpenAI Responses API text verbosity. Use low, medium, or high.",
                        placeholder = "low / medium / high",
                        onValueChange = { value ->
                            onUpdate(assistant.copy(openAIVerbosity = value))
                        }
                    )
                }
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            BackgroundPicker(
                modifier = Modifier.padding(8.dp),
                background = assistant.background,
                backgroundOpacity = assistant.backgroundOpacity,
                backgroundBlur = assistant.backgroundBlur,
                onUpdate = { background ->
                    onUpdate(
                        assistant.copy(
                            background = background
                        )
                    )
                }
            )

            if (assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                val backgroundBlur = assistant.backgroundBlur.coerceIn(0f, 40f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    val backgroundOpacitySliderState = rememberCommitOnFinishSliderState(backgroundOpacity)
                    Slider(
                        value = backgroundOpacitySliderState.value,
                        onValueChange = backgroundOpacitySliderState::onValueChange,
                        onValueChangeFinished = {
                            backgroundOpacitySliderState.onValueChangeFinished(
                                externalValue = backgroundOpacity,
                                onValueCommitted = {
                                    onUpdate(
                                        assistant.copy(
                                            backgroundOpacity = it
                                        )
                                    )
                                },
                                normalize = {
                                    it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                }
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacitySliderState.value * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }

                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_blur))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_blur_desc))
                    }
                ) {
                    val backgroundBlurSliderState = rememberCommitOnFinishSliderState(backgroundBlur)
                    Slider(
                        value = backgroundBlurSliderState.value,
                        onValueChange = backgroundBlurSliderState::onValueChange,
                        onValueChangeFinished = {
                            backgroundBlurSliderState.onValueChangeFinished(
                                externalValue = backgroundBlur,
                                onValueCommitted = {
                                    onUpdate(
                                        assistant.copy(
                                            backgroundBlur = it
                                        )
                                    )
                                },
                                normalize = {
                                    it.toFixed(1).toFloatOrNull()?.coerceIn(0f, 40f) ?: 0f
                                }
                            )
                        },
                        valueRange = 0f..40f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_blur_value,
                            backgroundBlurSliderState.value.roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

private fun Assistant.hasProviderSpecificRequestParams(): Boolean {
    return frequencyPenalty != null ||
        presencePenalty != null ||
        minP != null ||
        topK != null ||
        topA != null ||
        repetitionPenalty != null ||
        seed != null ||
        stopSequences.isNotEmpty() ||
        googleResponseMimeType.isNotBlank() ||
        openAIVerbosity.isNotBlank()
}

@Composable
private fun OptionalFloatRequestParamField(
    label: String,
    value: Float?,
    description: String,
    onValueChange: (Float?) -> Unit,
) {
    ParsedRequestParamField(
        label = label,
        description = description,
        value = value,
        keyboardType = KeyboardType.Decimal,
        parse = { it.toFloatOrNull() },
        onValueChange = onValueChange,
    )
}

@Composable
private fun OptionalIntRequestParamField(
    label: String,
    value: Int?,
    description: String,
    onValueChange: (Int?) -> Unit,
) {
    ParsedRequestParamField(
        label = label,
        description = description,
        value = value,
        keyboardType = KeyboardType.Number,
        parse = { it.toIntOrNull() },
        onValueChange = onValueChange,
    )
}

@Composable
private fun OptionalLongRequestParamField(
    label: String,
    value: Long?,
    description: String,
    onValueChange: (Long?) -> Unit,
) {
    ParsedRequestParamField(
        label = label,
        description = description,
        value = value,
        keyboardType = KeyboardType.Number,
        parse = { it.toLongOrNull() },
        onValueChange = onValueChange,
    )
}

@Composable
private fun OptionalStringRequestParamField(
    label: String,
    value: String,
    description: String,
    placeholder: String = "Default",
    onValueChange: (String) -> Unit,
) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onValueChange(input)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(placeholder)
            }
        )
    }
}

@Composable
private fun OptionalStringLinesRequestParamField(
    label: String,
    value: List<String>,
    description: String,
    placeholder: String = "One per line",
    onValueChange: (List<String>) -> Unit,
) {
    val externalValue = value.joinToString("\n")
    var text by rememberSaveable(externalValue) { mutableStateOf(externalValue) }

    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onValueChange(
                    input.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(placeholder)
            },
            minLines = 3,
        )
    }
}

@Composable
private fun <T> ParsedRequestParamField(
    label: String,
    value: T?,
    description: String,
    keyboardType: KeyboardType,
    parse: (String) -> T?,
    onValueChange: (T?) -> Unit,
) {
    val externalValue = value?.toString().orEmpty()
    var text by rememberSaveable(externalValue) { mutableStateOf(externalValue) }

    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                when {
                    input.isBlank() -> onValueChange(null)
                    else -> parse(input)?.let(onValueChange)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Default")
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}
