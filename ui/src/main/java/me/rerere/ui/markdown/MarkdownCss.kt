package me.rerere.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 这里只解析 HTML 行内样式里对文本渲染有意义的那一小部分 CSS。
// 相对单位统一换算成 em，绝对单位按 1px = 1sp 处理，所以解析阶段不需要 Density 和基准字号。

private val EMPTY_SPAN_STYLE = SpanStyle()

internal fun parseCssDeclarations(css: String): Map<String, String> {
    if (css.isBlank()) return emptyMap()
    return css.split(';').mapNotNull { declaration ->
        val name = declaration.substringBefore(':', "").trim().lowercase()
        val value = declaration.substringAfter(':', "").trim()
        if (name.isEmpty() || value.isEmpty()) null else name to value
    }.toMap()
}

internal fun parseSpanStyle(css: String): SpanStyle? = parseSpanStyle(parseCssDeclarations(css))

private fun parseSpanStyle(properties: Map<String, String>): SpanStyle? {
    if (properties.isEmpty()) return null
    val style = SpanStyle(
        color = properties["color"]?.let(::parseCssColor) ?: Color.Unspecified,
        background = (properties["background-color"] ?: properties["background"])?.let(::parseCssColor)
            ?: Color.Unspecified,
        fontSize = properties["font-size"]?.let(::parseFontSize) ?: TextUnit.Unspecified,
        fontWeight = properties["font-weight"]?.let(::parseFontWeight),
        fontStyle = properties["font-style"]?.let(::parseFontStyle),
        fontFamily = properties["font-family"]?.let(::parseFontFamily),
        letterSpacing = properties["letter-spacing"]?.let(::parseCssSize) ?: TextUnit.Unspecified,
        textDecoration = (properties["text-decoration"] ?: properties["text-decoration-line"])
            ?.let(::parseTextDecoration),
    )
    return style.takeIf { it != EMPTY_SPAN_STYLE }
}

/** [align] 是 HTML 的 `align` 属性，优先级低于 CSS 的 `text-align` */
internal fun parseBlockStyle(css: String, align: String? = null): TextStyle? {
    val properties = parseCssDeclarations(css)
    val spanStyle = parseSpanStyle(properties)
    val textAlign = (properties["text-align"] ?: align)?.let(::parseTextAlign)
    val lineHeight = properties["line-height"]?.let(::parseLineHeight)
    if (spanStyle == null && textAlign == null && lineHeight == null) return null
    return TextStyle(
        textAlign = textAlign ?: TextAlign.Unspecified,
        lineHeight = lineHeight ?: TextUnit.Unspecified,
    ).merge(spanStyle ?: EMPTY_SPAN_STYLE)
}

/** 解析 `<font color size face>` 这类旧式属性 */
internal fun parseFontTag(color: String, size: String, face: String): SpanStyle? {
    val style = SpanStyle(
        color = parseCssColor(color) ?: Color.Unspecified,
        fontSize = parseLegacyFontSize(size) ?: TextUnit.Unspecified,
        fontFamily = parseFontFamily(face),
    )
    return style.takeIf { it != EMPTY_SPAN_STYLE }
}

private val SIZE_REGEX = Regex("""(-?\d*\.?\d+)\s*([a-z%]*)""")

private val SIZE_KEYWORDS = mapOf(
    "xx-small" to 0.6f,
    "x-small" to 0.75f,
    "small" to 0.89f,
    "smaller" to 0.83f,
    "medium" to 1f,
    "large" to 1.2f,
    "larger" to 1.2f,
    "x-large" to 1.5f,
    "xx-large" to 2f,
)

internal fun parseCssSize(value: String): TextUnit? {
    val match = SIZE_REGEX.matchEntire(value.trim().lowercase()) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2]) {
        "", "px", "sp", "dp" -> number.sp
        "pt" -> (number * 4f / 3f).sp
        "em", "rem" -> number.em
        "%" -> (number / 100f).em
        else -> null
    }
}

// 限制上限，避免 font-size: 9999px 这种内容把布局撑爆
private fun parseFontSize(value: String): TextUnit? {
    val size = SIZE_KEYWORDS[value.trim().lowercase()]?.em ?: parseCssSize(value) ?: return null
    if (size.value <= 0f) return null
    return if (size.type == TextUnitType.Em) size.value.coerceAtMost(6f).em else size.value.coerceAtMost(96f).sp
}

private fun parseLineHeight(value: String): TextUnit? {
    val normalized = value.trim().lowercase()
    // 无单位的数字是字号的倍数
    val size = normalized.toFloatOrNull()?.em ?: parseCssSize(normalized) ?: return null
    return size.takeIf { it.value > 0f }
}

private val LEGACY_FONT_SCALES = floatArrayOf(0.63f, 0.82f, 1f, 1.13f, 1.5f, 2f, 3f)

private fun parseLegacyFontSize(value: String): TextUnit? {
    val normalized = value.trim()
    val number = normalized.toIntOrNull() ?: return null
    val level = if (normalized.startsWith('+') || normalized.startsWith('-')) 3 + number else number
    return LEGACY_FONT_SCALES[level.coerceIn(1, 7) - 1].em
}

