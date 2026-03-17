package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.utils.toCssHex

private val THEME_TOKEN_LINE_REGEX = Regex(
    pattern = """^\s*([A-Za-z-][A-Za-z0-9_-]*)\s*:\s*([^;]+?)\s*;?\s*$""",
    options = setOf(RegexOption.MULTILINE),
)
private val THEME_TOKEN_NORMALIZE_REGEX = Regex("""[\s_-]""")

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

fun buildThemeTokenTemplate(colorScheme: ColorScheme): String {
    return COMMON_THEME_TOKEN_KEYS.joinToString(separator = "\n") { key ->
        "$key: ${colorScheme.themeTokenColor(key).toCssHex()};"
    }
}

data class ThemeTokenParseResult(
    val overrides: Map<String, Color>,
    val unsupportedKeys: Set<String> = emptySet(),
    val invalidEntries: List<String> = emptyList(),
) {
    val validCount: Int
        get() = overrides.size
}

fun parseThemeTokenSource(source: String): ThemeTokenParseResult {
    if (source.isBlank()) {
        return ThemeTokenParseResult(overrides = emptyMap())
    }

    val overrides = linkedMapOf<String, Color>()
    val unsupportedKeys = linkedSetOf<String>()
    val invalidEntries = mutableListOf<String>()

    THEME_TOKEN_LINE_REGEX.findAll(source).forEach { match ->
        val rawKey = match.groupValues[1]
        val rawValue = match.groupValues[2].trim()
        val canonicalKey = normalizeThemeTokenKey(rawKey)

        if (canonicalKey == null) {
            unsupportedKeys += rawKey
            return@forEach
        }

        val color = parseThemeColor(rawValue)
        if (color == null) {
            invalidEntries += match.value.trim()
            return@forEach
        }

        overrides[canonicalKey] = color
    }

    return ThemeTokenParseResult(
        overrides = overrides,
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
    val canonicalKey = normalizeThemeTokenKey(key) ?: key
    var found = false

    val updatedLines = source.lines().mapNotNull { rawLine ->
        val match = THEME_TOKEN_LINE_REGEX.matchEntire(rawLine)
        val matchedKey = match?.groupValues?.getOrNull(1)?.let(::normalizeThemeTokenKey)
        if (matchedKey == canonicalKey) {
            if (found || color == null) {
                null
            } else {
                found = true
                serializeThemeTokenLine(canonicalKey, color)
            }
        } else {
            rawLine
        }
    }.toMutableList()

    if (!found && color != null) {
        if (updatedLines.isNotEmpty() && updatedLines.last().isNotBlank()) {
            updatedLines += ""
        }
        updatedLines += serializeThemeTokenLine(canonicalKey, color)
    }

    return updatedLines.joinToString(separator = "\n").trim()
}

fun removeThemeTokenSource(source: String, key: String): String {
    return upsertThemeTokenSource(source = source, key = key, color = null)
}

fun ColorScheme.applyThemeTokenOverrides(source: String): ColorScheme {
    val overrides = parseThemeTokenSource(source).overrides
    if (overrides.isEmpty()) {
        return this
    }

    return copy(
        primary = overrides["primary"] ?: primary,
        onPrimary = overrides["onPrimary"] ?: onPrimary,
        primaryContainer = overrides["primaryContainer"] ?: primaryContainer,
        onPrimaryContainer = overrides["onPrimaryContainer"] ?: onPrimaryContainer,
        inversePrimary = overrides["inversePrimary"] ?: inversePrimary,
        secondary = overrides["secondary"] ?: secondary,
        onSecondary = overrides["onSecondary"] ?: onSecondary,
        secondaryContainer = overrides["secondaryContainer"] ?: secondaryContainer,
        onSecondaryContainer = overrides["onSecondaryContainer"] ?: onSecondaryContainer,
        tertiary = overrides["tertiary"] ?: tertiary,
        onTertiary = overrides["onTertiary"] ?: onTertiary,
        tertiaryContainer = overrides["tertiaryContainer"] ?: tertiaryContainer,
        onTertiaryContainer = overrides["onTertiaryContainer"] ?: onTertiaryContainer,
        background = overrides["background"] ?: background,
        onBackground = overrides["onBackground"] ?: onBackground,
        surface = overrides["surface"] ?: surface,
        onSurface = overrides["onSurface"] ?: onSurface,
        surfaceVariant = overrides["surfaceVariant"] ?: surfaceVariant,
        onSurfaceVariant = overrides["onSurfaceVariant"] ?: onSurfaceVariant,
        inverseSurface = overrides["inverseSurface"] ?: inverseSurface,
        inverseOnSurface = overrides["inverseOnSurface"] ?: inverseOnSurface,
        error = overrides["error"] ?: error,
        onError = overrides["onError"] ?: onError,
        errorContainer = overrides["errorContainer"] ?: errorContainer,
        onErrorContainer = overrides["onErrorContainer"] ?: onErrorContainer,
        outline = overrides["outline"] ?: outline,
        outlineVariant = overrides["outlineVariant"] ?: outlineVariant,
        scrim = overrides["scrim"] ?: scrim,
        surfaceBright = overrides["surfaceBright"] ?: surfaceBright,
        surfaceDim = overrides["surfaceDim"] ?: surfaceDim,
        surfaceContainer = overrides["surfaceContainer"] ?: surfaceContainer,
        surfaceContainerHigh = overrides["surfaceContainerHigh"] ?: surfaceContainerHigh,
        surfaceContainerHighest = overrides["surfaceContainerHighest"] ?: surfaceContainerHighest,
        surfaceContainerLow = overrides["surfaceContainerLow"] ?: surfaceContainerLow,
        surfaceContainerLowest = overrides["surfaceContainerLowest"] ?: surfaceContainerLowest,
    )
}

private fun normalizeThemeTokenKey(rawKey: String): String? {
    val normalized = rawKey
        .trim()
        .removePrefix("--")
        .replace(THEME_TOKEN_NORMALIZE_REGEX, "")
        .lowercase()

    return SUPPORTED_THEME_TOKEN_KEYS[normalized]
}

private fun parseThemeColor(value: String): Color? {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("#") -> parseCssHexColor(trimmed.removePrefix("#"))
        trimmed.startsWith("0x", ignoreCase = true) -> parseAndroidHexColor(trimmed.drop(2))
        else -> null
    }
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

private fun serializeThemeTokenLine(key: String, color: Color): String {
    return "$key: ${color.toCssHex()};"
}

fun ColorScheme.themeTokenColor(key: String): Color {
    return when (key) {
        "primary" -> primary
        "primaryContainer" -> primaryContainer
        "background" -> background
        "surface" -> surface
        "surfaceContainer" -> surfaceContainer
        "surfaceContainerHigh" -> surfaceContainerHigh
        "surfaceVariant" -> surfaceVariant
        "outline" -> outline
        else -> error("Unsupported common theme token key: $key")
    }
}

private val SUPPORTED_THEME_TOKEN_KEYS = mapOf(
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
