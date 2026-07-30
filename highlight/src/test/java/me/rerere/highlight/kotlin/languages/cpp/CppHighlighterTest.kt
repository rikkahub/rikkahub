package me.rerere.highlight.kotlin.languages.cpp

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class CppHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights modern C++ declarations and templates`() {
        val code = """
            #include <vector>

            template <typename T>
            class Repository final {
            public:
                std::vector<T> values;

                void add(T value) {
                    values.push_back(value);
                }
            };
        """.trimIndent()

        val tokens = highlighter.highlight(code, "cpp")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("cpp"))
        assertTrue(highlighter.supports("c++"))
        assertTrue(highlighter.supports("hpp"))
        assertToken(tokens, "#include", "important")
        assertToken(tokens, "<vector>", "string")
        assertToken(tokens, "template", "keyword")
        assertToken(tokens, "typename", "keyword")
        assertToken(tokens, "class", "keyword")
        assertToken(tokens, "Repository", "class-name")
        assertToken(tokens, "vector", "class-name")
        assertToken(tokens, "void", "class-name")
        assertToken(tokens, "add", "function")
        assertToken(tokens, "push_back", "function")
    }

    @Test
    fun `highlights C++ raw strings literals and operators`() {
        val code = """
            auto text = R"json({"value": 42})json";
            auto count = 1'000ULL;
            auto binary = 0b1010'0101;
            auto hex = 0x1.fp+3;
            Widget *widget = nullptr;
            auto order = left <=> right;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "c++")

        assertPreservesSource(code, tokens)
        assertToken(tokens, """R"json({"value": 42})json"""", "string")
        assertToken(tokens, "1'000ULL", "number")
        assertToken(tokens, "0b1010'0101", "number")
        assertToken(tokens, "0x1.fp+3", "number")
        assertToken(tokens, "Widget", "class-name")
        assertToken(tokens, "nullptr", "constant")
        assertToken(tokens, "<=>", "operator")
    }

    @Test
    fun `highlights C++ namespaces methods and comments`() {
        val code = """
            // line
            /* block */
            namespace demo {
                struct Service {};
                demo::Service service;
                service.start();
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "cc")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "// line", "comment")
        assertToken(tokens, "/* block */", "comment")
        assertToken(tokens, "namespace", "keyword")
        assertToken(tokens, "demo", "class-name")
        assertToken(tokens, "struct", "keyword")
        assertToken(tokens, "Service", "class-name")
        assertToken(tokens, "::", "operator")
        assertToken(tokens, "start", "function")
    }

    @Test
    fun `preserves incomplete C++ constructs`() {
        val samples = listOf(
            """auto text = R"tag(unfinished""",
            """std::string text = "unfinished""",
            "/* unfinished",
            "template <typename T",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "cpp"))
        }
    }
}
