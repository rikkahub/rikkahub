package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes as MaterialShapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CustomThemeSetting
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalExtendColors
import me.rerere.rikkahub.ui.theme.Typography
import me.rerere.rikkahub.ui.theme.applyThemeTokenOverrides
import me.rerere.rikkahub.ui.theme.buildThemeTokenTemplate
import me.rerere.rikkahub.ui.theme.darkExtendColors
import me.rerere.rikkahub.ui.theme.formatThemeDimensionTokenValue
import me.rerere.rikkahub.ui.theme.formatThemeScaleTokenValue
import me.rerere.rikkahub.ui.theme.lightExtendColors
import me.rerere.rikkahub.ui.theme.parseThemeColorString
import me.rerere.rikkahub.ui.theme.parseThemeTokenSource
import me.rerere.rikkahub.ui.theme.themeTokenColor
import me.rerere.rikkahub.ui.theme.upsertThemeTokenSource
import me.rerere.rikkahub.utils.toCssHex

private enum class ThemeEditorMode {
    LIGHT,
    DARK,
}

private enum class ThemeEditorTab {
    COLORS,
    STYLE,
    CODE,
}

private data class CommonThemeToken(
    val key: String,
    val label: String,
)

private data class ActiveColorPicker(
    val mode: ThemeEditorMode,
    val token: CommonThemeToken,
)

private data class ThemeColorTokenGroup(
    val id: String,
    val title: String,
    val description: String,
    val tokens: List<CommonThemeToken>,
    val expandedByDefault: Boolean,
)

private data class ThemeShapeToken(
    val key: String,
    val label: String,
    val description: String,
    val defaultValue: Dp,
    val range: ClosedFloatingPointRange<Float>,
)

private data class ThemeScaleToken(
    val key: String,
    val label: String,
    val description: String,
    val defaultValue: Float,
    val range: ClosedFloatingPointRange<Float>,
)

private val PREVIEW_SWATCH_KEYS = listOf("primary", "surface", "surfaceContainerHigh", "outline")

private val VISUAL_THEME_COLOR_GROUPS = listOf(
    ThemeColorTokenGroup(
        id = "brand",
        title = "Brand",
        description = "Primary emphasis colors and their readable foregrounds.",
        expandedByDefault = true,
        tokens = listOf(
            CommonThemeToken("primary", "Primary"),
            CommonThemeToken("onPrimary", "On Primary"),
            CommonThemeToken("primaryContainer", "Primary Container"),
            CommonThemeToken("onPrimaryContainer", "On Primary Container"),
            CommonThemeToken("inversePrimary", "Inverse Primary"),
        ),
    ),
    ThemeColorTokenGroup(
        id = "supporting",
        title = "Secondary And Tertiary",
        description = "Supporting accents for chips, cards, and alternate callouts.",
        expandedByDefault = false,
        tokens = listOf(
            CommonThemeToken("secondary", "Secondary"),
            CommonThemeToken("onSecondary", "On Secondary"),
            CommonThemeToken("secondaryContainer", "Secondary Container"),
            CommonThemeToken("onSecondaryContainer", "On Secondary Container"),
            CommonThemeToken("tertiary", "Tertiary"),
            CommonThemeToken("onTertiary", "On Tertiary"),
            CommonThemeToken("tertiaryContainer", "Tertiary Container"),
            CommonThemeToken("onTertiaryContainer", "On Tertiary Container"),
        ),
    ),
    ThemeColorTokenGroup(
        id = "surface",
        title = "Surfaces",
        description = "Background layers and text colors used across the UI.",
        expandedByDefault = true,
        tokens = listOf(
            CommonThemeToken("background", "Background"),
            CommonThemeToken("onBackground", "On Background"),
            CommonThemeToken("surface", "Surface"),
            CommonThemeToken("onSurface", "On Surface"),
            CommonThemeToken("surfaceVariant", "Surface Variant"),
            CommonThemeToken("onSurfaceVariant", "On Surface Variant"),
            CommonThemeToken("surfaceContainerLowest", "Surface Container Lowest"),
            CommonThemeToken("surfaceContainerLow", "Surface Container Low"),
            CommonThemeToken("surfaceContainer", "Surface Container"),
            CommonThemeToken("surfaceContainerHigh", "Surface Container High"),
            CommonThemeToken("surfaceContainerHighest", "Surface Container Highest"),
            CommonThemeToken("surfaceBright", "Surface Bright"),
            CommonThemeToken("surfaceDim", "Surface Dim"),
        ),
    ),
    ThemeColorTokenGroup(
        id = "utility",
        title = "Utility And Feedback",
        description = "Error colors, outlines, scrims, and inverse surfaces.",
        expandedByDefault = false,
        tokens = listOf(
            CommonThemeToken("inverseSurface", "Inverse Surface"),
            CommonThemeToken("inverseOnSurface", "Inverse On Surface"),
            CommonThemeToken("error", "Error"),
            CommonThemeToken("onError", "On Error"),
            CommonThemeToken("errorContainer", "Error Container"),
            CommonThemeToken("onErrorContainer", "On Error Container"),
            CommonThemeToken("outline", "Outline"),
            CommonThemeToken("outlineVariant", "Outline Variant"),
            CommonThemeToken("scrim", "Scrim"),
        ),
    ),
)

