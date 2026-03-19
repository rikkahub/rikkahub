package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes as MaterialShapes
import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.toCssHex
import java.util.Locale

private val THEME_TOKEN_LINE_REGEX = Regex(
    pattern = """^\s*([A-Za-z-][A-Za-z0-9_-]*)\s*:\s*([^;]+?)\s*;?\s*$""",
    options = setOf(RegexOption.MULTILINE),
)
private val THEME_TOKEN_NORMALIZE_REGEX = Regex("""[\s_-]""")
private val THEME_DP_VALUE_REGEX = Regex("""^(-?\d+(?:\.\d+)?)(dp)?$""", RegexOption.IGNORE_CASE)
private val THEME_SCALE_VALUE_REGEX = Regex("""^(-?\d+(?:\.\d+)?)(%)?$""", RegexOption.IGNORE_CASE)

private const val MIN_THEME_TEXT_SCALE = 0.7f
private const val MAX_THEME_TEXT_SCALE = 1.6f
private val MAX_THEME_RADIUS = 96.dp
private val SURFACE_FAMILY_TOKEN_KEYS = listOf(
    "surface",
    "surfaceBright",
    "surfaceDim",
    "surfaceContainerLowest",
    "surfaceContainerLow",
    "surfaceContainer",
    "surfaceContainerHigh",
    "surfaceContainerHighest",
)

val COMMON_THEME_TOKEN_KEYS = listOf(
    "primary",
    "primaryContainer",
    "background",
    "surface",
    "surfaceContainer",
    "surfaceContainerHigh",
    "surfaceVariant",
    "outline",
)

private val COMMON_THEME_STYLE_TEMPLATE_LINES = listOf(
    "// shapeMedium: 16dp;",
    "// shapeLarge: 24dp;",
    "// shapeExtraLarge: 32dp;",
    "// fontScale: 1.05;",
    "// headlineScale: 1.10;",
    "// titleScale: 1.08;",
    "// bodyScale: 0.98;",
    "// labelScale: 0.95;",
)

private enum class ThemeTokenType {
    COLOR,
    SHAPE,
    SCALE,
}

enum class ThemeTokenTextScaleGroup {
    DISPLAY,
    HEADLINE,
    TITLE,
    BODY,
    LABEL,
}

private data class ThemeTokenDescriptor(
    val key: String,
    val type: ThemeTokenType,
)

data class ThemeTokenParseResult(
    val overrides: Map<String, Color>,
    val shapeOverrides: Map<String, Dp> = emptyMap(),
    val scaleOverrides: Map<String, Float> = emptyMap(),
    val unsupportedKeys: Set<String> = emptySet(),
    val invalidEntries: List<String> = emptyList(),
) {
    val validCount: Int
        get() = overrides.size + shapeOverrides.size + scaleOverrides.size

    val hasStyleOverrides: Boolean
        get() = shapeOverrides.isNotEmpty() || scaleOverrides.isNotEmpty()
}

fun buildThemeTokenTemplate(colorScheme: ColorScheme): String {
    val colorLines = COMMON_THEME_TOKEN_KEYS.map { key ->
        "$key: ${colorScheme.themeTokenColor(key).toCssHex()};"
    }
    return buildList {
        addAll(colorLines)
        add("")
        add("// Shape and typography tokens")
        addAll(COMMON_THEME_STYLE_TEMPLATE_LINES)
    }.joinToString(separator = "\n").trim()
}

