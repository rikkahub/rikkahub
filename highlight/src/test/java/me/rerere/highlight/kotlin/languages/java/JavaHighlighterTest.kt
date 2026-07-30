package me.rerere.highlight.kotlin.languages.java

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights modern Java declarations and expressions`() {
        val code = """
            @Deprecated
            public sealed class UserService implements AutoCloseable {
                private static final long MAX_COUNT = 1_000L;

                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "java")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("java"))
        assertTrue(highlighter.supports("jsp"))
        assertToken(tokens, "@Deprecated", "important")
        assertToken(tokens, "sealed", "keyword")
        assertToken(tokens, "class", "keyword")
        assertToken(tokens, "UserService", "class-name")
        assertToken(tokens, "AutoCloseable", "class-name")
        assertToken(tokens, "long", "class-name")
        assertToken(tokens, "MAX_COUNT", "constant")
        assertToken(tokens, "1_000L", "number")
        assertToken(tokens, "greet", "function")
        assertToken(tokens, "\"Hello, \"", "string")
    }

    @Test
    fun `highlights records annotations text blocks and method access`() {
        val code = listOf(
            """@SuppressWarnings("preview")""",
            "record Message(String text) {",
            "    String json() {",
            "        System.out.println(text);",
            "        return \"\"\"",
            """            {"text": "value"}""",
            "            \"\"\";",
            "    }",
            "}",
        ).joinToString("\n")

        val tokens = highlighter.highlight(code, "java")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "@SuppressWarnings", "important")
        assertToken(tokens, "record", "keyword")
        assertToken(tokens, "Message", "class-name")
        assertToken(tokens, "json", "function")
        assertToken(tokens, "System", "class-name")
        assertToken(tokens, "out", "property")
        assertToken(tokens, "println", "function")
        assertToken(
            tokens,
            "\"\"\"\n            {\"text\": \"value\"}\n            \"\"\"",
            "string",
        )
    }

    @Test
    fun `highlights Java comments literals and operators`() {
        val code = """
            // line
            /* block */
            boolean active = true;
            Object value = null;
            char newline = '\n';
            int shifted = 0x2A << 2;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "java")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "// line", "comment")
        assertToken(tokens, "/* block */", "comment")
        assertToken(tokens, "boolean", "class-name")
        assertToken(tokens, "true", "boolean")
        assertToken(tokens, "null", "constant")
        assertToken(tokens, "'\\n'", "string")
        assertToken(tokens, "0x2A", "number")
        assertToken(tokens, "<<", "operator")
    }

    @Test
    fun `preserves incomplete Java constructs`() {
        val samples = listOf(
            "class Example { String value = \"unfinished",
            "class Example { /* unfinished",
            "class Example { String value = \"\"\"unfinished",
            "@Annotation(value = \"unfinished",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "java"))
        }
    }
}
