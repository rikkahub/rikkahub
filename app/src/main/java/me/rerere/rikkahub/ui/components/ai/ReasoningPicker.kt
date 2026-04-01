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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium

private fun ReasoningLevel.icon(): ImageVector = when (this) {
    ReasoningLevel.OFF -> HugeIcons.Idea
    ReasoningLevel.AUTO -> HugeIcons.Idea01
    ReasoningLevel.MINIMAL -> ReasoningLow
    ReasoningLevel.LOW -> ReasoningLow
    ReasoningLevel.MEDIUM -> ReasoningMedium
    ReasoningLevel.HIGH -> ReasoningHigh
    ReasoningLevel.XHIGH -> ReasoningHigh
}

private data class ReasoningLevelStrings(val titleRes: Int, val descRes: Int)

private fun ReasoningLevel.strings(): ReasoningLevelStrings = when (this) {
    ReasoningLevel.OFF -> ReasoningLevelStrings(R.string.reasoning_off, R.string.reasoning_off_desc)
    ReasoningLevel.AUTO -> ReasoningLevelStrings(R.string.reasoning_auto, R.string.reasoning_auto_desc)
    ReasoningLevel.MINIMAL -> ReasoningLevelStrings(R.string.reasoning_minimal, R.string.reasoning_minimal_desc)
    ReasoningLevel.LOW -> ReasoningLevelStrings(R.string.reasoning_light, R.string.reasoning_light_desc)
    ReasoningLevel.MEDIUM -> ReasoningLevelStrings(R.string.reasoning_medium, R.string.reasoning_medium_desc)
    ReasoningLevel.HIGH -> ReasoningLevelStrings(R.string.reasoning_heavy, R.string.reasoning_heavy_desc)
    ReasoningLevel.XHIGH -> ReasoningLevelStrings(R.string.reasoning_xhigh, R.string.reasoning_xhigh_desc)
}

private fun displayedReasoningLevel(
    reasoningTokens: Int,
    supportedLevels: List<ReasoningLevel>,
): ReasoningLevel {
    val availableLevels = supportedLevels.ifEmpty { ReasoningLevel.entries.toList() }
    val requested = ReasoningLevel.fromBudgetTokens(reasoningTokens)
    if (requested == ReasoningLevel.AUTO && ReasoningLevel.AUTO in availableLevels) {
        return ReasoningLevel.AUTO
    }

    val candidates = availableLevels.filter { it != ReasoningLevel.AUTO }
    if (candidates.isEmpty()) {
        return requested
    }

    return if (requested in candidates) {
        requested
    } else {
        candidates.minByOrNull { kotlin.math.abs(it.budgetTokens - reasoningTokens) } ?: requested
    }
}

internal fun guardReasoningUpdate(
    modelId: String?,
    hasBuiltInWebSearch: Boolean,
    tokens: Int,
    onBlocked: () -> Unit,
    onAllowed: (Int) -> Unit,
) {
    val isMinimalWebSearchConflict = modelId != null &&
        hasBuiltInWebSearch &&
        ModelRegistry.GPT_5.match(modelId) &&
        ReasoningLevel.fromBudgetTokens(tokens) == ReasoningLevel.MINIMAL

    if (isMinimalWebSearchConflict) {
        onBlocked()
    } else {
        onAllowed(tokens)
    }
}

internal fun normalizeReasoningTokensForModel(
    modelId: String?,
    hasBuiltInWebSearch: Boolean,
    tokens: Int,
): Int {
    if (modelId == null) {
        return tokens
    }

    val baseSupportedLevels = ModelRegistry.SUPPORTED_REASONING_LEVELS.getData(modelId)
    if (baseSupportedLevels == ReasoningLevel.entries.toList()) {
        return tokens
    }

    return ModelRegistry.normalizeReasoningLevel(
        modelId = modelId,
        requested = ReasoningLevel.fromBudgetTokens(tokens),
        hasBuiltInWebSearch = hasBuiltInWebSearch,
    ).budgetTokens
}

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningTokens: Int,
    onUpdateReasoningTokens: (Int) -> Unit,
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningTokens = reasoningTokens,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningTokens = onUpdateReasoningTokens,
            supportedLevels = supportedLevels,
        )
    }

    val level = displayedReasoningLevel(reasoningTokens, supportedLevels)
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
                Icon(level.icon(), null)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningTokens: Int,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningTokens: (Int) -> Unit,
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries,
) {
    val currentLevel = displayedReasoningLevel(reasoningTokens, supportedLevels)
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
            supportedLevels.forEach { level ->
                val strings = level.strings()
                ReasoningLevelCard(
                    selected = currentLevel == level,
                    icon = {
                        Icon(level.icon(), null)
                    },
                    title = {
                        Text(stringResource(id = strings.titleRes))
                    },
                    description = {
                        Text(stringResource(id = strings.descRes))
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
            onUpdateReasoningTokens = {
                reasoningTokens = it
            }
        )
    }
}