fun parseThemeTokenSource(source: String): ThemeTokenParseResult {
    if (source.isBlank()) {
        return ThemeTokenParseResult(overrides = emptyMap())
    }

    val overrides = linkedMapOf<String, Color>()
    val shapeOverrides = linkedMapOf<String, Dp>()
    val scaleOverrides = linkedMapOf<String, Float>()
    val unsupportedKeys = linkedSetOf<String>()
    val invalidEntries = mutableListOf<String>()

    THEME_TOKEN_LINE_REGEX.findAll(source).forEach { match ->
        val rawKey = match.groupValues[1]
        val rawValue = match.groupValues[2].trim()
        val descriptor = normalizeThemeTokenKey(rawKey)

        if (descriptor == null) {
            unsupportedKeys += rawKey
            return@forEach
        }

        when (descriptor.type) {
            ThemeTokenType.COLOR -> {
                val color = parseThemeColor(rawValue)
                if (color == null) {
                    invalidEntries += match.value.trim()
                    return@forEach
                }
                overrides[descriptor.key] = color
            }

            ThemeTokenType.SHAPE -> {
                val shapeValue = parseThemeDimension(rawValue)
                if (shapeValue == null) {
                    invalidEntries += match.value.trim()
                    return@forEach
                }
                shapeOverrides[descriptor.key] = shapeValue
            }

            ThemeTokenType.SCALE -> {
                val scaleValue = parseThemeScale(rawValue)
                if (scaleValue == null) {
                    invalidEntries += match.value.trim()
                    return@forEach
                }
                scaleOverrides[descriptor.key] = scaleValue
            }
        }
    }

    return ThemeTokenParseResult(
        overrides = overrides,
        shapeOverrides = shapeOverrides,
        scaleOverrides = scaleOverrides,
        unsupportedKeys = unsupportedKeys,
        invalidEntries = invalidEntries,
    )
}

fun parseThemeColorString(value: String): Color? {
    return parseThemeColor(value)
}

fun upsertThemeTokenSource(
    source: String,
    key: String,
    color: Color?,
): String {
    return upsertThemeTokenSource(
        source = source,
        key = key,
        value = color?.toCssHex(),
    )
}

fun upsertThemeTokenSource(
    source: String,
    key: String,
    value: String?,
): String {
    val canonicalKey = normalizeThemeTokenKey(key)?.key ?: key.trim().removePrefix("--")
    return upsertThemeTokenValueSource(
        source = source,
        key = canonicalKey,
        value = value?.takeIf(String::isNotBlank),
    )
}

fun removeThemeTokenSource(source: String, key: String): String {
    return upsertThemeTokenSource(source = source, key = key, value = null)
}

fun formatThemeDimensionTokenValue(value: Dp): String {
    val rawValue = value.value
    val formatted = if (rawValue % 1f == 0f) {
        rawValue.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rawValue).trimEnd('0').trimEnd('.')
    }
    return "${formatted}dp"
}

fun formatThemeScaleTokenValue(value: Float): String {
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}

fun ColorScheme.applyThemeTokenOverrides(source: String): ColorScheme {
    return applyThemeTokenOverrides(parseThemeTokenSource(source))
}

