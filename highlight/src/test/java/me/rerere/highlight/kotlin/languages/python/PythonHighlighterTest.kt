package me.rerere.highlight.kotlin.languages.python

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights Python declarations decorators and types`() {
        val code = """
            @dataclass
            class Greeter(Base):
                def greet(self, name: str) -> str:
                    return print(name)
        """.trimIndent()

        val tokens = highlighter.highlight(code, "python")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("python"))
        assertTrue(highlighter.supports("py"))
        assertTrue(highlighter.supports("gyp"))
        assertTrue(highlighter.supports("ipython"))
        assertToken(tokens, "@dataclass", "important")
        assertToken(tokens, "class", "keyword")
        assertToken(tokens, "Greeter", "class-name")
        assertToken(tokens, "Base", "class-name")
        assertToken(tokens, "def", "keyword")
        assertToken(tokens, "greet", "function")
        assertToken(tokens, "self", "variable")
        assertToken(tokens, "str", "class-name")
        assertToken(tokens, "print", "function")
    }

    @Test
    fun `highlights formatted and triple quoted strings`() {
        val code = listOf(
            """message = f"Hello, {user.name.upper()}! {{ok}}"""",
            "document = '''",
            "multiple lines",
            "'''",
        ).joinToString("\n")

        val tokens = highlighter.highlight(code, "py")

        assertPreservesSource(code, tokens)
        assertTokenContaining(tokens, """f"Hello, """, "string")
        assertToken(tokens, "name", "property")
        assertToken(tokens, "upper", "function")
        assertTokenContaining(tokens, "! {{ok}}\"", "string")
        assertToken(tokens, "'''\nmultiple lines\n'''", "string")
    }

    @Test
    fun `highlights Python numbers comments literals and operators`() {
        val code = """
            # type: list[int]
            enabled = True
            missing = None
            mask = 0xCA_FE
            ratio = .5e+2
            value = 3.14j
            power = value ** 2
        """.trimIndent()

        val tokens = highlighter.highlight(code, "python")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "# type: list[int]", "comment")
        assertToken(tokens, "True", "boolean")
        assertToken(tokens, "None", "constant")
        assertToken(tokens, "0xCA_FE", "number")
        assertToken(tokens, ".5e+2", "number")
        assertToken(tokens, "3.14j", "number")
        assertToken(tokens, "**", "operator")
    }

    @Test
    fun `preserves incomplete Python constructs`() {
        val samples = listOf(
            """value = "unfinished""",
            """value = f"{user.name""",
            "document = '''unfinished",
            "@decorator(value=",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "python"))
        }
    }
}
