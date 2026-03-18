package me.rerere.rikkahub.ui.pages.setting.components

import androidx.annotation.StringRes
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
    @param:StringRes val labelRes: Int,
)

private data class ActiveColorPicker(
    val mode: ThemeEditorMode,
    val token: CommonThemeToken,
)

private data class ThemeColorTokenGroup(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val tokens: List<CommonThemeToken>,
    val expandedByDefault: Boolean,
)

private data class ThemeShapeToken(
    val key: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val defaultValue: Dp,
    val range: ClosedFloatingPointRange<Float>,
)

private data class ThemeScaleToken(
    val key: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val defaultValue: Float,
    val range: ClosedFloatingPointRange<Float>,
)

private val PREVIEW_SWATCH_KEYS = listOf("primary", "surface", "surfaceContainerHigh", "outline")

private val VISUAL_THEME_COLOR_GROUPS = listOf(
    ThemeColorTokenGroup(
        id = "brand",
        titleRes = R.string.setting_display_page_custom_theme_group_brand_title,
        descriptionRes = R.string.setting_display_page_custom_theme_group_brand_desc,
        expandedByDefault = true,
        tokens = listOf(
            CommonThemeToken("primary", R.string.setting_display_page_custom_theme_token_primary_label),
            CommonThemeToken("onPrimary", R.string.setting_display_page_custom_theme_token_on_primary_label),
            CommonThemeToken("primaryContainer", R.string.setting_display_page_custom_theme_token_primary_container_label),
            CommonThemeToken("onPrimaryContainer", R.string.setting_display_page_custom_theme_token_on_primary_container_label),
            CommonThemeToken("inversePrimary", R.string.setting_display_page_custom_theme_token_inverse_primary_label),
        ),
    ),
    ThemeColorTokenGroup(
        id = "supporting",
        titleRes = R.string.setting_display_page_custom_theme_group_supporting_title,
        descriptionRes = R.string.setting_display_page_custom_theme_group_supporting_desc,
        expandedByDefault = false,
        tokens = listOf(
            CommonThemeToken("secondary", R.string.setting_display_page_custom_theme_token_secondary_label),
            CommonThemeToken("onSecondary", R.string.setting_display_page_custom_theme_token_on_secondary_label),
            CommonThemeToken("secondaryContainer", R.string.setting_display_page_custom_theme_token_secondary_container_label),
            CommonThemeToken("onSecondaryContainer", R.string.setting_display_page_custom_theme_token_on_secondary_container_label),
            CommonThemeToken("tertiary", R.string.setting_display_page_custom_theme_token_tertiary_label),
            CommonThemeToken("onTertiary", R.string.setting_display_page_custom_theme_token_on_tertiary_label),
            CommonThemeToken("tertiaryContainer", R.string.setting_display_page_custom_theme_token_tertiary_container_label),
            CommonThemeToken("onTertiaryContainer", R.string.setting_display_page_custom_theme_token_on_tertiary_container_label),
        ),
    ),
    ThemeColorTokenGroup(
        id = "surface",
        titleRes = R.string.setting_display_page_custom_theme_group_surface_title,
        descriptionRes = R.string.setting_display_page_custom_theme_group_surface_desc,
        expandedByDefault = true,
        tokens = listOf(
            CommonThemeToken("background", R.string.setting_display_page_custom_theme_token_background_label),
            CommonThemeToken("onBackground", R.string.setting_display_page_custom_theme_token_on_background_label),
            CommonThemeToken("surface", R.string.setting_display_page_custom_theme_token_surface_label),
            CommonThemeToken("onSurface", R.string.setting_display_page_custom_theme_token_on_surface_label),
            CommonThemeToken("surfaceVariant", R.string.setting_display_page_custom_theme_token_surface_variant_label),
            CommonThemeToken("onSurfaceVariant", R.string.setting_display_page_custom_theme_token_on_surface_variant_label),
            CommonThemeToken("surfaceContainerLowest", R.string.setting_display_page_custom_theme_token_surface_container_lowest_label),
            CommonThemeToken("surfaceContainerLow", R.string.setting_display_page_custom_theme_token_surface_container_low_label),
            CommonThemeToken("surfaceContainer", R.string.setting_display_page_custom_theme_token_surface_container_label),
            CommonThemeToken("surfaceContainerHigh", R.string.setting_display_page_custom_theme_token_surface_container_high_label),
            CommonThemeToken("surfaceContainerHighest", R.string.setting_display_page_custom_theme_token_surface_container_highest_label),
            CommonThemeToken("surfaceBright", R.string.setting_display_page_custom_theme_token_surface_bright_label),
            CommonThemeToken("surfaceDim", R.string.setting_display_page_custom_theme_token_surface_dim_label),
        ),
    ),
    ThemeColorTokenGroup(
        id = "utility",
        titleRes = R.string.setting_display_page_custom_theme_group_utility_title,
        descriptionRes = R.string.setting_display_page_custom_theme_group_utility_desc,
        expandedByDefault = false,
        tokens = listOf(
            CommonThemeToken("inverseSurface", R.string.setting_display_page_custom_theme_token_inverse_surface_label),
            CommonThemeToken("inverseOnSurface", R.string.setting_display_page_custom_theme_token_inverse_on_surface_label),
            CommonThemeToken("error", R.string.setting_display_page_custom_theme_token_error_label),
            CommonThemeToken("onError", R.string.setting_display_page_custom_theme_token_on_error_label),
            CommonThemeToken("errorContainer", R.string.setting_display_page_custom_theme_token_error_container_label),
            CommonThemeToken("onErrorContainer", R.string.setting_display_page_custom_theme_token_on_error_container_label),
            CommonThemeToken("outline", R.string.setting_display_page_custom_theme_token_outline_label),
            CommonThemeToken("outlineVariant", R.string.setting_display_page_custom_theme_token_outline_variant_label),
            CommonThemeToken("scrim", R.string.setting_display_page_custom_theme_token_scrim_label),
        ),
    ),
)