fun ColorScheme.applyThemeTokenOverrides(parseResult: ThemeTokenParseResult): ColorScheme {
    val overrides = parseResult.overrides
    if (overrides.isEmpty()) {
        return this
    }

    fun resolvedColor(key: String, fallback: Color): Color = overrides[key] ?: fallback

    fun resolvedOnColor(
        onKey: String,
        fallback: Color,
        autoColor: Color,
        shouldAutoResolve: Boolean,
    ): Color {
        return overrides[onKey] ?: if (shouldAutoResolve) autoColor.preferredContentColor() else fallback
    }

    val resolvedPrimary = resolvedColor("primary", primary)
    val resolvedPrimaryContainer = resolvedColor("primaryContainer", primaryContainer)
    val resolvedSecondary = resolvedColor("secondary", secondary)
    val resolvedSecondaryContainer = resolvedColor("secondaryContainer", secondaryContainer)
    val resolvedTertiary = resolvedColor("tertiary", tertiary)
    val resolvedTertiaryContainer = resolvedColor("tertiaryContainer", tertiaryContainer)
    val resolvedBackground = resolvedColor("background", background)
    val resolvedSurface = resolvedColor("surface", surface)
    val resolvedSurfaceVariant = resolvedColor("surfaceVariant", surfaceVariant)
    val resolvedInverseSurface = resolvedColor("inverseSurface", inverseSurface)
    val resolvedError = resolvedColor("error", error)
    val resolvedErrorContainer = resolvedColor("errorContainer", errorContainer)
    val resolvedSurfaceContentBase = SURFACE_FAMILY_TOKEN_KEYS.firstNotNullOfOrNull(overrides::get) ?: resolvedSurface
    val hasSurfaceFamilyOverride = SURFACE_FAMILY_TOKEN_KEYS.any(overrides::containsKey)

    return copy(
        primary = resolvedPrimary,
        onPrimary = resolvedOnColor(
            onKey = "onPrimary",
            fallback = onPrimary,
            autoColor = resolvedPrimary,
            shouldAutoResolve = overrides.containsKey("primary"),
        ),
        primaryContainer = resolvedPrimaryContainer,
        onPrimaryContainer = resolvedOnColor(
            onKey = "onPrimaryContainer",
            fallback = onPrimaryContainer,
            autoColor = resolvedPrimaryContainer,
            shouldAutoResolve = overrides.containsKey("primaryContainer"),
        ),
        inversePrimary = resolvedColor("inversePrimary", inversePrimary),
        secondary = resolvedSecondary,
        onSecondary = resolvedOnColor(
            onKey = "onSecondary",
            fallback = onSecondary,
            autoColor = resolvedSecondary,
            shouldAutoResolve = overrides.containsKey("secondary"),
        ),
        secondaryContainer = resolvedSecondaryContainer,
        onSecondaryContainer = resolvedOnColor(
            onKey = "onSecondaryContainer",
            fallback = onSecondaryContainer,
            autoColor = resolvedSecondaryContainer,
            shouldAutoResolve = overrides.containsKey("secondaryContainer"),
        ),
        tertiary = resolvedTertiary,
        onTertiary = resolvedOnColor(
            onKey = "onTertiary",
            fallback = onTertiary,
            autoColor = resolvedTertiary,
            shouldAutoResolve = overrides.containsKey("tertiary"),
        ),
        tertiaryContainer = resolvedTertiaryContainer,
        onTertiaryContainer = resolvedOnColor(
            onKey = "onTertiaryContainer",
            fallback = onTertiaryContainer,
            autoColor = resolvedTertiaryContainer,
            shouldAutoResolve = overrides.containsKey("tertiaryContainer"),
        ),
        background = resolvedBackground,
        onBackground = resolvedOnColor(
            onKey = "onBackground",
            fallback = onBackground,
            autoColor = resolvedBackground,
            shouldAutoResolve = overrides.containsKey("background"),
        ),
        surface = resolvedSurface,
        onSurface = resolvedOnColor(
            onKey = "onSurface",
            fallback = onSurface,
            autoColor = resolvedSurfaceContentBase,
            shouldAutoResolve = hasSurfaceFamilyOverride,
        ),
        surfaceVariant = resolvedSurfaceVariant,
        onSurfaceVariant = resolvedOnColor(
            onKey = "onSurfaceVariant",
            fallback = onSurfaceVariant,
            autoColor = resolvedSurfaceVariant,
            shouldAutoResolve = overrides.containsKey("surfaceVariant"),
        ),
        inverseSurface = resolvedInverseSurface,
        inverseOnSurface = resolvedOnColor(
            onKey = "inverseOnSurface",
            fallback = inverseOnSurface,
            autoColor = resolvedInverseSurface,
            shouldAutoResolve = overrides.containsKey("inverseSurface"),
        ),
        error = resolvedError,
        onError = resolvedOnColor(
            onKey = "onError",
            fallback = onError,
            autoColor = resolvedError,
            shouldAutoResolve = overrides.containsKey("error"),
        ),
        errorContainer = resolvedErrorContainer,
        onErrorContainer = resolvedOnColor(
            onKey = "onErrorContainer",
            fallback = onErrorContainer,
            autoColor = resolvedErrorContainer,
            shouldAutoResolve = overrides.containsKey("errorContainer"),
        ),
        outline = resolvedColor("outline", outline),
        outlineVariant = resolvedColor("outlineVariant", outlineVariant),
        scrim = resolvedColor("scrim", scrim),
        surfaceBright = resolvedColor("surfaceBright", surfaceBright),
        surfaceDim = resolvedColor("surfaceDim", surfaceDim),
        surfaceContainer = resolvedColor("surfaceContainer", surfaceContainer),
        surfaceContainerHigh = resolvedColor("surfaceContainerHigh", surfaceContainerHigh),
        surfaceContainerHighest = resolvedColor("surfaceContainerHighest", surfaceContainerHighest),
        surfaceContainerLow = resolvedColor("surfaceContainerLow", surfaceContainerLow),
        surfaceContainerLowest = resolvedColor("surfaceContainerLowest", surfaceContainerLowest),
    )
}

