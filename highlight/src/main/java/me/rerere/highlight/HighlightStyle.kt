package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

fun AnnotatedString.Builder.buildHighlightText(
    token: HighlightToken,
    colors: HighlightTextColorPalette,
) {
    when (token) {
        is HighlightToken.Plain -> append(token.content)
        is HighlightToken.Styled -> {
            withStyle(getStyleForTokenType(token.type, colors)) {
                append(token.content)
            }
        }
    }
}

data class HighlightTextColorPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val className: Color,
    val property: Color,
    val boolean: Color,
    val variable: Color,
    val tag: Color,
    val attrName: Color,
    val attrValue: Color,
    val fallback: Color,
) {
    companion object {
        val Default = HighlightTextColorPalette(
            keyword = Color(0xFFC678DD),
            string = Color(0xFF98C379),
            number = Color(0xFFD19A66),
            comment = Color(0xFF5C6370),
            function = Color(0xFF61AFEF),
            operator = Color(0xFF56B6C2),
            punctuation = Color(0xFFABB2BF),
            className = Color(0xFFE5C07B),
            property = Color(0xFFE06C75),
            boolean = Color(0xFFD19A66),
            variable = Color(0xFFE06C75),
            tag = Color(0xFFE06C75),
            attrName = Color(0xFFD19A66),
            attrValue = Color(0xFF98C379),
            fallback = Color(0xFFABB2BF),
        )
    }
}

private fun getStyleForTokenType(
    type: String,
    colors: HighlightTextColorPalette,
): SpanStyle {
    return when (type) {
        "keyword" -> SpanStyle(color = colors.keyword)
        "string" -> SpanStyle(color = colors.string)
        "number" -> SpanStyle(color = colors.number)
        "comment" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        "function", "method" -> SpanStyle(color = colors.function)
        "operator" -> SpanStyle(color = colors.operator)
        "punctuation" -> SpanStyle(color = colors.punctuation)
        "class-name" -> SpanStyle(color = colors.className)
        "property" -> SpanStyle(color = colors.property)
        "boolean", "constant" -> SpanStyle(color = colors.boolean)
        "regex", "important", "variable" -> SpanStyle(color = colors.variable)
        "tag" -> SpanStyle(color = colors.tag)
        "attr-name" -> SpanStyle(color = colors.attrName)
        "attr-value" -> SpanStyle(color = colors.attrValue)
        else -> SpanStyle(color = colors.fallback)
    }
}
