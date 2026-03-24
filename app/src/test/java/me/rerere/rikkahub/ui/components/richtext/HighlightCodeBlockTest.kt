package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightCodeBlockTest {
    @Test
    fun mermaid_is_richly_rendered_when_enabled_for_complete_block() {
        assertTrue(
            shouldRenderMermaidRichly(
                language = "mermaid",
                completeCodeBlock = true,
                richRenderingEnabled = true
            )
        )
    }

    @Test
    fun mermaid_falls_back_to_plain_code_block_when_rich_render_is_disabled() {
        assertFalse(
            shouldRenderMermaidRichly(
                language = "mermaid",
                completeCodeBlock = true,
                richRenderingEnabled = false
            )
        )
    }

    @Test
    fun incomplete_mermaid_block_is_not_richly_rendered() {
        assertFalse(
            shouldRenderMermaidRichly(
                language = "mermaid",
                completeCodeBlock = false,
                richRenderingEnabled = true
            )
        )
    }
}