fun MaterialShapes.applyThemeTokenOverrides(source: String): MaterialShapes {
    return applyThemeTokenOverrides(parseThemeTokenSource(source))
}

fun MaterialShapes.applyThemeTokenOverrides(parseResult: ThemeTokenParseResult): MaterialShapes {
    val overrides = parseResult.shapeOverrides
    if (overrides.isEmpty()) {
        return this
    }

    return copy(
        extraSmall = overrides["shapeExtraSmall"]?.let(::themeRoundedShape) ?: extraSmall,
        small = overrides["shapeSmall"]?.let(::themeRoundedShape) ?: small,
        medium = overrides["shapeMedium"]?.let(::themeRoundedShape) ?: medium,
        large = overrides["shapeLarge"]?.let(::themeRoundedShape) ?: large,
        extraLarge = overrides["shapeExtraLarge"]?.let(::themeRoundedShape) ?: extraLarge,
        largeIncreased = overrides["shapeLargeIncreased"]?.let(::themeRoundedShape) ?: largeIncreased,
        extraLargeIncreased = overrides["shapeExtraLargeIncreased"]?.let(::themeRoundedShape) ?: extraLargeIncreased,
        extraExtraLarge = overrides["shapeExtraExtraLarge"]?.let(::themeRoundedShape) ?: extraExtraLarge,
    )
}

fun MaterialTypography.applyThemeTokenOverrides(source: String): MaterialTypography {
    return applyThemeTokenOverrides(parseThemeTokenSource(source))
}

fun MaterialTypography.applyThemeTokenOverrides(parseResult: ThemeTokenParseResult): MaterialTypography {
    val overrides = parseResult.scaleOverrides
    if (overrides.isEmpty()) {
        return this
    }

    val displayScale = ThemeTokenTextScaleGroup.DISPLAY.resolveScale(overrides)
    val headlineScale = ThemeTokenTextScaleGroup.HEADLINE.resolveScale(overrides)
    val titleScale = ThemeTokenTextScaleGroup.TITLE.resolveScale(overrides)
    val bodyScale = ThemeTokenTextScaleGroup.BODY.resolveScale(overrides)
    val labelScale = ThemeTokenTextScaleGroup.LABEL.resolveScale(overrides)

    return copy(
        displayLarge = displayLarge.scaled(displayScale),
        displayMedium = displayMedium.scaled(displayScale),
        displaySmall = displaySmall.scaled(displayScale),
        headlineLarge = headlineLarge.scaled(headlineScale),
        headlineMedium = headlineMedium.scaled(headlineScale),
        headlineSmall = headlineSmall.scaled(headlineScale),
        titleLarge = titleLarge.scaled(titleScale),
        titleMedium = titleMedium.scaled(titleScale),
        titleSmall = titleSmall.scaled(titleScale),
        bodyLarge = bodyLarge.scaled(bodyScale),
        bodyMedium = bodyMedium.scaled(bodyScale),
        bodySmall = bodySmall.scaled(bodyScale),
        labelLarge = labelLarge.scaled(labelScale),
        labelMedium = labelMedium.scaled(labelScale),
        labelSmall = labelSmall.scaled(labelScale),
        displayLargeEmphasized = displayLargeEmphasized.scaled(displayScale),
        displayMediumEmphasized = displayMediumEmphasized.scaled(displayScale),
        displaySmallEmphasized = displaySmallEmphasized.scaled(displayScale),
        headlineLargeEmphasized = headlineLargeEmphasized.scaled(headlineScale),
        headlineMediumEmphasized = headlineMediumEmphasized.scaled(headlineScale),
        headlineSmallEmphasized = headlineSmallEmphasized.scaled(headlineScale),
        titleLargeEmphasized = titleLargeEmphasized.scaled(titleScale),
        titleMediumEmphasized = titleMediumEmphasized.scaled(titleScale),
        titleSmallEmphasized = titleSmallEmphasized.scaled(titleScale),
        bodyLargeEmphasized = bodyLargeEmphasized.scaled(bodyScale),
        bodyMediumEmphasized = bodyMediumEmphasized.scaled(bodyScale),
        bodySmallEmphasized = bodySmallEmphasized.scaled(bodyScale),
        labelLargeEmphasized = labelLargeEmphasized.scaled(labelScale),
        labelMediumEmphasized = labelMediumEmphasized.scaled(labelScale),
        labelSmallEmphasized = labelSmallEmphasized.scaled(labelScale),
    )
}