private val VISUAL_THEME_SHAPE_TOKENS = listOf(
    ThemeShapeToken(
        key = "shapeSmall",
        label = "Small radius",
        description = "Compact chips and small inputs.",
        defaultValue = 12.dp,
        range = 0f..32f,
    ),
    ThemeShapeToken(
        key = "shapeMedium",
        label = "Medium radius",
        description = "Standard cards and mid-sized surfaces.",
        defaultValue = 16.dp,
        range = 0f..40f,
    ),
    ThemeShapeToken(
        key = "shapeLarge",
        label = "Large radius",
        description = "Message bubbles and large cards.",
        defaultValue = 24.dp,
        range = 0f..64f,
    ),
    ThemeShapeToken(
        key = "shapeExtraLarge",
        label = "Extra large radius",
        description = "Hero containers and prominent panels.",
        defaultValue = 32.dp,
        range = 0f..80f,
    ),
    ThemeShapeToken(
        key = "shapeLargeIncreased",
        label = "Large increased",
        description = "Expressive variants used by larger controls.",
        defaultValue = 28.dp,
        range = 0f..80f,
    ),
    ThemeShapeToken(
        key = "shapeExtraLargeIncreased",
        label = "Extra large increased",
        description = "Extra expressive radius for large panels.",
        defaultValue = 36.dp,
        range = 0f..96f,
    ),
)

