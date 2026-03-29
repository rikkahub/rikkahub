package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.getSupportedGptReasoningLevels
import me.rerere.ai.core.resolveCompatibilityReasoningLevel
import me.rerere.ai.core.resolveGptReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningTokens: Int,
    model: Model? = null,
    onUpdateReasoningTokens: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningTokens = reasoningTokens,
            model = model,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningTokens = onUpdateReasoningTokens
        )
    }

    val level = resolveReasoningLevel(model, reasoningTokens)
    ToggleSurface(
        checked = level.isEnabled,
        onClick = {
            showPicker = true
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningLevelIcon(level)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningTokens: Int,
    model: Model? = null,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningTokens: (Int) -> Unit,
) {
    val currentLevel = resolveReasoningLevel(model, reasoningTokens)
    val levels = remember(model?.modelId) {
        model?.let { currentModel ->
            getSupportedGptReasoningLevels(currentModel.modelId)?.let { listOf(ReasoningLevel.AUTO) + it }
        } ?: ReasoningLevel.compatibilityPresets
    }
    ModalBottomSheet(
        onDismissRequest = {
            onDismissRequest()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            levels.forEach { level ->
                ReasoningLevelCard(
                    selected = currentLevel == level,
                    icon = {
                        ReasoningLevelIcon(level)
                    },
                    title = {
                        Text(stringResource(id = reasoningTitle(level)))
                    },
                    description = {
                        Text(stringResource(id = reasoningDescription(level)))
                    },
                    onClick = {
                        onUpdateReasoningTokens(level.budgetTokens)
                    }
                )
            }

            Card(
                modifier = Modifier.imePadding(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(stringResource(id = R.string.reasoning_custom))
                    var input by remember(reasoningTokens) {
                        mutableStateOf(reasoningTokens.toString())
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { newValue ->
                            input = newValue
                            val newTokens = newValue.toIntOrNull()
                            if (newTokens != null) {
                                onUpdateReasoningTokens(newTokens)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningLevelIcon(level: ReasoningLevel) {
    when (level) {
        ReasoningLevel.OFF -> Icon(HugeIcons.Idea, null)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Idea01, null)
        ReasoningLevel.MINIMAL, ReasoningLevel.LOW -> Icon(ReasoningLow, null)
        ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null)
        ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null)
    }
}

private fun resolveReasoningLevel(model: Model?, reasoningTokens: Int): ReasoningLevel {
    return model?.let { resolveGptReasoningLevel(it.modelId, reasoningTokens) }
        ?: resolveCompatibilityReasoningLevel(reasoningTokens)
}

private fun reasoningTitle(level: ReasoningLevel): Int {
    return when (level) {
        ReasoningLevel.OFF -> R.string.reasoning_off
        ReasoningLevel.AUTO -> R.string.reasoning_auto
        ReasoningLevel.MINIMAL -> R.string.reasoning_minimal
        ReasoningLevel.LOW -> R.string.reasoning_light
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium
        ReasoningLevel.HIGH -> R.string.reasoning_heavy
        ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
    }
}

private fun reasoningDescription(level: ReasoningLevel): Int {
    return when (level) {
        ReasoningLevel.OFF -> R.string.reasoning_off_desc
        ReasoningLevel.AUTO -> R.string.reasoning_auto_desc
        ReasoningLevel.MINIMAL -> R.string.reasoning_minimal_desc
        ReasoningLevel.LOW -> R.string.reasoning_light_desc
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium_desc
        ReasoningLevel.HIGH -> R.string.reasoning_heavy_desc
        ReasoningLevel.XHIGH -> R.string.reasoning_xhigh_desc
    }
}

@Composable
private fun ReasoningLevelCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    description: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val containerColor = animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    )
    val textColor = animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = containerColor.value,
            contentColor = textColor.value,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                    title()
                }
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    description()
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var reasoningTokens by remember { mutableIntStateOf(0) }
        ReasoningPicker(
            onDismissRequest = {},
            reasoningTokens = reasoningTokens,
            model = null,
            onUpdateReasoningTokens = {
                reasoningTokens = it
            }
        )
    }
}