fun ThemeTokenParseResult.themedRoundedShape(
    tokenKey: String,
    fallback: Dp,
): RoundedCornerShape {
    return themeRoundedShape(shapeOverrides[tokenKey] ?: fallback)
}

fun ThemeTokenParseResult.applyThemeTokenTextScale(
    style: TextStyle,
    group: ThemeTokenTextScaleGroup,
): TextStyle {
    if (scaleOverrides.isEmpty()) {
        return style
    }
    return style.scaled(group.resolveScale(scaleOverrides))
}

private fun upsertThemeTokenValueSource(
    source: String,
    key: String,
    value: String?,
): String {
    var found = false

    val updatedLines = source.lines().mapNotNull { rawLine ->
        val match = THEME_TOKEN_LINE_REGEX.matchEntire(rawLine)
        val matchedKey = match?.groupValues?.getOrNull(1)?.let(::normalizeThemeTokenKey)?.key
        if (matchedKey == key) {
            if (found || value == null) {
                null
            } else {
                found = true
                serializeThemeTokenLine(key, value)
            }
        } else {
            rawLine
        }
    }.toMutableList()

    if (!found && value != null) {
        if (updatedLines.isNotEmpty() && updatedLines.last().isNotBlank()) {
            updatedLines += ""
        }
        updatedLines += serializeThemeTokenLine(key, value)
    }

    return updatedLines.joinToString(separator = "\n").trim()
}

private fun normalizeThemeTokenKey(rawKey: String): ThemeTokenDescriptor? {
    val normalized = rawKey
        .trim()
        .removePrefix("--")
        .replace(THEME_TOKEN_NORMALIZE_REGEX, "")
        .lowercase()

    SUPPORTED_THEME_COLOR_TOKEN_KEYS[normalized]?.let { key ->
        return ThemeTokenDescriptor(key = key, type = ThemeTokenType.COLOR)
    }
    SUPPORTED_THEME_SHAPE_TOKEN_KEYS[normalized]?.let { key ->
        return ThemeTokenDescriptor(key = key, type = ThemeTokenType.SHAPE)
    }
    SUPPORTED_THEME_SCALE_TOKEN_KEYS[normalized]?.let { key ->
        return ThemeTokenDescriptor(key = key, type = ThemeTokenType.SCALE)
    }
    return null
}

private fun parseThemeColor(value: String): Color? {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("#") -> parseCssHexColor(trimmed.removePrefix("#"))
        trimmed.startsWith("0x", ignoreCase = true) -> parseAndroidHexColor(trimmed.drop(2))
        else -> null
    }
}

private fun parseThemeDimension(value: String): Dp? {
    val match = THEME_DP_VALUE_REGEX.matchEntire(value.trim()) ?: return null
    val rawNumber = match.groupValues[1].toFloatOrNull() ?: return null
    return rawNumber.dp
}

