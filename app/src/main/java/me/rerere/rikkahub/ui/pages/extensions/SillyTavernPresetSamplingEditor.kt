package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.configuredValueCount
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.rememberCommitOnFinishSliderState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed

@Composable
fun PresetSamplingEditorCard(
    sampling: SillyTavernPresetSampling,
    onUpdate: (SillyTavernPresetSampling) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "采样 / 运行参数",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "启用 ST 预设后，这里的字段会覆盖助手自身的采样参数。至少配置 1 项后才会接管运行时；一旦接管，留空字段也会按空值发送，不回退助手值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tag(type = TagType.INFO) {
                    Text("已配置 ${sampling.configuredValueCount()} 项")
                }
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text("控制回复随机度。")
                },
                tail = {
                    Switch(
                        checked = sampling.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                sampling.copy(
                                    temperature = if (enabled) sampling.temperature ?: 1.0f else null,
                                )
                            )
                        },
                    )
                }
            ) {
                sampling.temperature?.let { temperature ->
                    val sliderState = rememberCommitOnFinishSliderState(temperature)
                    Slider(
                        value = sliderState.value,
                        onValueChange = sliderState::onValueChange,
                        onValueChangeFinished = {
                            sliderState.onValueChangeFinished(
                                externalValue = temperature,
                                onValueCommitted = { onUpdate(sampling.copy(temperature = it)) },
                                normalize = {
                                    it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 2f) ?: 1.0f
                                },
                            )
                        },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val currentTemperature = sliderState.value
                        val tagType = when (currentTemperature) {
                            in 0.0f..0.3f -> TagType.INFO
                            in 0.3f..1.0f -> TagType.SUCCESS
                            in 1.0f..1.5f -> TagType.WARNING
                            else -> TagType.ERROR
                        }
                        Tag(type = TagType.INFO) {
                            Text(currentTemperature.toFixed(2))
                        }
                        Tag(type = tagType) {
                            Text(
                                text = when (currentTemperature) {
                                    in 0.0f..0.3f -> stringResource(R.string.assistant_page_strict)
                                    in 0.3f..1.0f -> stringResource(R.string.assistant_page_balanced)
                                    in 1.0f..1.5f -> stringResource(R.string.assistant_page_creative)
                                    else -> stringResource(R.string.assistant_page_chaotic)
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
                    Text(stringResource(R.string.assistant_page_top_p_warning))
                },
                tail = {
                    Switch(
                        checked = sampling.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                sampling.copy(
                                    topP = if (enabled) sampling.topP ?: 1.0f else null,
                                )
                            )
                        },
                    )
                }
            ) {
                sampling.topP?.let { topP ->
                    val sliderState = rememberCommitOnFinishSliderState(topP)
                    Slider(
                        value = sliderState.value,
                        onValueChange = sliderState::onValueChange,
                        onValueChangeFinished = {
                            sliderState.onValueChangeFinished(
                                externalValue = topP,
                                onValueCommitted = { onUpdate(sampling.copy(topP = it)) },
                                normalize = {
                                    it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                },
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_top_p_value,
                            sliderState.value.toFixed(2),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }

            HorizontalDivider()

            OptionalIntRequestParamField(
                label = stringResource(R.string.assistant_page_max_tokens),
                value = sampling.maxTokens,
                description = "限制单次生成的最大输出 token 数，留空表示交给后端默认值。",
                placeholder = stringResource(R.string.assistant_page_max_tokens_no_limit),
                onValueChange = { onUpdate(sampling.copy(maxTokens = it)) },
            )

            HorizontalDivider()

            OptionalFloatRequestParamField(
                label = "Presence penalty",
                value = sampling.presencePenalty,
                description = "提高对已出现主题的惩罚，常见于 OpenAI / Gemini 兼容接口。",
                onValueChange = { onUpdate(sampling.copy(presencePenalty = it)) },
            )

            HorizontalDivider()

            OptionalFloatRequestParamField(
                label = "Frequency penalty",
                value = sampling.frequencyPenalty,
                description = "按 token 重复频率进行惩罚。",
                onValueChange = { onUpdate(sampling.copy(frequencyPenalty = it)) },
            )

            HorizontalDivider()

            OptionalFloatRequestParamField(
                label = "Min P",
                value = sampling.minP,
                description = "部分 OpenAI-compatible 后端支持的最小采样阈值。",
                onValueChange = { onUpdate(sampling.copy(minP = it)) },
            )

            HorizontalDivider()

            OptionalIntRequestParamField(
                label = "Top K",
                value = sampling.topK,
                description = "限制采样候选集合大小。",
                onValueChange = { onUpdate(sampling.copy(topK = it)) },
            )

            HorizontalDivider()

            OptionalFloatRequestParamField(
                label = "Top A",
                value = sampling.topA,
                description = "部分 OpenAI-compatible 后端支持的自适应采样因子。",
                onValueChange = { onUpdate(sampling.copy(topA = it)) },
            )

            HorizontalDivider()

            OptionalFloatRequestParamField(
                label = "Repetition penalty",
                value = sampling.repetitionPenalty,
                description = "抑制重复用词。",
                onValueChange = { onUpdate(sampling.copy(repetitionPenalty = it)) },
            )

            HorizontalDivider()

            OptionalLongRequestParamField(
                label = "Seed",
                value = sampling.seed,
                description = "兼容后端支持时可用于固定随机种子。",
                onValueChange = { onUpdate(sampling.copy(seed = it)) },
            )

            HorizontalDivider()

            OptionalStringLinesRequestParamField(
                label = "Stop sequences",
                value = sampling.stopSequences,
                description = "每行一个停止词。启用预设接管后，留空会发送空 stop 列表。",
                placeholder = "User:",
                onValueChange = { onUpdate(sampling.copy(stopSequences = it)) },
            )

            HorizontalDivider()

            OptionalStringRequestParamField(
                label = "Reasoning effort",
                value = sampling.openAIReasoningEffort,
                description = "OpenAI reasoning effort，常用值是 low / medium / high。",
                placeholder = "low / medium / high",
                onValueChange = { onUpdate(sampling.copy(openAIReasoningEffort = it)) },
            )

            HorizontalDivider()

            OptionalStringRequestParamField(
                label = "Verbosity",
                value = sampling.openAIVerbosity,
                description = "OpenAI Responses API 文本详细度，常用值是 low / medium / high。",
                placeholder = "low / medium / high",
                onValueChange = { onUpdate(sampling.copy(openAIVerbosity = it)) },
            )
        }
    }
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
    placeholder: String = "Default",
    onValueChange: (Int?) -> Unit,
) {
    ParsedRequestParamField(
        label = label,
        description = description,
        value = value,
        keyboardType = KeyboardType.Number,
        placeholder = placeholder,
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
            },
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
    placeholder: String = "Default",
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
                Text(placeholder)
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}
