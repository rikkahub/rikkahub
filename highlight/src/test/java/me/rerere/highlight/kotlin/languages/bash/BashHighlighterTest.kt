package me.rerere.highlight.kotlin.languages.bash

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class BashHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `supports shell aliases and highlights common Bash syntax`() {
        val code = """
            #!/usr/bin/env bash
            name="world"
            function greet() {
              local count=2
              if [[ ${'$'}count -gt 0 ]]; then
                echo "Hello ${'$'}{name}: ${'$'}(date +%Y)"
              fi
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "bash")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "#!/usr/bin/env bash", "comment")
        assertToken(tokens, "name", "variable")
        assertToken(tokens, "function", "keyword")
        assertToken(tokens, "greet", "function")
        assertToken(tokens, "if", "keyword")
        assertToken(tokens, "${'$'}count", "variable")
        assertToken(tokens, "${'$'}{name}", "variable")
        assertToken(tokens, "date", "function")
        listOf("bash", "sh", "zsh", "shell").forEach {
            assertTrue(it, highlighter.supports(it))
        }
    }

    @Test
    fun `highlights heredocs as strings without expanding their contents`() {
        val code = """
            cat <<'EOF' > output.txt
            literal ${'$'}name
            EOF
            echo "done"
        """.trimIndent()

        val tokens = highlighter.highlight(code, "sh")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "<<'EOF'", "operator")
        assertTokenContaining(tokens, "literal ${'$'}name\nEOF", "string")
        assertToken(tokens, "echo", "function")
        assertToken(tokens, "\"done\"", "string")
    }

    @Test
    fun `distinguishes comments and supports arithmetic and command substitution`() {
        val code = """
            echo value#suffix # real comment
            total=${'$'}((count + ${'$'}(wc -l < input.txt)))
        """.trimIndent()

        val tokens = highlighter.highlight(code, "bash")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "# real comment", "comment")
        assertToken(tokens, "total", "variable")
        assertToken(tokens, "${'$'}((", "punctuation")
        assertToken(tokens, "${'$'}(", "punctuation")
        assertToken(tokens, "wc", "function")
    }

    @Test
    fun `preserves incomplete Bash constructs`() {
        val samples = listOf(
            """echo "hello ${'$'}{name""",
            """value=${'$'}(echo nested""",
            "cat <<EOF\nunfinished",
            "echo 'unterminated",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "bash"))
        }
    }
}
