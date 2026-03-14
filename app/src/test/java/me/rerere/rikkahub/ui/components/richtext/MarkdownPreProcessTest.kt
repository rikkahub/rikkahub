package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreProcessTest {
    private val parser = MarkdownParser(
        GFMFlavourDescriptor(
            makeHttpsAutoLinks = true,
            useSafeLinks = true
        )
    )

    @Test
    fun preprocess_skips_tilde_fence_content() {
        val markdown = """
            ~~~html
            <div>\(code\)</div>
            ~~~
            outside \(math\)
        """.trimIndent()

        val processed = preProcessMarkdownContent(markdown)

        assertTrue(processed.contains("<div>\\(code\\)</div>"))
        assertTrue(processed.contains("outside \$math\$"))
    }

    @Test
    fun preprocess_skips_long_backtick_fence_content() {
        val markdown = """
            ````html
            <script>
            const sample = "```";
            const latex = "\(code\)";
            </script>
            ````
            after \(math\)
        """.trimIndent()

        val processed = preProcessMarkdownContent(markdown)

        assertTrue(processed.contains("""const sample = "```";"""))
        assertTrue(processed.contains("""const latex = "\(code\)";"""))
        assertTrue(processed.contains("after \$math\$"))
    }

    @Test
    fun preprocess_skips_multi_backtick_inline_code() {
        val markdown = "Use ``\\(code\\)`` and \\(math\\)"

        val processed = preProcessMarkdownContent(markdown)

        assertEquals("Use ``\\(code\\)`` and \$math\$", processed)
    }

    @Test
    fun extract_code_fence_content_preserves_indentation_and_blank_lines() {
        val markdown = """
            ```html
                <pre>
                  keep
                </pre>

            ```
        """.trimIndent()
        val codeFence = findFirstCodeFence(parser.buildMarkdownTreeFromString(markdown))

        val code = extractCodeFenceContent(codeFence, markdown)

        assertEquals(
            "    <pre>\n      keep\n    </pre>\n\n",
            code
        )
    }

    @Test
    fun normalize_code_fence_content_for_display_trims_fence_newline_only() {
        val markdown = """
            ```kotlin
            println("hi")
            ```
        """.trimIndent()
        val codeFence = findFirstCodeFence(parser.buildMarkdownTreeFromString(markdown))

        val code = extractCodeFenceContent(codeFence, markdown)
        val displayCode = normalizeCodeFenceContentForDisplay(
            code = code.orEmpty(),
            completeCodeBlock = true,
        )

        assertEquals("println(\"hi\")", displayCode)
    }

    @Test
    fun normalize_code_fence_content_for_display_preserves_intentional_blank_line() {
        val markdown = """
            ```html
            <div>keep</div>

            ```
        """.trimIndent()
        val codeFence = findFirstCodeFence(parser.buildMarkdownTreeFromString(markdown))

        val code = extractCodeFenceContent(codeFence, markdown)
        val displayCode = normalizeCodeFenceContentForDisplay(
            code = code.orEmpty(),
            completeCodeBlock = true,
        )

        assertEquals("<div>keep</div>\n", displayCode)
    }

    private fun findFirstCodeFence(node: ASTNode): ASTNode {
        if (node.type == MarkdownElementTypes.CODE_FENCE) {
            return node
        }
        node.children.forEach { child ->
            val found = findFirstCodeFenceOrNull(child)
            if (found != null) {
                return found
            }
        }
        error("No code fence found")
    }

    private fun findFirstCodeFenceOrNull(node: ASTNode): ASTNode? {
        if (node.type == MarkdownElementTypes.CODE_FENCE) {
            return node
        }
        node.children.forEach { child ->
            val found = findFirstCodeFenceOrNull(child)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
