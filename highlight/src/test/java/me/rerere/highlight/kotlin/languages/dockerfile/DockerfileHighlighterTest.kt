package me.rerere.highlight.kotlin.languages.dockerfile

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerfileHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `supports Dockerfile aliases and highlights common instructions`() {
        val code = """
            FROM eclipse-temurin:21
            ARG DEBUG=0
            ENV APP_HOME="/opt/app"
            EXPOSE 8080
            COPY build/app.jar ${'$'}APP_HOME/app.jar
            CMD ["java", "-jar", "/opt/app/app.jar"]
        """.trimIndent()

        val tokens = highlighter.highlight(code, "dockerfile")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "FROM", "keyword")
        assertToken(tokens, "ARG", "keyword")
        assertToken(tokens, "0", "number")
        assertToken(tokens, "\"/opt/app\"", "string")
        assertToken(tokens, "EXPOSE", "keyword")
        assertToken(tokens, "8080", "number")
        assertToken(tokens, "COPY", "keyword")
        assertToken(tokens, "${'$'}APP_HOME", "variable")
        assertToken(tokens, "CMD", "keyword")
        listOf("dockerfile", "docker").forEach { assertTrue(it, highlighter.supports(it)) }
    }

    @Test
    fun `uses Bash grammar for shell instructions and continuations`() {
        val code = """
            RUN name="world" && \
                echo "hello ${'$'}{name}"
            ONBUILD RUN echo "child"
            WORKDIR "/workspace"
            ENTRYPOINT ["/bin/sh", "-c"]
        """.trimIndent()

        val tokens = highlighter.highlight(code, "docker")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "RUN", "keyword")
        assertToken(tokens, "name", "variable")
        assertToken(tokens, "echo", "function")
        assertToken(tokens, "${'$'}{name}", "variable")
        assertToken(tokens, "ONBUILD", "keyword")
        assertToken(tokens, "WORKDIR", "keyword")
        assertToken(tokens, "\"/workspace\"", "string")
        assertToken(tokens, "ENTRYPOINT", "keyword")
    }

    @Test
    fun `highlights Docker comments and RUN heredocs`() {
        val code = """
            # syntax=docker/dockerfile:1
            RUN <<EOF
            echo "building"
            # shell comment inside heredoc
            EOF
            USER app
        """.trimIndent()

        val tokens = highlighter.highlight(code, "dockerfile")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "# syntax=docker/dockerfile:1", "comment")
        assertToken(tokens, "RUN", "keyword")
        assertTokenContaining(tokens, "# shell comment inside heredoc", "string")
        assertToken(tokens, "USER", "keyword")
    }

    @Test
    fun `preserves incomplete Dockerfile constructs`() {
        val samples = listOf(
            "RUN echo \"unfinished",
            "RUN echo value \\",
            "RUN <<EOF\nunfinished",
            "ENV VALUE=\"unterminated",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "dockerfile"))
        }
    }
}
