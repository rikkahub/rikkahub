package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.configuredValueCount
import me.rerere.rikkahub.ui.components.sampling.SamplingRequestFieldGroups
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.CustomColors

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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_sampling_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.prompt_page_st_preset_sampling_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.prompt_page_st_preset_sampling_configured_count,
                                sampling.configuredValueCount(),
                            ),
                        )
                    }
                    Tag(
                        type = if (sampling.configuredValueCount() > 0) {
                            TagType.WARNING
                        } else {
                            TagType.DEFAULT
                        },
                    ) {
                        Text(
                            if (sampling.configuredValueCount() > 0) {
                                stringResource(R.string.prompt_page_st_preset_sampling_takeover_active)
                            } else {
                                stringResource(R.string.prompt_page_st_preset_sampling_takeover_inherit)
                            },
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
                        text = stringResource(R.string.prompt_page_st_preset_sampling_guide_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SamplingGuideRow(
                        title = stringResource(R.string.prompt_page_st_preset_sampling_guide_takeover_title),
                        body = stringResource(R.string.prompt_page_st_preset_sampling_guide_takeover_body),
                    )
                    SamplingGuideRow(
                        title = stringResource(R.string.prompt_page_st_preset_sampling_guide_basic_title),
                        body = stringResource(R.string.prompt_page_st_preset_sampling_guide_basic_body),
                    )
                    SamplingGuideRow(
                        title = stringResource(R.string.prompt_page_st_preset_sampling_guide_compat_title),
                        body = stringResource(R.string.prompt_page_st_preset_sampling_guide_compat_body),
                    )
                }
            }

            SamplingRequestFieldGroups(
                temperature = sampling.temperature,
                onTemperatureChange = { onUpdate(sampling.copy(temperature = it)) },
                topP = sampling.topP,
                onTopPChange = { onUpdate(sampling.copy(topP = it)) },
                showTopK = true,
                topK = sampling.topK,
                onTopKChange = { onUpdate(sampling.copy(topK = it)) },
                maxTokens = sampling.maxTokens,
                onMaxTokensChange = { onUpdate(sampling.copy(maxTokens = it)) },
                showPresencePenalty = true,
                presencePenalty = sampling.presencePenalty,
                onPresencePenaltyChange = { onUpdate(sampling.copy(presencePenalty = it)) },
                showFrequencyPenalty = true,
                frequencyPenalty = sampling.frequencyPenalty,
                onFrequencyPenaltyChange = { onUpdate(sampling.copy(frequencyPenalty = it)) },
                showRepetitionPenalty = true,
                repetitionPenalty = sampling.repetitionPenalty,
                onRepetitionPenaltyChange = { onUpdate(sampling.copy(repetitionPenalty = it)) },
                showMinP = true,
                minP = sampling.minP,
                onMinPChange = { onUpdate(sampling.copy(minP = it)) },
                showTopA = true,
                topA = sampling.topA,
                onTopAChange = { onUpdate(sampling.copy(topA = it)) },
                showStopSequences = true,
                stopSequences = sampling.stopSequences,
                onStopSequencesChange = { onUpdate(sampling.copy(stopSequences = it)) },
                showSeed = true,
                seed = sampling.seed,
                onSeedChange = { onUpdate(sampling.copy(seed = it)) },
                showReasoningEffort = true,
                reasoningEffort = sampling.openAIReasoningEffort,
                onReasoningEffortChange = { onUpdate(sampling.copy(openAIReasoningEffort = it)) },
                showVerbosity = true,
                verbosity = sampling.openAIVerbosity,
                onVerbosityChange = { onUpdate(sampling.copy(openAIVerbosity = it)) },
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
