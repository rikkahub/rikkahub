package me.rerere.highlight.kotlin.languages.yaml

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class YamlHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `supports YAML aliases and highlights mappings sequences and scalars`() {
        val code = """
            name: Rikka
            enabled: true
            retries: 3
            missing: null
            created: 2001-12-15T02:59:43.1Z
            tags:
              - kotlin
              - android # mobile
        """.trimIndent()

        val tokens = highlighter.highlight(code, "yaml")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "name:", "attr-name")
        assertToken(tokens, "true", "boolean")
        assertToken(tokens, "3", "number")
        assertToken(tokens, "null", "constant")
        assertToken(tokens, "2001-12-15T02:59:43.1Z", "number")
        assertToken(tokens, "-", "punctuation")
        assertToken(tokens, "# mobile", "comment")
        listOf("yaml", "yml").forEach { assertTrue(it, highlighter.supports(it)) }
    }

    @Test
    fun `highlights quoted and flow values tags anchors and aliases`() {
        val code = """
            defaults: &defaults
              image: "app:latest"
            service:
              <<: *defaults
              ports: [8080, 8443]
              payload: !!map {key: 'value'}
        """.trimIndent()

        val tokens = highlighter.highlight(code, "yml")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "&defaults", "important")
        assertToken(tokens, "<<:", "attr-name")
        assertToken(tokens, "*defaults", "important")
        assertToken(tokens, "\"app:latest\"", "string")
        assertToken(tokens, "!!map", "class-name")
        assertToken(tokens, "8080", "number")
        assertToken(tokens, "key:", "attr-name")
        assertToken(tokens, "'value'", "string")
    }

    @Test
    fun `keeps indented block scalar content in one string`() {
        val code = """
            message: |-
              first line
              # part of the value
              last line
            next: value
        """.trimIndent()

        val tokens = highlighter.highlight(code, "yaml")

        assertPreservesSource(code, tokens)
        assertTokenContaining(tokens, "# part of the value", "string")
        assertToken(tokens, "next:", "attr-name")
    }

    @Test
    fun `preserves incomplete YAML constructs`() {
        val samples = listOf(
            "key: \"unfinished",
            "items: [one, two",
            "message: |\n  unfinished",
            "reference: &",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "yaml"))
        }
    }
}
