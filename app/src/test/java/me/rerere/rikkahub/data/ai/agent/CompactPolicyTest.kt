package me.rerere.rikkahub.data.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.compact.DefaultCompactPolicy
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPolicyTest {
    @Test
    fun `default never auto compacts`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
        )
        assertFalse(DefaultCompactPolicy.shouldAutoCompact(messages))
    }

    @Test
    fun `buildCompressPrompt substitutes placeholders from template`() {
        val template = "C={content};T={target_tokens};L={locale};A={additional_context}"
        val out = DefaultCompactPolicy.buildCompressPrompt(
            content = "hello world",
            targetTokens = 512,
            locale = "zh",
            additionalContext = "extra",
            template = template,
        )
        assertEquals("C=hello world;T=512;L=zh;A=extra", out)
    }

    @Test
    fun `default template retains compress prompt structure`() {
        val out = DefaultCompactPolicy.buildCompressPrompt(
            content = "BODY",
            targetTokens = 100,
            locale = "en",
            additionalContext = "",
            template = DEFAULT_COMPRESS_PROMPT,
        )
        assertTrue(out.contains("BODY"))
        assertTrue(out.contains("100"))
        assertTrue(out.contains("en"))
        assertTrue(out.contains("conversation") || out.contains("summary") || out.contains("Summary"))
    }
}
