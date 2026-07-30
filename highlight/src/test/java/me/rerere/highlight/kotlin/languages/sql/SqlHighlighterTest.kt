package me.rerere.highlight.kotlin.languages.sql

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights queries case-insensitively`() {
        val code = """
            SELECT u.id, COUNT(*) AS total
            FROM "user" AS u
            WHERE u.enabled = TRUE
              AND u.name <> 'O''Reilly'
              AND u.score >= 42.5;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "sql")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("SQL"))
        assertToken(tokens, "SELECT", "keyword")
        assertToken(tokens, "COUNT", "function")
        assertToken(tokens, "\"user\"", "class-name")
        assertToken(tokens, "TRUE", "boolean")
        assertToken(tokens, "'O''Reilly'", "string")
        assertToken(tokens, "42.5", "number")
        assertToken(tokens, "<>", "operator")
    }

    @Test
    fun `highlights comments parameters and PostgreSQL strings`() {
        val code = """
            -- load one row
            SELECT ${'$'}${'$'}text with 'quotes'${'$'}${'$'}, :name, ${'$'}1, @tenant
            FROM accounts
            WHERE id = ?; /* trailing */
        """.trimIndent()

        val tokens = highlighter.highlight(code, "sql")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "-- load one row", "comment")
        assertToken(tokens, "${'$'}${'$'}text with 'quotes'${'$'}${'$'}", "string")
        assertToken(tokens, ":name", "variable")
        assertToken(tokens, "${'$'}1", "variable")
        assertToken(tokens, "@tenant", "variable")
        assertToken(tokens, "?", "variable")
        assertToken(tokens, "accounts", "class-name")
        assertToken(tokens, "/* trailing */", "comment")
    }

    @Test
    fun `highlights data types and vendor quoted identifiers`() {
        val code = """
            CREATE TABLE `event-log` (
              [event_id] BIGINT PRIMARY KEY,
              payload JSONB NOT NULL
            );
        """.trimIndent()

        val tokens = highlighter.highlight(code, "sql")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "TABLE", "keyword")
        assertToken(tokens, "`event-log`", "class-name")
        assertToken(tokens, "[event_id]", "class-name")
        assertToken(tokens, "BIGINT", "class-name")
        assertToken(tokens, "JSONB", "class-name")
        assertToken(tokens, "NULL", "constant")
    }

    @Test
    fun `preserves incomplete SQL constructs`() {
        val samples = listOf(
            "SELECT 'unfinished",
            "SELECT \$tag\$unfinished",
            "SELECT /* unfinished",
            "SELECT \"unfinished",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "sql"))
        }
    }
}
