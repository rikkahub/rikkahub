package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import org.jsoup.nodes.Element

internal fun SpanStyle.asTextStyle(): TextStyle {
    return TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        background = background,
        textDecoration = textDecoration,
    )
}

internal fun buildFontTagStyle(
    element: Element,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val color = element.attr("color").takeIf { it.isNotBlank() }?.let(MarkdownCss::parseColor)
    val styleAttr = element.attr("style").takeIf { it.isNotBlank() }?.let {
        MarkdownCss.parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }
    val sizeAttr = element.attr("size").takeIf { it.isNotBlank() }?.let {
        MarkdownCss.parseLegacyFontSize(
            fontSize = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    var resolvedStyle = styleAttr ?: SpanStyle()
    color?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(color = it)) }
    sizeAttr?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(fontSize = it)) }

    return resolvedStyle.takeIf {
        color != null || styleAttr != null || sizeAttr != null
    }
}