private fun parseThemeScale(value: String): Float? {
    val match = THEME_SCALE_VALUE_REGEX.matchEntire(value.trim()) ?: return null
    val rawNumber = match.groupValues[1].toFloatOrNull() ?: return null
    return if (match.groupValues[2] == "%") rawNumber / 100f else rawNumber
}

private fun parseCssHexColor(hex: String): Color? {
    val expanded = when (hex.length) {
        3 -> "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}FF"
        4 -> "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}"
        6 -> "${hex}FF"
        8 -> hex
        else -> return null
    }

    val rgba = expanded.toLongOrNull(16) ?: return null
    val red = ((rgba shr 24) and 0xFF).toInt()
    val green = ((rgba shr 16) and 0xFF).toInt()
    val blue = ((rgba shr 8) and 0xFF).toInt()
    val alpha = (rgba and 0xFF).toInt()
    return Color(red, green, blue, alpha)
}

private fun parseAndroidHexColor(hex: String): Color? {
    val expanded = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }

    val argb = expanded.toLongOrNull(16) ?: return null
    val alpha = ((argb shr 24) and 0xFF).toInt()
    val red = ((argb shr 16) and 0xFF).toInt()
    val green = ((argb shr 8) and 0xFF).toInt()
    val blue = (argb and 0xFF).toInt()
    return Color(red, green, blue, alpha)
}

private fun combinedScale(globalScale: Float, sectionScale: Float?): Float {
    return (globalScale * (sectionScale ?: 1f)).coerceIn(MIN_THEME_TEXT_SCALE, MAX_THEME_TEXT_SCALE)
}

private fun ThemeTokenTextScaleGroup.resolveScale(overrides: Map<String, Float>): Float {
    val globalScale = overrides["fontScale"] ?: 1f
    val sectionScale = when (this) {
        ThemeTokenTextScaleGroup.DISPLAY -> overrides["displayScale"]
        ThemeTokenTextScaleGroup.HEADLINE -> overrides["headlineScale"]
        ThemeTokenTextScaleGroup.TITLE -> overrides["titleScale"]
        ThemeTokenTextScaleGroup.BODY -> overrides["bodyScale"]
        ThemeTokenTextScaleGroup.LABEL -> overrides["labelScale"]
    }
    return combinedScale(globalScale, sectionScale)
}

private fun TextStyle.scaled(scale: Float): TextStyle {
    val safeScale = scale.coerceIn(MIN_THEME_TEXT_SCALE, MAX_THEME_TEXT_SCALE)
    return copy(
        fontSize = fontSize.scaleBy(safeScale),
        lineHeight = lineHeight.scaleBy(safeScale),
        letterSpacing = letterSpacing.scaleBy(safeScale),
    )
}

private fun TextUnit.scaleBy(scale: Float): TextUnit {
    return if (type == TextUnitType.Unspecified) this else this * scale
}

private fun themeRoundedShape(radius: Dp): RoundedCornerShape {
    return RoundedCornerShape(radius.coerceIn(0.dp, MAX_THEME_RADIUS))
}

private fun serializeThemeTokenLine(key: String, color: Color): String {
    return serializeThemeTokenLine(key, color.toCssHex())
}

private fun serializeThemeTokenLine(key: String, value: String): String {
    return "$key: $value;"
}