private fun parseFontWeight(value: String): FontWeight? = when (val normalized = value.trim().lowercase()) {
    "normal" -> FontWeight.Normal
    "bold" -> FontWeight.Bold
    "bolder" -> FontWeight.ExtraBold
    "lighter" -> FontWeight.Light
    else -> normalized.toIntOrNull()?.takeIf { it in 1..1000 }?.let(::FontWeight)
}

private fun parseFontStyle(value: String): FontStyle? = when (value.trim().lowercase()) {
    "italic", "oblique" -> FontStyle.Italic
    "normal" -> FontStyle.Normal
    else -> null
}

private fun parseFontFamily(value: String): FontFamily? {
    val family = value.substringBefore(',').trim().trim('"', '\'').lowercase()
    return when {
        family.isEmpty() -> null
        "mono" in family || "courier" in family || "consolas" in family || "menlo" in family -> FontFamily.Monospace
        // sans-serif 里也带 serif，必须先判断
        "sans" in family || "arial" in family || "helvetica" in family -> FontFamily.SansSerif
        "serif" in family || "georgia" in family || "times" in family -> FontFamily.Serif
        "cursive" in family -> FontFamily.Cursive
        else -> null
    }
}

private fun parseTextDecoration(value: String): TextDecoration? {
    val parts = value.lowercase().split(' ')
    val decorations = buildList {
        if ("underline" in parts) add(TextDecoration.Underline)
        if ("line-through" in parts) add(TextDecoration.LineThrough)
    }
    return when {
        decorations.isNotEmpty() -> TextDecoration.combine(decorations)
        "none" in parts -> TextDecoration.None
        else -> null
    }
}

private fun parseTextAlign(value: String): TextAlign? = when (value.trim().lowercase()) {
    "left", "start" -> TextAlign.Start
    "right", "end" -> TextAlign.End
    "center", "middle" -> TextAlign.Center
    "justify" -> TextAlign.Justify
    else -> null
}

internal fun parseCssColor(value: String): Color? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.startsWith('#') -> parseHexColor(normalized.substring(1))
        normalized.startsWith("rgb") -> parseRgbColor(normalized)
        else -> NAMED_COLORS[normalized]
    }
}

// 支持 #rgb、#rgba、#rrggbb、#rrggbbaa，CSS 的透明度在最后
private fun parseHexColor(hex: String): Color? {
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val full = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val rgb = full.substring(0, 6).toInt(16)
    val alpha = if (full.length == 8) full.substring(6).toInt(16) else 0xFF
    return Color(red = rgb shr 16 and 0xFF, green = rgb shr 8 and 0xFF, blue = rgb and 0xFF, alpha = alpha)
}

// 支持 rgb(255, 0, 0)、rgba(255, 0, 0, 0.5) 以及空格分隔的 rgb(255 0 0 / 50%)
private fun parseRgbColor(value: String): Color? {
    val parts = value.substringAfter('(', "").substringBefore(')')
        .split(',', '/', ' ')
        .filter { it.isNotBlank() }
    if (parts.size !in 3..4) return null
    val channels = parts.take(3).map { part ->
        val number = part.removeSuffix("%").toFloatOrNull() ?: return null
        (if (part.endsWith('%')) number * 2.55f else number).roundToInt().coerceIn(0, 255)
    }
    val alpha = parts.getOrNull(3)?.let { part ->
        val number = part.removeSuffix("%").toFloatOrNull() ?: return null
        (if (part.endsWith('%')) number / 100f else number).coerceIn(0f, 1f)
    } ?: 1f
    return Color(channels[0], channels[1], channels[2], (alpha * 255).roundToInt())
}

private val NAMED_COLORS = mapOf(
    "transparent" to Color.Transparent,
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "blue" to Color(0xFF0000FF),
    "yellow" to Color(0xFFFFFF00),
    "cyan" to Color(0xFF00FFFF),
    "aqua" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF),
    "fuchsia" to Color(0xFFFF00FF),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "lightgray" to Color(0xFFD3D3D3),
    "lightgrey" to Color(0xFFD3D3D3),
    "darkgray" to Color(0xFFA9A9A9),
    "darkgrey" to Color(0xFFA9A9A9),
    "silver" to Color(0xFFC0C0C0),
    "maroon" to Color(0xFF800000),
    "olive" to Color(0xFF808000),
    "lime" to Color(0xFF00FF00),
    "teal" to Color(0xFF008080),
    "navy" to Color(0xFF000080),
    "purple" to Color(0xFF800080),
    "orange" to Color(0xFFFFA500),
    "brown" to Color(0xFFA52A2A),
    "pink" to Color(0xFFFFC0CB),
    "gold" to Color(0xFFFFD700),
    "violet" to Color(0xFFEE82EE),
    "indigo" to Color(0xFF4B0082),
    "crimson" to Color(0xFFDC143C),
    "coral" to Color(0xFFFF7F50),
    "salmon" to Color(0xFFFA8072),
    "tomato" to Color(0xFFFF6347),
    "skyblue" to Color(0xFF87CEEB),
)