private val VISUAL_THEME_SCALE_TOKENS = listOf(
    ThemeScaleToken(
        key = "fontScale",
        label = "Global scale",
        description = "Scales every text style before per-group adjustments.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "displayScale",
        label = "Display scale",
        description = "Huge marketing or splash typography.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "headlineScale",
        label = "Headline scale",
        description = "Large section titles and dialogs.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "titleScale",
        label = "Title scale",
        description = "Card titles and compact section headers.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "bodyScale",
        label = "Body scale",
        description = "Most chat and settings copy.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "labelScale",
        label = "Label scale",
        description = "Chips, helper text, and small labels.",
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
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
    val previewColors = remember(value.enabled, lightResult, darkResult, defaultLightScheme, defaultDarkScheme) {
        val lightOverrides = if (value.enabled) lightResult.overrides else emptyMap()
        val darkOverrides = if (value.enabled) darkResult.overrides else emptyMap()
        buildList {
            PREVIEW_SWATCH_KEYS.forEach { key ->
                add(lightOverrides[key] ?: defaultLightScheme.themeTokenColor(key))
            }
            PREVIEW_SWATCH_KEYS.forEach { key ->
                add(darkOverrides[key] ?: defaultDarkScheme.themeTokenColor(key))
            }
        }
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
    var selectedMode by rememberSaveable { mutableStateOf(ThemeEditorMode.LIGHT) }
    var selectedTab by rememberSaveable { mutableStateOf(ThemeEditorTab.COLORS) }
    var lightSource by remember(initialValue.light) { mutableStateOf(initialValue.light) }
    var darkSource by remember(initialValue.dark) { mutableStateOf(initialValue.dark) }
    var activeColorPicker by remember { mutableStateOf<ActiveColorPicker?>(null) }

    val lightParseResult = remember(lightSource) { parseThemeTokenSource(lightSource) }
    val darkParseResult = remember(darkSource) { parseThemeTokenSource(darkSource) }
    val currentSource = if (selectedMode == ThemeEditorMode.LIGHT) lightSource else darkSource
    val currentParseResult = if (selectedMode == ThemeEditorMode.LIGHT) lightParseResult else darkParseResult
    val currentBaseScheme = if (selectedMode == ThemeEditorMode.LIGHT) defaultLightScheme else defaultDarkScheme
    val currentPreviewScheme = remember(currentBaseScheme, currentParseResult) {
        currentBaseScheme.applyThemeTokenOverrides(currentParseResult)
    }
    val currentPreviewShapes = remember(currentParseResult) {
        AppShapes.applyThemeTokenOverrides(currentParseResult)
    }
    val currentPreviewTypography = remember(currentParseResult) {
        Typography.applyThemeTokenOverrides(currentParseResult)
    }

    fun updateSource(newSource: String) {
        if (selectedMode == ThemeEditorMode.LIGHT) {
            lightSource = newSource
        } else {
            darkSource = newSource
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(stringResource(R.string.setting_display_page_custom_theme_dialog_title))
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        },
                        actions = {
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
                        },
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ThemeEditorSummaryCard(
                        enabled = enabled,
                        lightTokenCount = lightParseResult.validCount,
                        darkTokenCount = darkParseResult.validCount,
                        onEnabledChange = { enabled = it },
                    )

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

                    ThemeEditorTabRow(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                    )

                    if (selectedTab != ThemeEditorTab.CODE) {
                        ThemePreviewCard(
                            colorScheme = currentPreviewScheme,
                            shapes = currentPreviewShapes,
                            typography = currentPreviewTypography,
                            darkMode = selectedMode == ThemeEditorMode.DARK,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (selectedTab) {
                            ThemeEditorTab.COLORS -> {
                                ThemeColorsTab(
                                    parseResult = currentParseResult,
                                    baseScheme = currentBaseScheme,
                                    onPickColor = { token ->
                                        activeColorPicker = ActiveColorPicker(
                                            mode = selectedMode,
                                            token = token,
                                        )
                                    },
                                    onResetColor = { token ->
                                        updateSource(
                                            upsertThemeTokenSource(
                                                source = currentSource,
                                                key = token.key,
                                                color = null,
                                            )
                                        )
                                    },
                                )
                            }

                            ThemeEditorTab.STYLE -> {
                                ThemeStyleTab(
                                    parseResult = currentParseResult,
                                    onShapeChange = { key, value ->
                                        updateSource(
                                            upsertThemeTokenSource(
                                                source = currentSource,
                                                key = key,
                                                value = value?.let(::formatThemeDimensionTokenValue),
                                            )
                                        )
                                    },
                                    onScaleChange = { key, value ->
                                        updateSource(
                                            upsertThemeTokenSource(
                                                source = currentSource,
                                                key = key,
                                                value = value?.let(::formatThemeScaleTokenValue),
                                            )
                                        )
                                    },
                                )
                            }

                            ThemeEditorTab.CODE -> {
                                ThemeCodeTab(
                                    mode = selectedMode,
                                    source = currentSource,
                                    parseResult = currentParseResult,
                                    baseScheme = currentBaseScheme,
                                    onSourceChange = ::updateSource,
                                )
                            }
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

        ThemeColorPickerSheet(
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
                    lightSource = upsertThemeTokenSource(lightSource, picker.token.key, color = null)
                } else {
                    darkSource = upsertThemeTokenSource(darkSource, picker.token.key, color = null)
                }
                activeColorPicker = null
            },
        )
    }
}

@Composable
private fun ThemeEditorSummaryCard(
    enabled: Boolean,
    lightTokenCount: Int,
    darkTokenCount: Int,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
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
                        text = "Edit colors, radius, and type in one place without leaving the preview.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeCountPill(label = "Light", value = "$lightTokenCount tokens")
                ThemeCountPill(label = "Dark", value = "$darkTokenCount tokens")
                ThemeCountPill(label = "Modes", value = "Visual and code")
            }
        }
    }
}

