package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CustomThemeSetting
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalExtendColors
import me.rerere.rikkahub.ui.theme.Typography
import me.rerere.rikkahub.ui.theme.applyThemeTokenOverrides
import me.rerere.rikkahub.ui.theme.buildThemeTokenTemplate
import me.rerere.rikkahub.ui.theme.darkExtendColors
import me.rerere.rikkahub.ui.theme.lightExtendColors
import me.rerere.rikkahub.ui.theme.parseThemeColorString
import me.rerere.rikkahub.ui.theme.parseThemeTokenSource
import me.rerere.rikkahub.ui.theme.themeTokenColor
import me.rerere.rikkahub.ui.theme.upsertThemeTokenSource
import me.rerere.rikkahub.utils.toCssHex
import kotlin.math.roundToInt

private enum class ThemeEditorMode {
    LIGHT,
    DARK,
}

private data class CommonThemeToken(
    val key: String,
    val label: String,
)

private data class ActiveColorPicker(
    val mode: ThemeEditorMode,
    val token: CommonThemeToken,
)

private val VISUAL_THEME_TOKENS = listOf(
    CommonThemeToken(key = "primary", label = "Primary"),
    CommonThemeToken(key = "primaryContainer", label = "Primary Container"),
    CommonThemeToken(key = "background", label = "Background"),
    CommonThemeToken(key = "surface", label = "Surface"),
    CommonThemeToken(key = "surfaceContainer", label = "Surface Container"),
    CommonThemeToken(key = "surfaceVariant", label = "Surface Variant"),
    CommonThemeToken(key = "outline", label = "Outline"),
)

private val PRESET_PICKER_COLORS = listOf(
    Color(0xFF6750A4),
    Color(0xFF386A20),
    Color(0xFF0B57D0),
    Color(0xFFB3261E),
    Color(0xFFFF9800),
    Color(0xFF009688),
    Color(0xFF607D8B),
    Color(0xFFFFFFFF),
    Color(0xFF000000),
)

@Composable
fun CustomThemeSection(
    value: CustomThemeSetting,
    defaultLightScheme: ColorScheme,
    defaultDarkScheme: ColorScheme,
    modifier: Modifier = Modifier,
    onUpdate: (CustomThemeSetting) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    val lightResult = remember(value.light) { parseThemeTokenSource(value.light) }
    val darkResult = remember(value.dark) { parseThemeTokenSource(value.dark) }
    val previewColors = remember(value.enabled, value.light, value.dark, defaultLightScheme, defaultDarkScheme) {
        val lightSource = if (value.enabled) value.light else ""
        val darkSource = if (value.enabled) value.dark else ""
        listOf(
            parseThemeTokenSource(lightSource).overrides["primary"] ?: defaultLightScheme.themeTokenColor("primary"),
            parseThemeTokenSource(lightSource).overrides["surface"] ?: defaultLightScheme.themeTokenColor("surface"),
            parseThemeTokenSource(darkSource).overrides["primary"] ?: defaultDarkScheme.themeTokenColor("primary"),
            parseThemeTokenSource(darkSource).overrides["surface"] ?: defaultDarkScheme.themeTokenColor("surface"),
        )
    }

    CardGroup(
        modifier = modifier,
        title = {
            Text(stringResource(R.string.setting_display_page_custom_theme_section_title))
        },
    ) {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_display_page_custom_theme_enabled_title))
            },
            supportingContent = {
                Text(stringResource(R.string.setting_display_page_custom_theme_enabled_desc))
            },
            trailingContent = {
                Switch(
                    checked = value.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(value.copy(enabled = enabled))
                    },
                )
            },
        )
        item(
            onClick = {
                showEditor = true
            },
            headlineContent = {
                Text(stringResource(R.string.setting_display_page_custom_theme_edit_title))
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.setting_display_page_custom_theme_edit_desc,
                            lightResult.validCount,
                            darkResult.validCount,
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previewColors.forEach { color ->
                            ColorSwatch(
                                color = color,
                                size = 18.dp,
                            )
                        }
                    }
                }
            },
            trailingContent = {
                Text(stringResource(R.string.edit))
            },
        )
    }

    if (showEditor) {
        CustomThemeEditorDialog(
            initialValue = value,
            defaultLightScheme = defaultLightScheme,
            defaultDarkScheme = defaultDarkScheme,
            onDismiss = {
                showEditor = false
            },
            onSave = { updated ->
                onUpdate(updated)
                showEditor = false
            },
        )
    }
}

