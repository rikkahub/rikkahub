package me.rerere.rikkahub.ui.components.sampling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.rememberCommitOnFinishSliderState
import me.rerere.rikkahub.utils.toFixed
import kotlin.math.roundToInt

@Composable
internal fun SamplingRequestFieldGroups(
    temperature: Float?,
    onTemperatureChange: (Float?) -> Unit,
    topP: Float?,
    onTopPChange: (Float?) -> Unit,
    showTopK: Boolean,
    topK: Int?,
    onTopKChange: (Int?) -> Unit,
    maxTokens: Int?,
    onMaxTokensChange: (Int?) -> Unit,
    showPresencePenalty: Boolean,
    presencePenalty: Float?,
    onPresencePenaltyChange: (Float?) -> Unit,
    showFrequencyPenalty: Boolean,
    frequencyPenalty: Float?,
    onFrequencyPenaltyChange: (Float?) -> Unit,
    showRepetitionPenalty: Boolean,
    repetitionPenalty: Float?,
    onRepetitionPenaltyChange: (Float?) -> Unit,
    showMinP: Boolean,
    minP: Float?,
    onMinPChange: (Float?) -> Unit,
    showTopA: Boolean,
    topA: Float?,
    onTopAChange: (Float?) -> Unit,
    showStopSequences: Boolean,
    stopSequences: List<String>,
    onStopSequencesChange: (List<String>) -> Unit,
    showSeed: Boolean,
    seed: Long?,
    onSeedChange: (Long?) -> Unit,
    showGoogleResponseMimeType: Boolean = false,
    googleResponseMimeType: String = "",
    onGoogleResponseMimeTypeChange: (String) -> Unit = {},
    showReasoningEffort: Boolean = false,
    reasoningEffort: String = "",
    onReasoningEffortChange: (String) -> Unit = {},
    showVerbosity: Boolean = false,
    verbosity: String = "",
    onVerbosityChange: (String) -> Unit = {},
) {
    val strictLabel = stringResource(R.string.assistant_page_strict)
    val balancedLabel = stringResource(R.string.assistant_page_balanced)
    val creativeLabel = stringResource(R.string.assistant_page_creative)
    val chaoticLabel = stringResource(R.string.assistant_page_chaotic)
    val topKTightLabel = stringResource(R.string.sampling_editor_top_k_status_tight)
    val topKCommonLabel = stringResource(R.string.sampling_editor_top_k_status_common)
    val topKOpenLabel = stringResource(R.string.sampling_editor_top_k_status_open)
    val newTopicLabel = stringResource(R.string.sampling_editor_presence_penalty_status_new_topic)
    val oldTopicLabel = stringResource(R.string.sampling_editor_presence_penalty_status_old_topic)
    val neutralLabel = stringResource(R.string.sampling_editor_neutral)
    val reduceRepetitionLabel = stringResource(R.string.sampling_editor_frequency_penalty_status_reduce)
    val allowRepetitionLabel = stringResource(R.string.sampling_editor_frequency_penalty_status_repeat)
    val lightSuppressionLabel = stringResource(R.string.sampling_editor_repetition_penalty_status_light)
    val strongSuppressionLabel = stringResource(R.string.sampling_editor_repetition_penalty_status_strong)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamplingFieldGroup(
            title = stringResource(R.string.sampling_editor_group_basic_title),
            description = stringResource(R.string.sampling_editor_group_basic_desc),
        ) {
            OptionalFloatSliderRequestParamField(
                label = stringResource(R.string.assistant_page_temperature),
                value = temperature,
                description = stringResource(R.string.sampling_editor_temperature_desc),
                defaultValue = 1.0f,
                valueRange = 0f..2f,
                steps = 19,
                onValueChange = onTemperatureChange,
                normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 2f) ?: 1.0f },
                status = { currentTemperature ->
                    when (currentTemperature) {
                        in 0.0f..0.3f -> SliderStatus(TagType.INFO, strictLabel)
                        in 0.3f..1.0f -> SliderStatus(TagType.SUCCESS, balancedLabel)
                        in 1.0f..1.5f -> SliderStatus(TagType.WARNING, creativeLabel)
                        else -> SliderStatus(TagType.ERROR, chaoticLabel)
                    }
                },
            )

            OptionalFloatSliderRequestParamField(
                label = stringResource(R.string.assistant_page_top_p),
                value = topP,
                description = stringResource(R.string.sampling_editor_top_p_desc),
                defaultValue = 1.0f,
                valueRange = 0f..1f,
                onValueChange = onTopPChange,
                normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f },
            )

            if (showTopK) {
                OptionalIntSliderRequestParamField(
                    label = stringResource(R.string.sampling_editor_top_k),
                    value = topK,
                    description = stringResource(R.string.sampling_editor_top_k_desc),
                    defaultValue = 40,
                    valueRange = 0..500,
                    onValueChange = onTopKChange,
                    status = { currentTopK ->
                        when {
                            currentTopK <= 20 -> SliderStatus(TagType.INFO, topKTightLabel)

                            currentTopK <= 80 -> SliderStatus(TagType.SUCCESS, topKCommonLabel)

                            else -> SliderStatus(TagType.WARNING, topKOpenLabel)
                        }
                    },
                )
            }

            OptionalIntRequestParamField(
                label = stringResource(R.string.assistant_page_max_tokens),
                value = maxTokens,
                description = stringResource(R.string.sampling_editor_max_tokens_desc),
                placeholder = stringResource(R.string.assistant_page_max_tokens_no_limit),
                onValueChange = onMaxTokensChange,
            )
        }

        if (showPresencePenalty || showFrequencyPenalty || showRepetitionPenalty || showMinP || showTopA) {
            SamplingFieldGroup(
                title = stringResource(R.string.sampling_editor_group_filter_title),
                description = stringResource(R.string.sampling_editor_group_filter_desc),
            ) {
                if (showPresencePenalty) {
                    OptionalFloatSliderRequestParamField(
                        label = stringResource(R.string.sampling_editor_presence_penalty),
                        value = presencePenalty,
                        description = stringResource(R.string.sampling_editor_presence_penalty_desc),
                        defaultValue = 0f,
                        valueRange = -2f..2f,
                        onValueChange = onPresencePenaltyChange,
                        normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f },
                        status = { penalty ->
                            when {
                                penalty > 0.01f -> SliderStatus(TagType.SUCCESS, newTopicLabel)

                                penalty < -0.01f -> SliderStatus(TagType.WARNING, oldTopicLabel)

                                else -> SliderStatus(TagType.DEFAULT, neutralLabel)
                            }
                        },
                    )
                }

                if (showFrequencyPenalty) {
                    OptionalFloatSliderRequestParamField(
                        label = stringResource(R.string.sampling_editor_frequency_penalty),
                        value = frequencyPenalty,
                        description = stringResource(R.string.sampling_editor_frequency_penalty_desc),
                        defaultValue = 0f,
                        valueRange = -2f..2f,
                        onValueChange = onFrequencyPenaltyChange,
                        normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f },
                        status = { penalty ->
                            when {
                                penalty > 0.01f -> SliderStatus(TagType.SUCCESS, reduceRepetitionLabel)

                                penalty < -0.01f -> SliderStatus(TagType.WARNING, allowRepetitionLabel)

                                else -> SliderStatus(TagType.DEFAULT, neutralLabel)
                            }
                        },
                    )
                }

                if (showRepetitionPenalty) {
                    OptionalFloatSliderRequestParamField(
                        label = stringResource(R.string.sampling_editor_repetition_penalty),
                        value = repetitionPenalty,
                        description = stringResource(R.string.sampling_editor_repetition_penalty_desc),
                        defaultValue = 1.1f,
                        valueRange = 1f..2f,
                        onValueChange = onRepetitionPenaltyChange,
                        normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(1f, 2f) ?: 1.1f },
                        status = { penalty ->
                            when {
                                penalty <= 1.02f -> SliderStatus(TagType.DEFAULT, neutralLabel)

                                penalty <= 1.20f -> SliderStatus(TagType.SUCCESS, lightSuppressionLabel)

                                else -> SliderStatus(TagType.WARNING, strongSuppressionLabel)
                            }
                        },
                    )
                }

                if (showMinP) {
                    OptionalFloatSliderRequestParamField(
                        label = stringResource(R.string.sampling_editor_min_p),
                        value = minP,
                        description = stringResource(R.string.sampling_editor_min_p_desc),
                        defaultValue = 0.05f,
                        valueRange = 0f..1f,
                        onValueChange = onMinPChange,
                        normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f },
                    )
                }

                if (showTopA) {
                    OptionalFloatSliderRequestParamField(
                        label = stringResource(R.string.sampling_editor_top_a),
                        value = topA,
                        description = stringResource(R.string.sampling_editor_top_a_desc),
                        defaultValue = 0f,
                        valueRange = 0f..1f,
                        onValueChange = onTopAChange,
                        normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f },
                    )
                }
            }
        }

        if (showStopSequences || showSeed || showGoogleResponseMimeType || showReasoningEffort || showVerbosity) {
            SamplingFieldGroup(
                title = stringResource(R.string.sampling_editor_group_runtime_title),
                description = stringResource(R.string.sampling_editor_group_runtime_desc),
            ) {
                if (showStopSequences) {
                    OptionalStringLinesRequestParamField(
                        label = stringResource(R.string.sampling_editor_stop_sequences),
                        value = stopSequences,
                        description = stringResource(R.string.sampling_editor_stop_sequences_desc),
                        placeholder = stringResource(R.string.sampling_editor_stop_sequences_placeholder),
                        onValueChange = onStopSequencesChange,
                    )
                }

                if (showSeed) {
                    OptionalLongRequestParamField(
                        label = stringResource(R.string.sampling_editor_seed),
                        value = seed,
                        description = stringResource(R.string.sampling_editor_seed_desc),
                        onValueChange = onSeedChange,
                    )
                }

                if (showGoogleResponseMimeType) {
                    OptionalStringRequestParamField(
                        label = stringResource(R.string.sampling_editor_response_mime_type),
                        value = googleResponseMimeType,
                        description = stringResource(R.string.sampling_editor_response_mime_type_desc),
                        placeholder = stringResource(R.string.sampling_editor_response_mime_type_placeholder),
                        onValueChange = onGoogleResponseMimeTypeChange,
                    )
                }

                if (showReasoningEffort) {
                    OptionalStringRequestParamField(
                        label = stringResource(R.string.assistant_page_openai_reasoning_effort),
                        value = reasoningEffort,
                        description = stringResource(R.string.sampling_editor_reasoning_effort_desc),
                        placeholder = stringResource(R.string.assistant_page_openai_reasoning_effort_hint),
                        onValueChange = onReasoningEffortChange,
                    )
                }

                if (showVerbosity) {
                    OptionalStringRequestParamField(
                        label = stringResource(R.string.sampling_editor_verbosity),
                        value = verbosity,
                        description = stringResource(R.string.sampling_editor_verbosity_desc),
                        placeholder = stringResource(R.string.sampling_editor_verbosity_placeholder),
                        onValueChange = onVerbosityChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionalIntRequestParamField(
    label: String,
    value: Int?,
    description: String,
    placeholder: String? = null,
    onValueChange: (Int?) -> Unit,
) {
    val resolvedPlaceholder = placeholder ?: stringResource(R.string.sampling_editor_default_placeholder)
    ParsedRequestParamField(
        label = label,
        description = description,
        value = value,
        keyboardType = KeyboardType.Number,
        placeholder = resolvedPlaceholder,
        parse = { it.toIntOrNull() },
        onValueChange = onValueChange,
    )
}

@Composable
private fun OptionalFloatSliderRequestParamField(
    label: String,
    value: Float?,
    description: String,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float?) -> Unit,
    normalize: (Float) -> Float,
    status: (Float) -> SliderStatus? = { null },
) {
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        },
        tail = {
            Switch(
                checked = value != null,
                onCheckedChange = { enabled ->
                    onValueChange(if (enabled) value ?: defaultValue else null)
                },
            )
        },
    ) {
        value?.let { currentValue ->
            val sliderState = rememberCommitOnFinishSliderState(currentValue)
            Slider(
                value = sliderState.value,
                onValueChange = sliderState::onValueChange,
                onValueChangeFinished = {
                    sliderState.onValueChangeFinished(
                        externalValue = currentValue,
                        onValueCommitted = onValueChange,
                        normalize = normalize,
                    )
                },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderMetaRow(
                valueText = sliderState.value.toFixed(2),
                status = status(sliderState.value),
            )
        }
    }
}

@Composable
private fun OptionalIntSliderRequestParamField(
    label: String,
    value: Int?,
    description: String,
    defaultValue: Int,
    valueRange: IntRange,
    onValueChange: (Int?) -> Unit,
    status: (Int) -> SliderStatus? = { null },
) {
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        },
        tail = {
            Switch(
                checked = value != null,
                onCheckedChange = { enabled ->
                    onValueChange(if (enabled) value ?: defaultValue else null)
                },
            )
        },
    ) {
        value?.let { currentValue ->
            val sliderState = rememberCommitOnFinishSliderState(currentValue.toFloat())
            Slider(
                value = sliderState.value,
                onValueChange = sliderState::onValueChange,
                onValueChangeFinished = {
                    sliderState.onValueChangeFinished(
                        externalValue = currentValue.toFloat(),
                        onValueCommitted = { onValueChange(it.roundToInt()) },
                        normalize = {
                            it.roundToInt()
                                .coerceIn(valueRange.first, valueRange.last)
                                .toFloat()
                        },
                    )
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            val sliderValue = sliderState.value.roundToInt()
            SliderMetaRow(
                valueText = sliderValue.toString(),
                status = status(sliderValue),
            )
        }
    }
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
        placeholder = stringResource(R.string.sampling_editor_default_placeholder),
        parse = { it.toLongOrNull() },
        onValueChange = onValueChange,
    )
}

@Composable
private fun OptionalStringRequestParamField(
    label: String,
    value: String,
    description: String,
    placeholder: String? = null,
    onValueChange: (String) -> Unit,
) {
    val resolvedPlaceholder = placeholder ?: stringResource(R.string.sampling_editor_default_placeholder)
    var text by rememberSaveable(value) { mutableStateOf(value) }
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onValueChange(input)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(resolvedPlaceholder)
            },
        )
    }
}

@Composable
private fun OptionalStringLinesRequestParamField(
    label: String,
    value: List<String>,
    description: String,
    placeholder: String? = null,
    onValueChange: (List<String>) -> Unit,
) {
    val resolvedPlaceholder = placeholder ?: stringResource(R.string.sampling_editor_one_per_line_placeholder)
    val externalValue = value.joinToString("\n")
    var text by rememberSaveable(externalValue) { mutableStateOf(externalValue) }

    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(label)
        },
        description = {
            Text(description)
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                onValueChange(
                    input.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList(),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(resolvedPlaceholder)
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
    placeholder: String,
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
        },
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
                Text(placeholder)
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

@Composable
private fun SamplingFieldGroup(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SliderMetaRow(
    valueText: String,
    status: SliderStatus?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(type = TagType.INFO) {
            Text(valueText)
        }
        status?.let {
            Tag(type = it.type) {
                Text(it.text)
            }
        }
    }
}

private data class SliderStatus(
    val type: TagType,
    val text: String,
)
