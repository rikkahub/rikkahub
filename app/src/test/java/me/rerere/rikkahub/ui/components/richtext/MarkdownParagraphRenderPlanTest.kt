package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParagraphRenderPlanTest {
    @Test
    fun promotesInlineMathWhenWiderThanContainer() {
        val markdown = "a \$x\$ b"
        val paragraphNode = parseFirstParagraph(markdown)

        val plan = buildParagraphRenderPlan(
            paragraphNode = paragraphNode,
            content = markdown,
            containerWidthPx = 100f,
            textSizePx = 16f,
            enableLatexRendering = true,
            allowPromotion = true,
            measureLatex = { rawLatex, _ ->
                if (rawLatex == "\$x\$") LatexSize(widthPx = 120, heightPx = 10) else null
            },
        )

        val blockMathIndex = plan.items.indexOfFirst { item ->
            item is ParagraphItem.BlockMath && item.latex == "\$x\$"
        }
        assertTrue("Expected a promoted BlockMath item", blockMathIndex >= 0)
        assertTrue(
            "Expected Inline before BlockMath",
            plan.items.take(blockMathIndex).any { it is ParagraphItem.Inline }
        )
        assertTrue(
            "Expected Inline after BlockMath",
            plan.items.drop(blockMathIndex + 1).any { it is ParagraphItem.Inline }
        )

        val inlineMathNode = findFirstNode(paragraphNode) { it.type == GFMElementTypes.INLINE_MATH }
        assertNotNull("Expected INLINE_MATH node in AST", inlineMathNode)
        assertTrue(
            "Expected measured size to be cached",
            plan.inlineMathSizeCache.containsKey(inlineMathNode!!.startOffset to inlineMathNode.endOffset)
        )
        assertEquals(120, plan.inlineMathSizeCache[inlineMathNode.startOffset to inlineMathNode.endOffset]?.widthPx)
    }

    @Test
    fun keepsInlineMathWhenFits() {
        val markdown = "a \$x\$ b"
        val paragraphNode = parseFirstParagraph(markdown)

        val plan = buildParagraphRenderPlan(
            paragraphNode = paragraphNode,
            content = markdown,
            containerWidthPx = 100f,
            textSizePx = 16f,
            enableLatexRendering = true,
            allowPromotion = true,
            measureLatex = { rawLatex, _ ->
                if (rawLatex == "\$x\$") LatexSize(widthPx = 100, heightPx = 10) else null
            },
        )

        assertFalse("Expected no BlockMath items", plan.items.any { it is ParagraphItem.BlockMath })
    }

    @Test
    fun doesNotPromoteWhenLatexDisabled() {
        val markdown = "a \$x\$ b"
        val paragraphNode = parseFirstParagraph(markdown)

        val plan = buildParagraphRenderPlan(
            paragraphNode = paragraphNode,
            content = markdown,
            containerWidthPx = 100f,
            textSizePx = 16f,
            enableLatexRendering = false,
            allowPromotion = true,
            measureLatex = { _, _ ->
                LatexSize(widthPx = 120, heightPx = 10)
            },
        )

        assertFalse("Expected no BlockMath items", plan.items.any { it is ParagraphItem.BlockMath })
    }

    private fun parseFirstParagraph(markdown: String): ASTNode {
        val flavour = GFMFlavourDescriptor(
            makeHttpsAutoLinks = true,
            useSafeLinks = true,
        )
        val parser = MarkdownParser(flavour)
        val root = parser.buildMarkdownTreeFromString(markdown)
        return findFirstNode(root) { it.type == MarkdownElementTypes.PARAGRAPH }
            ?: error("No PARAGRAPH node found")
    }

    private fun findFirstNode(node: ASTNode, predicate: (ASTNode) -> Boolean): ASTNode? {
        if (predicate(node)) return node
        node.children.forEach { child ->
            val found = findFirstNode(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
