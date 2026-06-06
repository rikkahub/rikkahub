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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun CustomThemeButtonGroup(
    themes: List<CustomTheme>,
    selectedThemeId: String,
    modifier: Modifier = Modifier,
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
            themes.fastForEach { theme ->
                CustomThemeButton(
                    theme = theme,
                    selected = theme.id == selectedThemeId,
                    onClick = { onChangeTheme(theme.id) },
                )
            }
        }
    }
}

@Composable
private fun CustomThemeButton(
    theme: CustomTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.generateColorScheme(darkMode)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(48.dp)
            ) {
                drawRect(color = scheme.primaryContainer, size = size)
                drawRect(
                    color = scheme.secondaryContainer,
                    size = size,
                    topLeft = Offset(x = size.width / 2, y = 0f),
                )
                drawRect(
                    color = scheme.tertiaryContainer,
                    size = size,
                    topLeft = Offset(x = size.width / 2, y = size.height / 2),
                )
                drawCircle(
                    color = scheme.primary,
                    radius = if (selected) 12.dp.toPx() else 8.dp.toPx(),
                    center = Offset(x = size.width / 2, y = size.height / 2)
                )
            }
            if (selected) {
                Icon(
                    HugeIcons.Tick01,
                    contentDescription = null,
                    tint = scheme.onPrimary
                )
            }
        }
        Text(
            text = theme.name.ifEmpty { "?" },
            style = MaterialTheme.typography.labelMedium.copy(color = scheme.primary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
