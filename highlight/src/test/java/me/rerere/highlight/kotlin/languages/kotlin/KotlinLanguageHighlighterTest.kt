package me.rerere.highlight.kotlin.languages.kotlin

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinLanguageHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `supports Kotlin aliases and highlights declarations`() {
        val code = """
            @JvmInline
            value class UserId(val value: Long)

            data class User(val id: UserId, val name: String)

            fun greet(user: User): String {
                val message = "Hello, ${'$'}{user.name.uppercase()}!"
                return message
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "kotlin")

        assertPreservesSource(code, tokens)
        listOf("kotlin", "kt", "kts", "ktm", "ktx").forEach {
            assertTrue(it, highlighter.supports(it))
        }
        assertToken(tokens, "@JvmInline", "important")
        assertToken(tokens, "value", "keyword")
        assertToken(tokens, "UserId", "class-name")
        assertToken(tokens, "Long", "class-name")
        assertToken(tokens, "User", "class-name")
        assertToken(tokens, "greet", "function")
        assertToken(tokens, "message", "variable")
        assertToken(tokens, "${'$'}{", "punctuation")
        assertToken(tokens, "name", "property")
        assertToken(tokens, "uppercase", "function")
    }

    @Test
    fun `highlights raw strings simple templates annotations and labels`() {
        val tripleQuote = "\"\"\""
        val code = """
            #!/usr/bin/env kotlin
            @file:JvmName("Demo")

            fun main() {
                val name = "Rikka"
                loop@ for (index in 0 until 2u) {
                    println(${tripleQuote}Hello ${'$'}name
                        from raw string${tripleQuote})
                    return@loop
                }
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "kts")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "@file:JvmName", "important")
        assertToken(tokens, "loop@", "important")
        assertToken(tokens, "@loop", "important")
        assertToken(tokens, "2u", "number")
        assertToken(tokens, "${'$'}name", "variable")
        assertTokenContaining(tokens, "from raw string", "string")
    }

    @Test
    fun `highlights nested comments operators and built-in types`() {
        val code = """
            /* outer
               /* nested */
            */
            val answer: Int? = if (enabled) 42 else null
            val range = 1..<10
        """.trimIndent()

        val tokens = highlighter.highlight(code, "kt")

        assertPreservesSource(code, tokens)
        assertTokenContaining(tokens, "/* nested */", "comment")
        assertToken(tokens, "answer", "variable")
        assertToken(tokens, "Int", "class-name")
        assertToken(tokens, "if", "keyword")
        assertToken(tokens, "42", "number")
        assertToken(tokens, "null", "constant")
        assertToken(tokens, "..<", "operator")
    }

    @Test
    fun `preserves incomplete and nested Kotlin templates`() {
        val samples = listOf(
            """val value = "unfinished ${'$'}{name""",
            "val value = \"\"\"unfinished ${'$'}name",
            "/* outer /* nested",
            "val value = \"nested \${\"inner \$name\"}\"",
            "fun `unfinished name",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "kotlin"))
        }
    }
}