@Composable
private fun CustomThemeEditorDialog(
    initialValue: CustomThemeSetting,
    defaultLightScheme: ColorScheme,
    defaultDarkScheme: ColorScheme,
    onDismiss: () -> Unit,
    onSave: (CustomThemeSetting) -> Unit,
) {
    var enabled by remember(initialValue.enabled) { mutableStateOf(initialValue.enabled) }
    var selectedMode by remember { mutableStateOf(ThemeEditorMode.LIGHT) }
    var lightSource by remember(initialValue.light) { mutableStateOf(initialValue.light) }
    var darkSource by remember(initialValue.dark) { mutableStateOf(initialValue.dark) }
    var activeColorPicker by remember { mutableStateOf<ActiveColorPicker?>(null) }
    var showRawEditor by remember { mutableStateOf(false) }

    val currentSource = if (selectedMode == ThemeEditorMode.LIGHT) lightSource else darkSource
    val currentParseResult = remember(currentSource) { parseThemeTokenSource(currentSource) }
    val currentBaseScheme = if (selectedMode == ThemeEditorMode.LIGHT) defaultLightScheme else defaultDarkScheme
    val currentPreviewScheme = remember(currentBaseScheme, currentSource) {
        currentBaseScheme.applyThemeTokenOverrides(currentSource)
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 760.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_display_page_custom_theme_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.setting_display_page_custom_theme_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_display_page_custom_theme_enabled_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.setting_display_page_custom_theme_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }

                PrimaryTabRow(selectedTabIndex = selectedMode.ordinal) {
                    ThemeEditorMode.entries.forEach { mode ->
                        Tab(
                            selected = selectedMode == mode,
                            onClick = {
                                selectedMode = mode
                            },
                            text = {
                                Text(
                                    text = if (mode == ThemeEditorMode.LIGHT) {
                                        stringResource(R.string.setting_display_page_custom_theme_light_label)
                                    } else {
                                        stringResource(R.string.setting_display_page_custom_theme_dark_label)
                                    }
                                )
                            },
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePreviewCard(
                        colorScheme = currentPreviewScheme,
                        darkMode = selectedMode == ThemeEditorMode.DARK,
                    )

                    VISUAL_THEME_TOKENS.forEach { token ->
                        val overrideColor = currentParseResult.overrides[token.key]
                        val effectiveColor = overrideColor ?: currentBaseScheme.themeTokenColor(token.key)
                        ListItem(
                            headlineContent = {
                                Text(token.label)
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(
                                            if (overrideColor != null) {
                                                stringResource(R.string.setting_display_page_custom_theme_state_custom)
                                            } else {
                                                stringResource(R.string.setting_display_page_custom_theme_state_default)
                                            }
                                        )
                                        append(" · ")
                                        append(effectiveColor.toCssHex())
                                    }
                                )
                            },
                            trailingContent = {
                                ColorSwatch(
                                    color = effectiveColor,
                                    size = 32.dp,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeColorPicker = ActiveColorPicker(
                                        mode = selectedMode,
                                        token = token,
                                    )
                                },
                        )
                    }
                }

                Text(
                    text = stringResource(
                        R.string.setting_display_page_custom_theme_parse_hint,
                        currentParseResult.validCount,
                        currentParseResult.unsupportedKeys.size + currentParseResult.invalidEntries.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            showRawEditor = true
                        }
                    ) {
                        Text(stringResource(R.string.setting_display_page_custom_theme_advanced_edit))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                onSave(
                                    initialValue.copy(
                                        enabled = enabled,
                                        light = lightSource.trim(),
                                        dark = darkSource.trim(),
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    activeColorPicker?.let { picker ->
        val targetSource = if (picker.mode == ThemeEditorMode.LIGHT) lightSource else darkSource
        val targetBaseScheme = if (picker.mode == ThemeEditorMode.LIGHT) defaultLightScheme else defaultDarkScheme
        val parseResult = remember(targetSource) { parseThemeTokenSource(targetSource) }
        val overrideColor = parseResult.overrides[picker.token.key]
        val effectiveColor = overrideColor ?: targetBaseScheme.themeTokenColor(picker.token.key)

        ThemeColorPickerDialog(
            title = picker.token.label,
            initialColor = effectiveColor,
            defaultColor = targetBaseScheme.themeTokenColor(picker.token.key),
            hasOverride = overrideColor != null,
            onDismiss = {
                activeColorPicker = null
            },
            onApply = { color ->
                if (picker.mode == ThemeEditorMode.LIGHT) {
                    lightSource = upsertThemeTokenSource(lightSource, picker.token.key, color)
                } else {
                    darkSource = upsertThemeTokenSource(darkSource, picker.token.key, color)
                }
                activeColorPicker = null
            },
            onReset = {
                if (picker.mode == ThemeEditorMode.LIGHT) {
                    lightSource = upsertThemeTokenSource(lightSource, picker.token.key, null)
                } else {
                    darkSource = upsertThemeTokenSource(darkSource, picker.token.key, null)
                }
                activeColorPicker = null
            },
        )
    }

    if (showRawEditor) {
        RawThemeTokenEditorDialog(
            initialLight = lightSource,
            initialDark = darkSource,
            defaultLightScheme = defaultLightScheme,
            defaultDarkScheme = defaultDarkScheme,
            onDismiss = {
                showRawEditor = false
            },
            onSave = { light, dark ->
                lightSource = light
                darkSource = dark
                showRawEditor = false
            },
        )
    }
}

@Composable
private fun ThemePreviewCard(
    colorScheme: ColorScheme,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendColors = if (darkMode) darkExtendColors() else lightExtendColors()

    CompositionLocalProvider(
        LocalDarkMode provides darkMode,
        LocalExtendColors provides extendColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
        ) {
            Surface(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(24.dp),
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "Live Preview",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (darkMode) "Dark mode" else "Light mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "The visual editor is applying your theme tokens in real time.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "Looks better already.",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = "Outline",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "Secondary",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = "Message input",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "\u2191",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorPickerDialog(
    title: String,
    initialColor: Color,
    defaultColor: Color,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onApply: (Color) -> Unit,
    onReset: () -> Unit,
) {
    var pickerColor by remember(initialColor) { mutableStateOf(initialColor) }
    var hexInput by remember(initialColor) { mutableStateOf(initialColor.toCssHex()) }
    var hexError by remember { mutableStateOf(false) }

    fun updateColor(newColor: Color) {
        pickerColor = newColor
        hexInput = newColor.toCssHex()
        hexError = false
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.large,
                        )
                        .background(
                            color = pickerColor,
                            shape = MaterialTheme.shapes.large,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pickerColor.toCssHex(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pickerColor.luminance() > 0.55f) Color.Black else Color.White,
                    )
                }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { value ->
                        hexInput = value
                        val parsed = parseThemeColorString(value)
                        if (parsed != null) {
                            pickerColor = parsed
                            hexError = false
                        } else {
                            hexError = value.isNotBlank()
                        }
                    },
                    label = {
                        Text(stringResource(R.string.setting_display_page_custom_theme_hex_label))
                    },
                    placeholder = {
                        Text("#RRGGBBAA")
                    },
                    isError = hexError,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ThemeColorChannelSlider(
                    label = "R",
                    value = colorComponent(pickerColor.red),
                    onValueChange = { value ->
                        updateColor(pickerColor.copy(red = value / 255f))
                    },
                )
                ThemeColorChannelSlider(
                    label = "G",
                    value = colorComponent(pickerColor.green),
                    onValueChange = { value ->
                        updateColor(pickerColor.copy(green = value / 255f))
                    },
                )
                ThemeColorChannelSlider(
                    label = "B",
                    value = colorComponent(pickerColor.blue),
                    onValueChange = { value ->
                        updateColor(pickerColor.copy(blue = value / 255f))
                    },
                )
                ThemeColorChannelSlider(
                    label = "A",
                    value = colorComponent(pickerColor.alpha),
                    onValueChange = { value ->
                        updateColor(pickerColor.copy(alpha = value / 255f))
                    },
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_display_page_custom_theme_presets),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        (listOf(defaultColor) + PRESET_PICKER_COLORS).forEach { preset ->
                            ColorSwatch(
                                color = preset,
                                size = 32.dp,
                                selected = sameColor(pickerColor, preset),
                                onClick = {
                                    updateColor(preset)
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (hasOverride) {
                        TextButton(onClick = onReset) {
                            Text(stringResource(R.string.setting_display_page_custom_theme_reset))
                        }
                    } else {
                        Box(modifier = Modifier)
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (!hexError) {
                                    onApply(pickerColor)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawThemeTokenEditorDialog(
    initialLight: String,
    initialDark: String,
    defaultLightScheme: ColorScheme,
    defaultDarkScheme: ColorScheme,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val lightState = rememberTextFieldState(initialText = initialLight)
    val darkState = rememberTextFieldState(initialText = initialDark)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 760.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_display_page_custom_theme_raw_editor_title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                Text(
                    text = stringResource(R.string.setting_display_page_custom_theme_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ThemeTemplateInsertButtons(
                    lightState = lightState,
                    darkState = darkState,
                    defaultLightScheme = defaultLightScheme,
                    defaultDarkScheme = defaultDarkScheme,
                )

                TextArea(
                    state = lightState,
                    label = stringResource(R.string.setting_display_page_custom_theme_light_label),
                    placeholder = buildThemeTokenTemplate(defaultLightScheme),
                    minLines = 6,
                    maxLines = 10,
                )

                TextArea(
                    state = darkState,
                    label = stringResource(R.string.setting_display_page_custom_theme_dark_label),
                    placeholder = buildThemeTokenTemplate(defaultDarkScheme),
                    minLines = 6,
                    maxLines = 10,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                lightState.text.toString().trim(),
                                darkState.text.toString().trim(),
                            )
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeTemplateInsertButtons(
    lightState: TextFieldState,
    darkState: TextFieldState,
    defaultLightScheme: ColorScheme,
    defaultDarkScheme: ColorScheme,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = {
                lightState.setTextAndPlaceCursorAtEnd(buildThemeTokenTemplate(defaultLightScheme))
            }
        ) {
            Text(stringResource(R.string.setting_display_page_custom_theme_insert_light_template))
        }
        TextButton(
            onClick = {
                darkState.setTextAndPlaceCursorAtEnd(buildThemeTokenTemplate(defaultDarkScheme))
            }
        ) {
            Text(stringResource(R.string.setting_display_page_custom_theme_insert_dark_template))
        }
    }
}

@Composable
private fun ThemeColorChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { sliderValue ->
                onValueChange(sliderValue.roundToInt().coerceIn(0, 255))
            },
            valueRange = 0f..255f,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .background(color = color, shape = CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}

private fun colorComponent(value: Float): Int {
    return (value * 255f).roundToInt().coerceIn(0, 255)
}

private fun sameColor(left: Color, right: Color): Boolean {
    return colorComponent(left.red) == colorComponent(right.red) &&
        colorComponent(left.green) == colorComponent(right.green) &&
        colorComponent(left.blue) == colorComponent(right.blue) &&
        colorComponent(left.alpha) == colorComponent(right.alpha)
}