@Composable
private fun ThemeCountPill(
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeEditorTabRow(
    selectedTab: ThemeEditorTab,
    onTabSelected: (ThemeEditorTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ThemeEditorTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ThemeEditorTab.entries.size,
                ),
                selected = selectedTab == tab,
                onClick = {
                    onTabSelected(tab)
                },
                label = {
                    Text(
                        text = when (tab) {
                            ThemeEditorTab.COLORS -> "Colors"
                            ThemeEditorTab.STYLE -> "Style"
                            ThemeEditorTab.CODE -> "Code"
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun ThemeColorsTab(
    parseResult: me.rerere.rikkahub.ui.theme.ThemeTokenParseResult,
    baseScheme: ColorScheme,
    onPickColor: (CommonThemeToken) -> Unit,
    onResetColor: (CommonThemeToken) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VISUAL_THEME_COLOR_GROUPS.forEach { group ->
            ThemeColorGroupCard(
                group = group,
                parseResult = parseResult,
                baseScheme = baseScheme,
                onPickColor = onPickColor,
                onResetColor = onResetColor,
            )
        }

        ThemeParseStatusCard(parseResult = parseResult)
    }
}

@Composable
private fun ThemeColorGroupCard(
    group: ThemeColorTokenGroup,
    parseResult: me.rerere.rikkahub.ui.theme.ThemeTokenParseResult,
    baseScheme: ColorScheme,
    onPickColor: (CommonThemeToken) -> Unit,
    onResetColor: (CommonThemeToken) -> Unit,
) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(group.expandedByDefault) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        expanded = !expanded
                    }
                ) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.tokens.forEach { token ->
                        val overrideColor = parseResult.overrides[token.key]
                        val effectiveColor = overrideColor ?: baseScheme.themeTokenColor(token.key)
                        ThemeColorTokenRow(
                            token = token,
                            color = effectiveColor,
                            isCustom = overrideColor != null,
                            onClick = {
                                onPickColor(token)
                            },
                            onReset = if (overrideColor != null) {
                                {
                                    onResetColor(token)
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorTokenRow(
    token: CommonThemeToken,
    color: Color,
    isCustom: Boolean,
    onClick: () -> Unit,
    onReset: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (isCustom) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = token.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(if (isCustom) "Custom" else "Default")
                        append(" · ")
                        append(color.toCssHex())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onReset != null) {
                    TextButton(onClick = onReset) {
                        Text("Reset")
                    }
                }
                ColorSwatch(
                    color = color,
                    size = 30.dp,
                )
            }
        }
    }
}

@Composable
private fun ThemeStyleTab(
    parseResult: me.rerere.rikkahub.ui.theme.ThemeTokenParseResult,
    onShapeChange: (String, Dp?) -> Unit,
    onScaleChange: (String, Float?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ThemeSliderCard(
            title = "Corner radius",
            description = "These sliders expose the shape tokens that were previously only reachable in raw code.",
        ) {
            VISUAL_THEME_SHAPE_TOKENS.forEach { token ->
                ThemeShapeSliderRow(
                    token = token,
                    value = parseResult.shapeOverrides[token.key],
                    onValueChange = { onShapeChange(token.key, it) },
                )
            }
        }

        ThemeSliderCard(
            title = "Typography scale",
            description = "Scale the entire type ramp or just one text group.",
        ) {
            VISUAL_THEME_SCALE_TOKENS.forEach { token ->
                ThemeScaleSliderRow(
                    token = token,
                    value = parseResult.scaleOverrides[token.key],
                    onValueChange = { onScaleChange(token.key, it) },
                )
            }
        }

        ThemeParseStatusCard(parseResult = parseResult)
    }
}

@Composable
private fun ThemeSliderCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            content()
        }
    }
}

