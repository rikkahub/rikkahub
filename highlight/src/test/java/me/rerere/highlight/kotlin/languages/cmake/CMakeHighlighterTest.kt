package me.rerere.highlight.kotlin.languages.cmake

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import me.rerere.highlight.kotlin.assertTokenContaining
import org.junit.Assert.assertTrue
import org.junit.Test

class CMakeHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights CMake commands case insensitively`() {
        val code = """
            CMAKE_MINIMUM_REQUIRED(VERSION 3.28)
            project(Rikka LANGUAGES C CXX)
            add_executable(rikka main.cpp)
            custom_command(argument)
        """.trimIndent()

        val tokens = highlighter.highlight(code, "cmake")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("cmake"))
        assertTrue(highlighter.supports("cmake.in"))
        assertToken(tokens, "CMAKE_MINIMUM_REQUIRED", "keyword")
        assertToken(tokens, "project", "keyword")
        assertToken(tokens, "add_executable", "keyword")
        assertToken(tokens, "custom_command", "function")
        assertToken(tokens, "3.28", "number")
    }

    @Test
    fun `highlights CMake variables and quoted interpolation`() {
        val code = """
            set(SOURCE_DIR "${'$'}{PROJECT_SOURCE_DIR}/src")
            message(STATUS "Compiler: ${'$'}ENV{CXX}")
            set(OUTPUT ${'$'}<TARGET_FILE:rikka>)
            option(ENABLE_TESTS "Build tests" ON)
        """.trimIndent()

        val tokens = highlighter.highlight(code, "cmake")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "\${PROJECT_SOURCE_DIR}", "variable")
        assertToken(tokens, "\$ENV{CXX}", "variable")
        assertToken(tokens, "\$<TARGET_FILE:rikka>", "variable")
        assertTokenContaining(tokens, "\"Compiler: ", "string")
        assertToken(tokens, "ON", "boolean")
    }

    @Test
    fun `highlights CMake bracket arguments and comments`() {
        val code = """
            #[=[
            bracket comment
            ]=]
            set(SCRIPT [=[
            message("inside")
            ]=])
            # line comment
        """.trimIndent()

        val tokens = highlighter.highlight(code, "cmake.in")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "#[=[\nbracket comment\n]=]", "comment")
        assertToken(tokens, "[=[\nmessage(\"inside\")\n]=]", "string")
        assertToken(tokens, "# line comment", "comment")
    }

    @Test
    fun `preserves incomplete CMake constructs`() {
        val samples = listOf(
            """set(VALUE "unfinished""",
            "set(VALUE \${unfinished)",
            "#[[ unfinished",
            "set(VALUE [=[unfinished)",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "cmake"))
        }
    }
}