fun ColorScheme.themeTokenColor(key: String): Color {
    return when (key) {
        "primary" -> primary
        "onPrimary" -> onPrimary
        "primaryContainer" -> primaryContainer
        "onPrimaryContainer" -> onPrimaryContainer
        "inversePrimary" -> inversePrimary
        "secondary" -> secondary
        "onSecondary" -> onSecondary
        "secondaryContainer" -> secondaryContainer
        "onSecondaryContainer" -> onSecondaryContainer
        "tertiary" -> tertiary
        "onTertiary" -> onTertiary
        "tertiaryContainer" -> tertiaryContainer
        "onTertiaryContainer" -> onTertiaryContainer
        "background" -> background
        "onBackground" -> onBackground
        "surface" -> surface
        "onSurface" -> onSurface
        "surfaceContainer" -> surfaceContainer
        "surfaceContainerLowest" -> surfaceContainerLowest
        "surfaceContainerLow" -> surfaceContainerLow
        "surfaceContainerHigh" -> surfaceContainerHigh
        "surfaceContainerHighest" -> surfaceContainerHighest
        "surfaceBright" -> surfaceBright
        "surfaceDim" -> surfaceDim
        "surfaceVariant" -> surfaceVariant
        "onSurfaceVariant" -> onSurfaceVariant
        "inverseSurface" -> inverseSurface
        "inverseOnSurface" -> inverseOnSurface
        "error" -> error
        "onError" -> onError
        "errorContainer" -> errorContainer
        "onErrorContainer" -> onErrorContainer
        "outline" -> outline
        "outlineVariant" -> outlineVariant
        "scrim" -> scrim
        else -> error("Unsupported common theme token key: $key")
    }
}

private val SUPPORTED_THEME_COLOR_TOKEN_KEYS = mapOf(
    "primary" to "primary",
    "onprimary" to "onPrimary",
    "primarycontainer" to "primaryContainer",
    "onprimarycontainer" to "onPrimaryContainer",
    "inverseprimary" to "inversePrimary",
    "secondary" to "secondary",
    "onsecondary" to "onSecondary",
    "secondarycontainer" to "secondaryContainer",
    "onsecondarycontainer" to "onSecondaryContainer",
    "tertiary" to "tertiary",
    "ontertiary" to "onTertiary",
    "tertiarycontainer" to "tertiaryContainer",
    "ontertiarycontainer" to "onTertiaryContainer",
    "background" to "background",
    "onbackground" to "onBackground",
    "surface" to "surface",
    "onsurface" to "onSurface",
    "surfacevariant" to "surfaceVariant",
    "onsurfacevariant" to "onSurfaceVariant",
    "inversesurface" to "inverseSurface",
    "inverseonsurface" to "inverseOnSurface",
    "error" to "error",
    "onerror" to "onError",
    "errorcontainer" to "errorContainer",
    "onerrorcontainer" to "onErrorContainer",
    "outline" to "outline",
    "outlinevariant" to "outlineVariant",
    "scrim" to "scrim",
    "surfacebright" to "surfaceBright",
    "surfacedim" to "surfaceDim",
    "surfacecontainer" to "surfaceContainer",
    "surfacecontainerhigh" to "surfaceContainerHigh",
    "surfacecontainerhighest" to "surfaceContainerHighest",
    "surfacecontainerlow" to "surfaceContainerLow",
    "surfacecontainerlowest" to "surfaceContainerLowest",
)

private val SUPPORTED_THEME_SHAPE_TOKEN_KEYS = mapOf(
    "shapeextrasmall" to "shapeExtraSmall",
    "radiusextrasmall" to "shapeExtraSmall",
    "cornerextrasmall" to "shapeExtraSmall",
    "shapesmall" to "shapeSmall",
    "radiussmall" to "shapeSmall",
    "cornersmall" to "shapeSmall",
    "shapemedium" to "shapeMedium",
    "radiusmedium" to "shapeMedium",
    "cornermedium" to "shapeMedium",
    "shapelarge" to "shapeLarge",
    "radiuslarge" to "shapeLarge",
    "cornerlarge" to "shapeLarge",
    "shapeextralarge" to "shapeExtraLarge",
    "radiusextralarge" to "shapeExtraLarge",
    "cornerextralarge" to "shapeExtraLarge",
    "shapelargeincreased" to "shapeLargeIncreased",
    "shapeextralargeincreased" to "shapeExtraLargeIncreased",
    "shapeextraextralarge" to "shapeExtraExtraLarge",
)

private val SUPPORTED_THEME_SCALE_TOKEN_KEYS = mapOf(
    "fontscale" to "fontScale",
    "textscale" to "fontScale",
    "displayscale" to "displayScale",
    "headlinescale" to "headlineScale",
    "titlescale" to "titleScale",
    "bodyscale" to "bodyScale",
    "labelscale" to "labelScale",
)
