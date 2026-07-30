package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.EmbeddedLanguageRegion
import me.rerere.highlight.kotlin.engine.rules.EmbeddedLanguageRule
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

    @Test
    fun `highlights an embedded language and preserves host delimiters`() {
        val child = LanguageDefinition(
            name = "Child",
            aliases = setOf("child"),
            rules = listOf(
                RegexRule(
                    pattern = Regex("""let"""),
                    scope = TokenScope.KEYWORD,
                ),
            ),
        )
        val host = LanguageDefinition(
            name = "Host",
            aliases = setOf("host"),
            rules = listOf(delimitedEmbedding(language = "child")),
        )
        val embeddedEngine = GrammarEngine(listOf(host, child))
        val code = "before <%let value%> after"

        val tokens = requireNotNull(embeddedEngine.highlight(code, "host"))

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertTrue(tokens.any { it.text == "<%" && it.tokenType == TokenScope.PUNCTUATION })
        assertTrue(tokens.any { it.text == "let" && it.tokenType == TokenScope.KEYWORD })
        assertTrue(tokens.any { it.text == "%>" && it.tokenType == TokenScope.PUNCTUATION })
    }

    @Test
    fun `keeps an unknown embedded language as plain text`() {
        val host = LanguageDefinition(
            name = "Host",
            aliases = setOf("host"),
            rules = listOf(delimitedEmbedding(language = "missing")),
        )
        val embeddedEngine = GrammarEngine(listOf(host))
        val code = "<%unregistered content%>"

        val tokens = requireNotNull(embeddedEngine.highlight(code, "host"))

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertTrue(tokens.any { it.text == "unregistered content" && it.tokenType == null })
    }

    @Test
    fun `limits recursive language embedding`() {
        val recursiveRule = EmbeddedLanguageRule { context ->
            EmbeddedLanguageRegion(
                language = "recursive",
                contentStartIndex = context.index,
                contentEndIndex = context.index + 1,
            )
        }
        val recursiveLanguage = LanguageDefinition(
            name = "Recursive",
            aliases = setOf("recursive"),
            rules = listOf(recursiveRule),
        )
        val recursiveEngine = GrammarEngine(listOf(recursiveLanguage))

        val tokens = requireNotNull(recursiveEngine.highlight("x", "recursive"))

        assertEquals(listOf(HighlightToken.Plain("x")), tokens)
    }

    private fun delimitedEmbedding(language: String): EmbeddedLanguageRule {
        return EmbeddedLanguageRule { context ->
            if (!context.source.startsWith("<%", context.index)) return@EmbeddedLanguageRule null

            val contentStart = context.index + 2
            val closingStart = context.source.indexOf("%>", contentStart)
                .takeIf { it in contentStart until context.endIndex }
            val contentEnd = closingStart ?: context.endIndex
            val matchEnd = closingStart?.plus(2)?.coerceAtMost(context.endIndex) ?: context.endIndex
            EmbeddedLanguageRegion(
                language = language,
                contentStartIndex = contentStart,
                contentEndIndex = contentEnd,
                endIndex = matchEnd,
                leadingScope = TokenScope.PUNCTUATION,
                trailingScope = TokenScope.PUNCTUATION,
            )
        }
    }

    private val HighlightToken.text: String
        get() = content

    private val HighlightToken.tokenType: String?
        get() = when (this) {
            is HighlightToken.Plain -> null
            is HighlightToken.Styled -> type
        }
}
