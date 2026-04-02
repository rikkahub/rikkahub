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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.math.abs

private data class ReasoningLevelOption(
    val level: ReasoningLevel,
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
)

private val ALL_LEVEL_OPTIONS = listOf(
    ReasoningLevelOption(ReasoningLevel.OFF, HugeIcons.Idea, R.string.reasoning_off, R.string.reasoning_off_desc),
    ReasoningLevelOption(ReasoningLevel.AUTO, HugeIcons.Idea01, R.string.reasoning_auto, R.string.reasoning_auto_desc),
    ReasoningLevelOption(ReasoningLevel.MINIMAL, ReasoningLow, R.string.reasoning_minimal, R.string.reasoning_minimal_desc),
    ReasoningLevelOption(ReasoningLevel.LOW, ReasoningLow, R.string.reasoning_light, R.string.reasoning_light_desc),
    ReasoningLevelOption(ReasoningLevel.MEDIUM, ReasoningMedium, R.string.reasoning_medium, R.string.reasoning_medium_desc),
    ReasoningLevelOption(ReasoningLevel.HIGH, ReasoningHigh, R.string.reasoning_heavy, R.string.reasoning_heavy_desc),
    ReasoningLevelOption(ReasoningLevel.XHIGH, ReasoningHigh, R.string.reasoning_xhigh, R.string.reasoning_xhigh_desc),
)

private val ALL_REASONING_LEVELS = ReasoningLevel.entries.toList()

private fun normalizeReasoningTokens(reasoningTokens: Int, supportedLevels: List<ReasoningLevel>): Int {
    if (supportedLevels.isEmpty() || supportedLevels.containsAll(ALL_REASONING_LEVELS)) {
        return reasoningTokens
    }

    if (reasoningTokens == ReasoningLevel.AUTO.budgetTokens) {
        return reasoningTokens
    }

    if (reasoningTokens == ReasoningLevel.OFF.budgetTokens && ReasoningLevel.OFF in supportedLevels) {
        return reasoningTokens
    }

    val enabledSupported = supportedLevels
        .filter { it != ReasoningLevel.AUTO && it != ReasoningLevel.OFF }
        .ifEmpty { supportedLevels.filter { it != ReasoningLevel.AUTO } }

    if (enabledSupported.isEmpty()) {
        return reasoningTokens
    }

    return enabledSupported.minBy { abs(it.budgetTokens - reasoningTokens) }.budgetTokens
}

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningTokens: Int,
    onUpdateReasoningTokens: (Int) -> Unit,
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries.toList(),
) {
    var showPicker by remember { mutableStateOf(false) }
    val normalizedTokens = normalizeReasoningTokens(reasoningTokens, supportedLevels)

    if (showPicker) {
        ReasoningPicker(
            reasoningTokens = normalizedTokens,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningTokens = onUpdateReasoningTokens,
            supportedLevels = supportedLevels,
        )
    }

    val level = ReasoningLevel.fromBudgetTokens(normalizedTokens)
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
                when (level) {
                    ReasoningLevel.OFF -> Icon(HugeIcons.Idea, null)
                    ReasoningLevel.AUTO -> Icon(HugeIcons.Idea01, null)
                    ReasoningLevel.MINIMAL -> Icon(ReasoningLow, null)
                    ReasoningLevel.LOW -> Icon(ReasoningLow, null)
                    ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null)
                    ReasoningLevel.HIGH -> Icon(ReasoningHigh, null)
                    ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null)
                }
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
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries.toList(),
) {
    val normalizedTokens = normalizeReasoningTokens(reasoningTokens, supportedLevels)
    val currentLevel = ReasoningLevel.fromBudgetTokens(normalizedTokens)
    val visibleOptions = ALL_LEVEL_OPTIONS.filter { it.level in supportedLevels }
    var input by remember(normalizedTokens) {
        mutableStateOf(normalizedTokens.toString())
    }

    fun commitInput() {
        input.toIntOrNull()?.let(onUpdateReasoningTokens)
    }

    ModalBottomSheet(
        onDismissRequest = {
            commitInput()
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
            visibleOptions.forEach { option ->
                ReasoningLevelCard(
                    selected = currentLevel == option.level,
                    icon = { Icon(option.icon, null) },
                    title = { Text(stringResource(id = option.titleRes)) },
                    description = { Text(stringResource(id = option.descRes)) },
                    onClick = {
                        input = option.level.budgetTokens.toString()
                        onUpdateReasoningTokens(option.level.budgetTokens)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    commitInput()
                                    onDismissRequest()
                                }
                            ),
                        )
                        TextButton(
                            onClick = {
                                commitInput()
                                onDismissRequest()
                            }
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
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
        var reasoningTokens by remember { mutableStateOf(0) }
        ReasoningPicker(
            onDismissRequest = {},
            reasoningTokens = reasoningTokens,
            onUpdateReasoningTokens = {
                reasoningTokens = it
            }
        )
    }
}
