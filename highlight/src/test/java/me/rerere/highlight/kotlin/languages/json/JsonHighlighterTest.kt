package me.rerere.highlight.kotlin.languages.json

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `supports JSON aliases and highlights object values`() {
        val code = """
            {
              "name": "Rikka",
              "enabled": true,
              "count": 42,
              "ratio": -1.5e+2,
              "value": null,
              "items": [1, 2, 3]
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "json")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "\"name\"", "property")
        assertToken(tokens, "\"Rikka\"", "string")
        assertToken(tokens, "true", "boolean")
        assertToken(tokens, "42", "number")
        assertToken(tokens, "-1.5e+2", "number")
        assertToken(tokens, "null", "constant")
        listOf("json", "jsonc", "json5").forEach {
            assertTrue(it, highlighter.supports(it))
        }
    }

    @Test
    fun `highlights JSONC comments and JSON5 extensions`() {
        val code = """
            {
              // line comment
              'hex': 0x2a,
              "special": Infinity,
              /* block comment */
              "missing": NaN
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "json5")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "// line comment", "comment")
        assertToken(tokens, "'hex'", "property")
        assertToken(tokens, "0x2a", "number")
        assertToken(tokens, "Infinity", "constant")
        assertToken(tokens, "/* block comment */", "comment")
        assertToken(tokens, "NaN", "constant")
    }

    @Test
    fun `preserves escaped and incomplete JSON strings`() {
        val samples = listOf(
            """{"escaped\"key": "line\nvalue"}""",
            """{"unfinished": "value""",
            """{"nested": [{"ok": false}]}""",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "json"))
        }
    }
}
