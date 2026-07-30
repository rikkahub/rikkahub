package me.rerere.highlight.kotlin.languages.toml

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights TOML tables keys and scalar values`() {
        val code = """
            title = "TOML Example"

            [owner]
            name = "Tom"
            dob = 1979-05-27T07:32:00Z
            enabled = true
            ports = [8000, 8001, 8002]
        """.trimIndent()

        val tokens = highlighter.highlight(code, "toml")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("toml"))
        assertToken(tokens, "title", "property")
        assertToken(tokens, "owner", "class-name")
        assertToken(tokens, "\"Tom\"", "string")
        assertToken(tokens, "1979-05-27T07:32:00Z", "number")
        assertToken(tokens, "true", "boolean")
        assertToken(tokens, "8000", "number")
    }

    @Test
    fun `highlights dotted and quoted keys plus array tables`() {
        val code = """
            [[products]]
            "display name" = "Hammer"
            database.settings.max_connections = 5_000
            color = 0xDDAA00
        """.trimIndent()

        val tokens = highlighter.highlight(code, "toml")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "products", "class-name")
        assertToken(tokens, "\"display name\"", "property")
        assertToken(tokens, "database", "property")
        assertToken(tokens, "settings", "property")
        assertToken(tokens, "max_connections", "property")
        assertToken(tokens, "5_000", "number")
        assertToken(tokens, "0xDDAA00", "number")
    }

    @Test
    fun `keeps comments inside multiline strings as string content`() {
        val code = "\"\"\"\n# not a comment\nmulti-line\n\"\"\"\n# actual comment"

        val tokens = highlighter.highlight(code, "toml")

        assertPreservesSource(code, tokens)
        assertTokenContaining(tokens, "# not a comment", "string")
        assertToken(tokens, "# actual comment", "comment")
    }

    @Test
    fun `preserves incomplete TOML constructs`() {
        val samples = listOf(
            "title = \"unfinished",
            "[unfinished",
            "value = [1, 2, 3",
            "text = '''unterminated",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "toml"))
        }
    }
}
