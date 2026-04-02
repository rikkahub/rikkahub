package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDetailsGroupingTest {
    private val parser = MarkdownParser(
        GFMFlavourDescriptor(
            makeHttpsAutoLinks = true,
            useSafeLinks = true,
        )
    )

    @Test
    fun `collectMarkdownDetailsBlock should group markdown body inside details`() {
        val content = """
            <details>
            <summary>点我展开/收起</summary>

            这里是被折叠的内容，
            支持**加粗**、*斜体*、甚至 [链接](https://github.com)。

            </details>
        """.trimIndent()

        val astTree = parser.buildMarkdownTreeFromString(content)
        val detailsBlock = collectMarkdownDetailsBlock(astTree.children, 0, content)

        assertNotNull(detailsBlock)
        assertEquals("点我展开/收起", detailsBlock?.summaryText)
        assertFalse(detailsBlock?.isOpenByDefault ?: true)
        assertEquals("", detailsBlock?.trailingMarkdown)

        val bodyText = detailsBlock?.contentMarkdown.orEmpty()
        assertTrue(bodyText.contains("这里是被折叠的内容"))
        assertTrue(bodyText.contains("**加粗**"))
        assertFalse(bodyText.contains("</details>"))
    }

    @Test
    fun `collectMarkdownDetailsBlock should preserve siblings around details`() {
        val content = """
            展开前的说明

            <details>
            <summary>More</summary>

            hidden

            </details>

            展开后的说明
        """.trimIndent()

        val blocks = collectRenderedBlocks(content)
        val siblingText = blocks
            .filterIsInstance<ASTNode>()
            .joinToString("\n") { content.substring(it.startOffset, it.endOffset) }

        assertEquals(1, blocks.count { it is MarkdownDetailsBlock })
        assertTrue(siblingText.contains("展开前的说明"))
        assertTrue(siblingText.contains("展开后的说明"))
    }

    @Test
    fun `collectMarkdownDetailsBlock should support compact single line details`() {
        val content = "<details><summary>More</summary>hidden</details>"

        val astTree = parser.buildMarkdownTreeFromString(content)
        val detailsBlock = collectMarkdownDetailsBlock(astTree.children, 0, content)

        assertNotNull(detailsBlock)
        assertEquals("More", detailsBlock?.summaryText)
        assertEquals("hidden", detailsBlock?.contentMarkdown)
        assertEquals("", detailsBlock?.trailingMarkdown)
    }

    @Test
    fun `collectMarkdownDetailsBlock should keep html body from opening block`() {
        val content = "<details><summary><strong>More</strong></summary><div>html</div></details>"

        val astTree = parser.buildMarkdownTreeFromString(content)
        val detailsBlock = collectMarkdownDetailsBlock(astTree.children, 0, content)

        assertNotNull(detailsBlock)
        assertTrue(detailsBlock?.summaryHtml?.contains("<strong>More</strong>") == true)
        assertEquals("<div>html</div>", detailsBlock?.contentMarkdown)
    }

    @Test
    fun `collectMarkdownDetailsBlock should expose trailing markdown after closing tag`() {
        val content = "<details><summary>More</summary>hidden</details>after"

        val astTree = parser.buildMarkdownTreeFromString(content)
        val detailsBlock = collectMarkdownDetailsBlock(astTree.children, 0, content)

        assertNotNull(detailsBlock)
        assertEquals("after", detailsBlock?.trailingMarkdown)
    }

    private fun collectRenderedBlocks(content: String): List<Any> {
        val astTree = parser.buildMarkdownTreeFromString(content)
        val blocks = mutableListOf<Any>()
        var index = 0

        while (index < astTree.children.size) {
            val detailsBlock = collectMarkdownDetailsBlock(astTree.children, index, content)
            if (detailsBlock != null) {
                blocks += detailsBlock
                if (detailsBlock.trailingMarkdown.isNotBlank()) {
                    blocks.addAll(parser.buildMarkdownTreeFromString(detailsBlock.trailingMarkdown).children)
                }
                index += detailsBlock.consumedNodeCount
            } else {
                blocks += astTree.children[index]
                index++
            }
        }

        return blocks
    }
}
