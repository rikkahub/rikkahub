package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
import me.rerere.rikkahub.ui.components.sampling.SamplingRequestFieldGroups
import me.rerere.rikkahub.ui.components.ui.FormItem
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
    val requestParamsDescription = when {
        openAIProvider != null && openAIProvider.useResponseApi ->
            stringResource(R.string.assistant_page_sampling_desc_responses_api)
        openAIProvider != null ->
            stringResource(R.string.assistant_page_sampling_desc_openai)
        isGoogleProvider ->
            stringResource(R.string.assistant_page_sampling_desc_google)
        isClaudeProvider ->
            stringResource(R.string.assistant_page_sampling_desc_claude)
        hasProviderSpecificRequestParams ->
            stringResource(R.string.assistant_page_sampling_desc_compat)
        else ->
            stringResource(R.string.assistant_page_sampling_desc_compat)
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
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_sampling_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = requestParamsDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SamplingRequestFieldGroups(
                    temperature = assistant.temperature,
                    onTemperatureChange = { onUpdate(assistant.copy(temperature = it)) },
                    topP = assistant.topP,
                    onTopPChange = { onUpdate(assistant.copy(topP = it)) },
                    showTopK = showTopKField,
                    topK = assistant.topK,
                    onTopKChange = { onUpdate(assistant.copy(topK = it)) },
                    maxTokens = assistant.maxTokens,
                    onMaxTokensChange = { value ->
                        onUpdate(assistant.copy(maxTokens = value?.takeIf { it > 0 }))
                    },
                    showPresencePenalty = showPresencePenaltyField,
                    presencePenalty = assistant.presencePenalty,
                    onPresencePenaltyChange = { onUpdate(assistant.copy(presencePenalty = it)) },
                    showFrequencyPenalty = showFrequencyPenaltyField,
                    frequencyPenalty = assistant.frequencyPenalty,
                    onFrequencyPenaltyChange = { onUpdate(assistant.copy(frequencyPenalty = it)) },
                    showRepetitionPenalty = showRepetitionPenaltyField,
                    repetitionPenalty = assistant.repetitionPenalty,
                    onRepetitionPenaltyChange = { onUpdate(assistant.copy(repetitionPenalty = it)) },
                    showMinP = showMinPField,
                    minP = assistant.minP,
                    onMinPChange = { onUpdate(assistant.copy(minP = it)) },
                    showTopA = showTopAField,
                    topA = assistant.topA,
                    onTopAChange = { onUpdate(assistant.copy(topA = it)) },
                    showStopSequences = showStopSequencesField,
                    stopSequences = assistant.stopSequences,
                    onStopSequencesChange = { onUpdate(assistant.copy(stopSequences = it)) },
                    showSeed = showSeedField,
                    seed = assistant.seed,
                    onSeedChange = { onUpdate(assistant.copy(seed = it)) },
                    showGoogleResponseMimeType = showGoogleResponseMimeTypeField,
                    googleResponseMimeType = assistant.googleResponseMimeType,
                    onGoogleResponseMimeTypeChange = { onUpdate(assistant.copy(googleResponseMimeType = it)) },
                    showVerbosity = showVerbosityField,
                    verbosity = assistant.openAIVerbosity,
                    onVerbosityChange = { onUpdate(assistant.copy(openAIVerbosity = it)) },
                )
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
