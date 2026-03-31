package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.configuredValueCount
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.rememberCommitOnFinishSliderState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import kotlin.math.roundToInt

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
        val strictLabel = stringResource(R.string.assistant_page_strict)
        val balancedLabel = stringResource(R.string.assistant_page_balanced)
        val creativeLabel = stringResource(R.string.assistant_page_creative)
        val chaoticLabel = stringResource(R.string.assistant_page_chaotic)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag(type = TagType.INFO) {
                        Text("已配置 ${sampling.configuredValueCount()} 项")
                    }
                    Tag(
                        type = if (sampling.configuredValueCount() > 0) {
                            TagType.WARNING
                        } else {
                            TagType.DEFAULT
                        }
                    ) {
                        Text(
                            if (sampling.configuredValueCount() > 0) {
                                "当前活动预设会接管请求参数"
                            } else {
                                "未配置时继续沿用助手参数"
                            }
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "使用说明",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SamplingGuideRow(
                        title = "接管规则",
                        body = "这里至少配置 1 项后，当前活动 ST 预设会覆盖助手自身采样；未填写的字段会按空值一起发送，不再回退助手默认值。",
                    )
                    SamplingGuideRow(
                        title = "基础采样",
                        body = "Temperature、Top P、Top K 和 Max Tokens 决定随机度、候选范围和输出上限，通常先从这组开始调整。",
                    )
                    SamplingGuideRow(
                        title = "兼容参数",
                        body = "Penalty、Min P、Top A、Seed、Reasoning effort 等字段只在目标后端支持时生效，导入 ST 预设时会原样保留。",
                    )
                }
            }

            SamplingFieldGroup(
                title = "基础采样",
                description = "常用参数。优先在这一组里调随机度、候选裁剪和最大输出。",
            ) {
                OptionalFloatSliderRequestParamField(
                    label = stringResource(R.string.assistant_page_temperature),
                    value = sampling.temperature,
                    description = "控制回复随机度。越低越稳，越高越发散。",
                    defaultValue = 1.0f,
                    valueRange = 0f..2f,
                    steps = 19,
                    onValueChange = { onUpdate(sampling.copy(temperature = it)) },
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
                    value = sampling.topP,
                    description = "按累计概率裁剪候选集合。通常不要和极高 temperature 同时拉满。",
                    defaultValue = 1.0f,
                    valueRange = 0f..1f,
                    onValueChange = { onUpdate(sampling.copy(topP = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f },
                )

                OptionalIntSliderRequestParamField(
                    label = "Top K",
                    value = sampling.topK,
                    description = "只在分数最高的 K 个候选里采样。越小越稳，越大越开放。",
                    defaultValue = 40,
                    valueRange = 0..500,
                    onValueChange = { onUpdate(sampling.copy(topK = it)) },
                    status = { currentTopK ->
                        when {
                            currentTopK <= 20 -> SliderStatus(TagType.INFO, "收紧候选")
                            currentTopK <= 80 -> SliderStatus(TagType.SUCCESS, "常用范围")
                            else -> SliderStatus(TagType.WARNING, "开放候选")
                        }
                    },
                )

                OptionalIntRequestParamField(
                    label = stringResource(R.string.assistant_page_max_tokens),
                    value = sampling.maxTokens,
                    description = "限制单次生成的最大输出 token 数。留空表示交给后端默认值。",
                    placeholder = stringResource(R.string.assistant_page_max_tokens_no_limit),
                    onValueChange = { onUpdate(sampling.copy(maxTokens = it)) },
                )
            }

            SamplingFieldGroup(
                title = "候选过滤与惩罚",
                description = "控制重复抑制、候选削减和话题偏移。大多数情况下只需要微调，不建议一次改很多项。",
            ) {
                OptionalFloatSliderRequestParamField(
                    label = "Presence penalty",
                    value = sampling.presencePenalty,
                    description = "鼓励模型谈新内容。正值更容易换话题，负值会更黏住已出现主题。",
                    defaultValue = 0f,
                    valueRange = -2f..2f,
                    onValueChange = { onUpdate(sampling.copy(presencePenalty = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f },
                    status = { penalty ->
                        when {
                            penalty > 0.01f -> SliderStatus(TagType.SUCCESS, "偏向新话题")
                            penalty < -0.01f -> SliderStatus(TagType.WARNING, "偏向旧话题")
                            else -> SliderStatus(TagType.DEFAULT, "中性")
                        }
                    },
                )

                OptionalFloatSliderRequestParamField(
                    label = "Frequency penalty",
                    value = sampling.frequencyPenalty,
                    description = "按 token 重复频率进行惩罚。正值越大，重复句式和口头禅越少。",
                    defaultValue = 0f,
                    valueRange = -2f..2f,
                    onValueChange = { onUpdate(sampling.copy(frequencyPenalty = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f },
                    status = { penalty ->
                        when {
                            penalty > 0.01f -> SliderStatus(TagType.SUCCESS, "抑制重复")
                            penalty < -0.01f -> SliderStatus(TagType.WARNING, "鼓励复现")
                            else -> SliderStatus(TagType.DEFAULT, "中性")
                        }
                    },
                )

                OptionalFloatSliderRequestParamField(
                    label = "Repetition penalty",
                    value = sampling.repetitionPenalty,
                    description = "常见于 llama / OpenAI-compatible 后端的重复惩罚。1.0 为中性，越高越抑制重复。",
                    defaultValue = 1.1f,
                    valueRange = 1f..2f,
                    onValueChange = { onUpdate(sampling.copy(repetitionPenalty = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(1f, 2f) ?: 1.1f },
                    status = { penalty ->
                        when {
                            penalty <= 1.02f -> SliderStatus(TagType.DEFAULT, "中性")
                            penalty <= 1.20f -> SliderStatus(TagType.SUCCESS, "轻抑制")
                            else -> SliderStatus(TagType.WARNING, "强抑制")
                        }
                    },
                )

                OptionalFloatSliderRequestParamField(
                    label = "Min P",
                    value = sampling.minP,
                    description = "为低概率 token 设置下限过滤。数值越大越保守，通常只在兼容后端明确支持时调整。",
                    defaultValue = 0.05f,
                    valueRange = 0f..1f,
                    onValueChange = { onUpdate(sampling.copy(minP = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f },
                )

                OptionalFloatSliderRequestParamField(
                    label = "Top A",
                    value = sampling.topA,
                    description = "根据候选分布动态裁剪尾部 token。大多数模型不需要改，除非你明确知道目标后端支持它。",
                    defaultValue = 0f,
                    valueRange = 0f..1f,
                    onValueChange = { onUpdate(sampling.copy(topA = it)) },
                    normalize = { it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f },
                )
            }

            SamplingFieldGroup(
                title = "高级请求参数",
                description = "主要用于兼容指定后端或复现特定 ST 预设。只有后端真正支持时才会生效。",
            ) {
                OptionalLongRequestParamField(
                    label = "Seed",
                    value = sampling.seed,
                    description = "兼容后端支持时可用于固定随机种子，便于复现输出。",
                    onValueChange = { onUpdate(sampling.copy(seed = it)) },
                )

                OptionalStringLinesRequestParamField(
                    label = "Stop sequences",
                    value = sampling.stopSequences,
                    description = "每行一个停止词。启用预设接管后，留空会发送空 stop 列表。",
                    placeholder = "User:",
                    onValueChange = { onUpdate(sampling.copy(stopSequences = it)) },
                )

                OptionalStringRequestParamField(
                    label = "Reasoning effort",
                    value = sampling.openAIReasoningEffort,
                    description = "OpenAI reasoning effort，常用值是 low / medium / high。只对支持 reasoning 的接口生效。",
                    placeholder = "low / medium / high",
                    onValueChange = { onUpdate(sampling.copy(openAIReasoningEffort = it)) },
                )

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
                        onValueCommitted = { onValueChange(it) },
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
private fun SamplingGuideRow(
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