private val VISUAL_THEME_SHAPE_TOKENS = listOf(
    ThemeShapeToken(
        key = "shapeSmall",
        labelRes = R.string.setting_display_page_custom_theme_shape_small_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_small_desc,
        defaultValue = 12.dp,
        range = 0f..32f,
    ),
    ThemeShapeToken(
        key = "shapeMedium",
        labelRes = R.string.setting_display_page_custom_theme_shape_medium_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_medium_desc,
        defaultValue = 16.dp,
        range = 0f..40f,
    ),
    ThemeShapeToken(
        key = "shapeLarge",
        labelRes = R.string.setting_display_page_custom_theme_shape_large_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_large_desc,
        defaultValue = 24.dp,
        range = 0f..64f,
    ),
    ThemeShapeToken(
        key = "shapeExtraLarge",
        labelRes = R.string.setting_display_page_custom_theme_shape_extra_large_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_extra_large_desc,
        defaultValue = 32.dp,
        range = 0f..80f,
    ),
    ThemeShapeToken(
        key = "shapeLargeIncreased",
        labelRes = R.string.setting_display_page_custom_theme_shape_large_increased_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_large_increased_desc,
        defaultValue = 28.dp,
        range = 0f..80f,
    ),
    ThemeShapeToken(
        key = "shapeExtraLargeIncreased",
        labelRes = R.string.setting_display_page_custom_theme_shape_extra_large_increased_label,
        descriptionRes = R.string.setting_display_page_custom_theme_shape_extra_large_increased_desc,
        defaultValue = 36.dp,
        range = 0f..96f,
    ),
)

