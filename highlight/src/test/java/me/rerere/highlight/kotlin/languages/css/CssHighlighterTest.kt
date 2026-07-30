package me.rerere.highlight.kotlin.languages.css

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class CssHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights selectors declarations and at rules`() {
        val code = """
            @media screen and (min-width: 48rem) {
              #app > .card:hover,
              button[disabled] {
                --accent-color: #ff8800;
                color: var(--accent-color);
                margin: 1.5rem 0;
              }
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "css")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("CSS"))
        assertToken(tokens, "@media", "keyword")
        assertToken(tokens, "and", "keyword")
        assertToken(tokens, "min-width", "property")
        assertToken(tokens, "#app", "constant")
        assertToken(tokens, ".card", "class-name")
        assertToken(tokens, ":hover", "keyword")
        assertToken(tokens, "button", "tag")
        assertToken(tokens, "disabled", "attr-name")
        assertToken(tokens, "--accent-color", "variable")
        assertToken(tokens, "#ff8800", "number")
        assertToken(tokens, "color", "property")
        assertToken(tokens, "var", "function")
        assertToken(tokens, "48rem", "number")
        assertToken(tokens, "1.5rem", "number")
    }

    @Test
    fun `highlights URLs strings comments and important values`() {
        val code = """
            /* asset styles */
            .hero::before {
              content: "a; }";
              background-image: url("/images/hero (dark).png");
              display: block !important;
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "css")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "/* asset styles */", "comment")
        assertToken(tokens, "::before", "keyword")
        assertToken(tokens, "content", "property")
        assertToken(tokens, "\"a; }\"", "string")
        assertToken(tokens, "background-image", "property")
        assertToken(tokens, "url", "function")
        assertToken(tokens, "\"/images/hero (dark).png\"", "string")
        assertToken(tokens, "!important", "important")
    }

    @Test
    fun `highlights attribute selector operators and values`() {
        val code = """.item[data-kind^="primary" i] { opacity: 0.75; }"""

        val tokens = highlighter.highlight(code, "css")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "data-kind", "attr-name")
        assertToken(tokens, "^=", "operator")
        assertToken(tokens, "\"primary\"", "attr-value")
        assertToken(tokens, "i", "attr-value")
        assertToken(tokens, "opacity", "property")
        assertToken(tokens, "0.75", "number")
    }

    @Test
    fun `preserves incomplete CSS constructs`() {
        val samples = listOf(
            ".card { color: ",
            ".card { content: \"unfinished",
            ".card { background: url(image",
            "/* unfinished",
            "[data-value=\"unfinished",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "css"))
        }
    }
}
