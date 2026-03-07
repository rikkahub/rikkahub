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
    onUpdateReasoningTokens: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningTokens = reasoningTokens,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningTokens = onUpdateReasoningTokens
        )
    }

    val level = ReasoningLevel.fromBudgetTokens(reasoningTokens)
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
                    ReasoningLevel.LOW -> Icon(ReasoningLow, null)
                    ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null)
                    ReasoningLevel.HIGH -> Icon(ReasoningHigh, null)
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
) {
    val currentLevel = ReasoningLevel.fromBudgetTokens(reasoningTokens)
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
            ReasoningLevelCard(
                selected = currentLevel == ReasoningLevel.OFF,
                icon = {
                    Icon(HugeIcons.Idea, null)
                },
                title = {
                    Text(stringResource(id = R.string.reasoning_off))
                },
                description = {
                    Text(stringResource(id = R.string.reasoning_off_desc))
                },
                onClick = {
                    onUpdateReasoningTokens(0)
                }
            )
            ReasoningLevelCard(
                selected = currentLevel == ReasoningLevel.AUTO,
                icon = {
                    Icon(HugeIcons.Idea01, null)
                },
                title = {
                    Text(stringResource(id = R.string.reasoning_auto))
                },
                description = {
                    Text(stringResource(id = R.string.reasoning_auto_desc))
                },
                onClick = {
                    onUpdateReasoningTokens(-1)
                }
            )
            ReasoningLevelCard(
                selected = currentLevel == ReasoningLevel.LOW,
                icon = {
                    Icon(ReasoningLow, null)
                },
                title = {
                    Text(stringResource(id = R.string.reasoning_light))
                },
                description = {
                    Text(stringResource(id = R.string.reasoning_light_desc))
                },
                onClick = {
                    onUpdateReasoningTokens(1024)
                }
            )
            ReasoningLevelCard(
                selected = currentLevel == ReasoningLevel.MEDIUM,
                icon = {
                    Icon(ReasoningMedium, null)
                },
                title = {
                    Text(stringResource(id = R.string.reasoning_medium))
                },
                description = {
                    Text(stringResource(id = R.string.reasoning_medium_desc))
                },
                onClick = {
                    onUpdateReasoningTokens(16_000)
                }
            )
            ReasoningLevelCard(
                selected = currentLevel == ReasoningLevel.HIGH,
                icon = {
                    Icon(ReasoningHigh, null)
                },
                title = {
                    Text(stringResource(id = R.string.reasoning_heavy))
                },
                description = {
                    Text(stringResource(id = R.string.reasoning_heavy_desc))
                },
                onClick = {
                    onUpdateReasoningTokens(32_000)
                }
            )

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
