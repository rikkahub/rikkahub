package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarEngineTest {
    private val demoLanguage = LanguageDefinition(
        name = "Demo",
        aliases = setOf("demo", "d"),
        rules = listOf(
            RegexRule(
                pattern = Regex("""\s+"""),
                scope = null,
            ),
            DelimitedRule(
                startDelimiter = "\"",
                endDelimiter = "\"",
                scope = TokenScope.STRING,
                nextKind = LexemeKind.Value,
                escapeCharacter = '\\',
            ),
            RegexRule(
                pattern = Regex("""\d+"""),
                scope = TokenScope.NUMBER,
                nextKind = LexemeKind.Value,
            ),
            RegexRule(
                pattern = Regex("""[A-Za-z_]+"""),
                scope = { _, match ->
                    if (match.value == "let") TokenScope.KEYWORD else null
                },
                nextKind = { _, _ -> LexemeKind.Value },
            ),
        ),
    )
    private val engine = GrammarEngine(listOf(demoLanguage))

    @Test
    fun `executes a declarative grammar and preserves unmatched text`() {
        val code = """let answer = "forty-two" + 42"""

        val tokens = requireNotNull(engine.highlight(code, "demo"))

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertTrue(tokens.any { it.text == "let" && it.tokenType == TokenScope.KEYWORD })
        assertTrue(tokens.any { it.text == "\"forty-two\"" && it.tokenType == TokenScope.STRING })
        assertTrue(tokens.any { it.text == "42" && it.tokenType == TokenScope.NUMBER })
        assertTrue(tokens.any { it.text.contains("=") && it.tokenType == null })
    }

    @Test
    fun `resolves aliases and rejects unknown languages`() {
        assertTrue(engine.supports("D"))
        assertFalse(engine.supports("unknown"))
        assertEquals(null, engine.highlight("value", "unknown"))
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
            is HighlightToken.Token.StringContent -> this.type
            is HighlightToken.Token.StringListContent -> this.type
            is HighlightToken.Token.Nested -> this.type
        }
}
