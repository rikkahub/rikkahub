package me.rerere.highlight.kotlin

import me.rerere.highlight.HighlightToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `unsupported language is returned as plain text`() {
        val code = "fun main() = Unit"

        assertEquals(listOf(HighlightToken.Plain(code)), highlighter.highlight(code, "kotlin"))
        assertFalse(highlighter.supports("kotlin"))
        assertTrue(highlighter.highlight("", "js").isEmpty())
    }

    @Test
    fun `supports JavaScript and TypeScript aliases`() {
        listOf("javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx", "mts", "cts")
            .forEach { assertTrue(it, highlighter.supports(it)) }
    }

    @Test
    fun `JavaScript tokens preserve the original source`() {
        val code = """
            // comment
            const answer = 0x2a;
            function greet(name) {
                return "Hello, " + name;
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "js")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertToken(tokens, "// comment", "comment")
        assertToken(tokens, "const", "keyword")
        assertToken(tokens, "0x2a", "number")
        assertToken(tokens, "function", "keyword")
        assertToken(tokens, "greet", "function")
        assertToken(tokens, "\"Hello, \"", "string")
    }

    @Test
    fun `distinguishes regular expressions from division`() {
        val code = "const matcher = /a[b/]c+/giu; const ratio = total / count;"

        val tokens = highlighter.highlight(code, "javascript")

        assertToken(tokens, "/a[b/]c+/giu", "regex")
        assertToken(tokens, "/", "operator")
        assertEquals(1, tokens.count { it.text == "/a[b/]c+/giu" })
    }

    @Test
    fun `template interpolation is recursively highlighted`() {
        val code = "const message = `Hello \${user.name.toUpperCase()}!`;"

        val tokens = highlighter.highlight(code, "js")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertToken(tokens, "`Hello ", "string")
        assertToken(tokens, "\${", "punctuation")
        assertToken(tokens, "name", "property")
        assertToken(tokens, "toUpperCase", "property")
        assertToken(tokens, "!`", "string")
    }

    @Test
    fun `highlights TypeScript declarations types and decorators`() {
        val code = """
            @sealed
            interface Result<T> extends Promise<T> {
                readonly value?: string;
            }
            type Handler = (value: number) => Result<boolean>;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "typescript")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertToken(tokens, "@sealed", "important")
        assertToken(tokens, "interface", "keyword")
        assertToken(tokens, "Result", "class-name")
        assertToken(tokens, "readonly", "keyword")
        assertToken(tokens, "value", "property")
        assertToken(tokens, "string", "class-name")
        assertToken(tokens, "type", "keyword")
        assertToken(tokens, "number", "class-name")
        assertToken(tokens, "boolean", "class-name")
    }

    @Test
    fun `highlights function variables calls properties and constants`() {
        val code = """
            const load = async (url) => fetch(url);
            console.log(JSON.stringify({ status: true, EMPTY_VALUE: null }));
        """.trimIndent()

        val tokens = highlighter.highlight(code, "js")

        assertToken(tokens, "load", "function")
        assertToken(tokens, "fetch", "function")
        assertToken(tokens, "console", "variable")
        assertToken(tokens, "log", "property")
        assertToken(tokens, "JSON", "class-name")
        assertToken(tokens, "stringify", "property")
        assertToken(tokens, "status", "property")
        assertToken(tokens, "true", "boolean")
        assertToken(tokens, "EMPTY_VALUE", "property")
        assertToken(tokens, "null", "constant")
    }

    @Test
    fun `highlights JSX tags attributes and embedded expressions`() {
        val code = """const view = <Button aria-label="Save" onClick={() => save()}>Save {name}</Button>;"""

        val tokens = highlighter.highlight(code, "jsx")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertToken(tokens, "Button", "tag")
        assertToken(tokens, "aria-label", "attr-name")
        assertToken(tokens, "\"Save\"", "attr-value")
        assertToken(tokens, "onClick", "attr-name")
        assertToken(tokens, "save", "function")
        assertTrue(tokens.count { it.text == "Button" && it.tokenType == "tag" } == 2)
    }

    @Test
    fun `highlights self-closing and nested JSX`() {
        val code = """const view = <Panel><Icon name="save" /><span>{label} 1 < 2</span></Panel>;"""

        val tokens = highlighter.highlight(code, "tsx")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertToken(tokens, "Panel", "tag")
        assertToken(tokens, "Icon", "tag")
        assertToken(tokens, "span", "tag")
        assertToken(tokens, "name", "attr-name")
    }

    @Test
    fun `highlights lower-case declaration names`() {
        val code = "class service extends baseService {}"

        val tokens = highlighter.highlight(code, "js")

        assertToken(tokens, "service", "class-name")
        assertToken(tokens, "baseService", "class-name")
    }

    @Test
    fun `does not treat TypeScript generics as JSX`() {
        val code = "const identity = <T extends object>(value: T): T => value;"

        val tokens = highlighter.highlight(code, "ts")

        assertEquals(code, tokens.joinToString(separator = "") { it.text })
        assertFalse(tokens.any { it.tokenType == "tag" })
        assertToken(tokens, "extends", "keyword")
        assertToken(tokens, "object", "class-name")
    }

    @Test
    fun `preserves source for incomplete and nested constructs`() {
        val samples = listOf(
            "/* unterminated",
            "'unterminated\nconst next = 1;",
            "const value = `outer \${`inner \${name}`}`;",
            "const value = `nested \${{ answer: 42 }.answer}`;",
            "const node = <><span>{value}</span><br /></>;",
            "const big = 1_000_000n;",
        )

        samples.forEach { code ->
            val tokens = highlighter.highlight(code, "tsx")
            assertEquals(code, tokens.joinToString(separator = "") { it.text })
        }
    }

    private fun assertToken(tokens: List<HighlightToken>, content: String, type: String) {
        assertTrue(
            "Expected token '$content' of type '$type', got ${tokens.describe()}",
            tokens.any { it.text == content && it.tokenType == type },
        )
    }

    private val HighlightToken.text: String
        get() = when (this) {
            is HighlightToken.Plain -> this.content
            is HighlightToken.Token.StringContent -> this.content
            is HighlightToken.Token.StringListContent -> this.content.joinToString(separator = "")
            is HighlightToken.Token.Nested -> this.content.joinToString(separator = "") { it.text }
        }

    private val HighlightToken.tokenType: String?
        get() = when (this) {
            is HighlightToken.Plain -> null
            is HighlightToken.Token.StringContent -> this.type
            is HighlightToken.Token.StringListContent -> this.type
            is HighlightToken.Token.Nested -> this.type
        }

    private fun List<HighlightToken>.describe(): String {
        return joinToString(prefix = "[", postfix = "]") { "${it.tokenType}:${it.text}" }
    }
}