@Composable
private fun ThemeShapeSliderRow(
    token: ThemeShapeToken,
    value: Dp?,
    onValueChange: (Dp?) -> Unit,
) {
    val effectiveValue = value ?: token.defaultValue
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = token.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(token.description)
                        append(" · ")
                        append(if (value != null) "Custom" else "Default")
                        append(" · ")
                        append(formatThemeDimensionTokenValue(effectiveValue))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value != null) {
                TextButton(onClick = { onValueChange(null) }) {
                    Text("Reset")
                }
            }
        }
        Slider(
            value = effectiveValue.value,
            onValueChange = { sliderValue ->
                onValueChange(sliderValue.roundToInt().dp)
            },
            valueRange = token.range,
            steps = (token.range.endInclusive - token.range.start).roundToInt().coerceAtLeast(1) - 1,
        )
    }
}

@Composable
private fun ThemeScaleSliderRow(
    token: ThemeScaleToken,
    value: Float?,
    onValueChange: (Float?) -> Unit,
) {
    val effectiveValue = value ?: token.defaultValue
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = token.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(token.description)
                        append(" · ")
                        append(if (value != null) "Custom" else "Default")
                        append(" · ")
                        append((effectiveValue * 100).roundToInt())
                        append("%")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value != null) {
                TextButton(onClick = { onValueChange(null) }) {
                    Text("Reset")
                }
            }
        }
        Slider(
            value = effectiveValue,
            onValueChange = { sliderValue ->
                val snappedValue = (sliderValue * 100f).roundToInt() / 100f
                onValueChange(snappedValue)
            },
            valueRange = token.range,
            steps = 59,
        )
    }
}

@Composable
private fun ThemeCodeTab(
    mode: ThemeEditorMode,
    source: String,
    parseResult: me.rerere.rikkahub.ui.theme.ThemeTokenParseResult,
    baseScheme: ColorScheme,
    onSourceChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.large,
                ),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (mode == ThemeEditorMode.LIGHT) {
                        "Light token source"
                    } else {
                        "Dark token source"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Use raw editing when you need exact token names, comments, or overrides not surfaced visually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            onSourceChange(buildThemeTokenTemplate(baseScheme))
                        }
                    ) {
                        Text("Insert template")
                    }
                    if (source.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onSourceChange("")
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        ThemeParseStatusCard(parseResult = parseResult)

        OutlinedTextField(
            value = source,
            onValueChange = onSourceChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = {
                Text(
                    text = buildThemeTokenTemplate(baseScheme),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetbrainsMono),
        )
    }
}

@Composable
private fun ThemeParseStatusCard(
    parseResult: me.rerere.rikkahub.ui.theme.ThemeTokenParseResult,
) {
    val ignoredCount = parseResult.unsupportedKeys.size + parseResult.invalidEntries.size
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Parser status",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.setting_display_page_custom_theme_parse_hint,
                    parseResult.validCount,
                    ignoredCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (parseResult.unsupportedKeys.isNotEmpty()) {
                Text(
                    text = "Unsupported: ${parseResult.unsupportedKeys.take(6).joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (parseResult.invalidEntries.isNotEmpty()) {
                Text(
                    text = "Invalid: ${parseResult.invalidEntries.take(3).joinToString(" | ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    colorScheme: ColorScheme,
    shapes: MaterialShapes,
    typography: MaterialTypography,
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
            shapes = shapes,
            typography = typography,
        ) {
            Surface(
                modifier = modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraLarge)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(color = MaterialTheme.colorScheme.surfaceContainer)
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
                            shape = MaterialTheme.shapes.medium,
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
                                    text = "The visual editor now covers colors, radius, and typography in one screen.",
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
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "Editing finally feels usable.",
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
                                shape = MaterialTheme.shapes.small,
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
                                shape = MaterialTheme.shapes.small,
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
                                shape = MaterialTheme.shapes.large,
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
private fun ThemeColorPickerSheet(
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
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
                    color = if (pickerColor.luminance() > 0.4f) Color.Black else Color.White,
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
    size: Dp,
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
            ),
    )
}

private fun colorComponent(value: Float): Int {
    return (value * 255f).roundToInt().coerceIn(0, 255)
}

private fun sameColor(first: Color, second: Color): Boolean {
    return colorComponent(first.red) == colorComponent(second.red) &&
        colorComponent(first.green) == colorComponent(second.green) &&
        colorComponent(first.blue) == colorComponent(second.blue) &&
        colorComponent(first.alpha) == colorComponent(second.alpha)
}