private val VISUAL_THEME_SCALE_TOKENS = listOf(
    ThemeScaleToken(
        key = "fontScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_font_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_font_desc,
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "displayScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_display_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_display_desc,
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "headlineScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_headline_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_headline_desc,
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "titleScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_title_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_title_desc,
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "bodyScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_body_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_body_desc,
        defaultValue = 1f,
        range = 0.8f..1.4f,
    ),
    ThemeScaleToken(
        key = "labelScale",
        labelRes = R.string.setting_display_page_custom_theme_scale_label_label,
        descriptionRes = R.string.setting_display_page_custom_theme_scale_label_desc,
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
            title = stringResource(picker.token.labelRes),
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
                        text = stringResource(R.string.setting_display_page_custom_theme_editor_summary_desc),
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
                ThemeCountPill(
                    label = stringResource(R.string.setting_display_page_custom_theme_mode_light),
                    value = stringResource(R.string.setting_display_page_custom_theme_count_tokens, lightTokenCount),
                )
                ThemeCountPill(
                    label = stringResource(R.string.setting_display_page_custom_theme_mode_dark),
                    value = stringResource(R.string.setting_display_page_custom_theme_count_tokens, darkTokenCount),
                )
                ThemeCountPill(
                    label = stringResource(R.string.setting_display_page_custom_theme_count_modes_label),
                    value = stringResource(R.string.setting_display_page_custom_theme_count_modes_value),
                )
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
                            ThemeEditorTab.COLORS -> stringResource(R.string.setting_display_page_custom_theme_tab_colors)
                            ThemeEditorTab.STYLE -> stringResource(R.string.setting_display_page_custom_theme_tab_style)
                            ThemeEditorTab.CODE -> stringResource(R.string.setting_display_page_custom_theme_tab_code)
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
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(group.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        expanded = !expanded
                    }
                ) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.setting_display_page_custom_theme_action_hide)
                        } else {
                            stringResource(R.string.setting_display_page_custom_theme_action_show)
                        }
                    )
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
                    text = stringResource(token.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(
                            if (isCustom) {
                                stringResource(R.string.setting_display_page_custom_theme_state_custom)
                            } else {
                                stringResource(R.string.setting_display_page_custom_theme_state_default)
                            }
                        )
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
                        Text(stringResource(R.string.setting_display_page_custom_theme_reset))
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
            title = stringResource(R.string.setting_display_page_custom_theme_style_shape_title),
            description = stringResource(R.string.setting_display_page_custom_theme_style_shape_desc),
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
            title = stringResource(R.string.setting_display_page_custom_theme_style_type_title),
            description = stringResource(R.string.setting_display_page_custom_theme_style_type_desc),
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
                    text = stringResource(token.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(stringResource(token.descriptionRes))
                        append(" · ")
                        append(
                            if (value != null) {
                                stringResource(R.string.setting_display_page_custom_theme_state_custom)
                            } else {
                                stringResource(R.string.setting_display_page_custom_theme_state_default)
                            }
                        )
                        append(" · ")
                        append(formatThemeDimensionTokenValue(effectiveValue))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value != null) {
                TextButton(onClick = { onValueChange(null) }) {
                    Text(stringResource(R.string.setting_display_page_custom_theme_reset))
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
                    text = stringResource(token.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(stringResource(token.descriptionRes))
                        append(" · ")
                        append(
                            if (value != null) {
                                stringResource(R.string.setting_display_page_custom_theme_state_custom)
                            } else {
                                stringResource(R.string.setting_display_page_custom_theme_state_default)
                            }
                        )
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
                    Text(stringResource(R.string.setting_display_page_custom_theme_reset))
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
                        stringResource(
                            R.string.setting_display_page_custom_theme_code_source_title,
                            stringResource(R.string.setting_display_page_custom_theme_mode_light),
                        )
                    } else {
                        stringResource(
                            R.string.setting_display_page_custom_theme_code_source_title,
                            stringResource(R.string.setting_display_page_custom_theme_mode_dark),
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_display_page_custom_theme_code_desc),
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
                        Text(
                            if (mode == ThemeEditorMode.LIGHT) {
                                stringResource(R.string.setting_display_page_custom_theme_insert_light_template)
                            } else {
                                stringResource(R.string.setting_display_page_custom_theme_insert_dark_template)
                            }
                        )
                    }
                    if (source.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onSourceChange("")
                            }
                        ) {
                            Text(stringResource(R.string.setting_display_page_custom_theme_code_clear))
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
                text = stringResource(R.string.setting_display_page_custom_theme_parser_title),
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
                    text = stringResource(
                        R.string.setting_display_page_custom_theme_parser_unsupported,
                        parseResult.unsupportedKeys.take(6).joinToString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (parseResult.invalidEntries.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.setting_display_page_custom_theme_parser_invalid,
                        parseResult.invalidEntries.take(3).joinToString(" | "),
                    ),
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_preview_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (darkMode) {
                                        stringResource(R.string.setting_display_page_custom_theme_preview_mode_dark)
                                    } else {
                                        stringResource(R.string.setting_display_page_custom_theme_preview_mode_light)
                                    },
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_preview_role),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.setting_display_page_custom_theme_preview_message),
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_preview_reply),
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_token_outline_label),
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_token_secondary_label),
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
                                    text = stringResource(R.string.setting_display_page_custom_theme_preview_input),
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
