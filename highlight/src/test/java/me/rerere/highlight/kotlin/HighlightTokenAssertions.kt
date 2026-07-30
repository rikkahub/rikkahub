package me.rerere.highlight.kotlin

import me.rerere.highlight.HighlightToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun assertPreservesSource(code: String, tokens: List<HighlightToken>) {
    assertEquals(code, tokens.joinToString(separator = "") { it.text })
}

internal fun assertToken(
    tokens: List<HighlightToken>,
    content: String,
    type: String,
) {
    assertTrue(
        "Expected token '$content' of type '$type', got ${tokens.describe()}",
        tokens.any { it.text == content && it.tokenType == type },
    )
}

internal fun assertTokenContaining(
    tokens: List<HighlightToken>,
    content: String,
    type: String,
) {
    assertTrue(
        "Expected a '$type' token containing '$content', got ${tokens.describe()}",
        tokens.any { content in it.text && it.tokenType == type },
    )
}

private val HighlightToken.text: String
    get() = when (this) {
        is HighlightToken.Plain -> content
        is HighlightToken.Token.StringContent -> content
        is HighlightToken.Token.StringListContent -> content.joinToString(separator = "")
        is HighlightToken.Token.Nested -> content.joinToString(separator = "") { it.text }
    }

private val HighlightToken.tokenType: String?
    get() = when (this) {
        is HighlightToken.Plain -> null
        is HighlightToken.Token.StringContent -> type
        is HighlightToken.Token.StringListContent -> type
        is HighlightToken.Token.Nested -> type
    }

private fun List<HighlightToken>.describe(): String {
    return joinToString(prefix = "[", postfix = "]") { "${it.tokenType}:${it.text}" }
}
