package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.PresetTheme
import me.rerere.rikkahub.ui.theme.PresetThemeType
import me.rerere.rikkahub.ui.theme.PresetThemes

@Composable
fun PresetThemeButton(
    theme: PresetTheme,
    type: PresetThemeType,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.getColorScheme(type, darkMode)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = {
                    onClick()
                }
            )
            .padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(64.dp)
            ) {
                drawRect(
                    color = scheme.primaryContainer,
                    size = size
                )
                drawRect(
                    color = scheme.secondaryContainer,
                    size = size,
                    topLeft = Offset(
                        x = size.width / 2,
                        y = 0f
                    ),
                )
                drawRect(
                    color = scheme.tertiaryContainer,
                    size = size,
                    topLeft = Offset(
                        x = size.width / 2,
                        y = size.height / 2
                    ),
                )
                drawCircle(
                    color = scheme.primary,
                    radius = if (selected) 15.dp.toPx() else 10.dp.toPx(),
                    center = Offset(
                        x = size.width / 2,
                        y = size.height / 2
                    )
                )
            }
            if (selected) {
                Icon(
                    Lucide.Check,
                    contentDescription = null,
                    tint = scheme.contentColorFor(scheme.onPrimary)
                )
            }
        }
        ProvideTextStyle(
            MaterialTheme.typography.labelMedium.copy(color = scheme.primary)
        ) {
            theme.name()
        }
    }
}

@Composable
fun PresetThemeButtonGroup(
    themeId: String,
    type: PresetThemeType,
    modifier: Modifier = Modifier,
    onChangeType: (PresetThemeType) -> Unit,
    onChangeTheme: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            PresetThemes.fastForEach { theme ->
                PresetThemeButton(
                    theme = theme,
                    type = type,
                    selected = theme.id == themeId,
                    onClick = {
                        onChangeTheme(theme.id)
                    },
                )
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            PresetThemeType.entries.fastForEachIndexed { index, themeType ->
                SegmentedButton(
                    selected = type == themeType,
                    onClick = { onChangeType(themeType) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PresetThemeType.entries.size
                    ),
                ) {
                    val text = when (themeType) {
                        PresetThemeType.STANDARD -> stringResource(R.string.setting_page_theme_type_standard)
                        PresetThemeType.MEDIUM_CONTRAST -> stringResource(R.string.setting_page_theme_type_medium_contrast)
                        PresetThemeType.HIGH_CONTRAST -> stringResource(R.string.setting_page_theme_type_high_contrast)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PresetThemeButtonPreview() {
    var type by remember { mutableStateOf(PresetThemeType.STANDARD) }
    var themeId by remember { mutableStateOf("ocean") }
    PresetThemeButtonGroup(
        themeId = themeId,
        type = type,
        onChangeType = { type = it },
        onChangeTheme = { themeId = it }
    )
}
