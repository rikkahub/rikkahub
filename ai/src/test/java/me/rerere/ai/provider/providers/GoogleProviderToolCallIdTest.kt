package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the function-call id policy used when replaying tool history through the Gemini codec.
 *
 * Claude / GPT-OSS via the managed cloudcode backend require explicit ids on functionCall and a
 * matching id on the functionResponse (the backend pairs them by id, not by order) — observed as
 * "messages.N.content.0.tool_use.id: Field required". Native Gemini matches by order and must not
 * receive ids. The id must be deterministic so the call and its response carry the same value.
 */
class GoogleProviderToolCallIdTest {

    @Test
    fun `claude and gpt-oss require ids, gemini does not`() {
        assertTrue(geminiRequiresToolCallId("claude-sonnet-4-6"))
        assertTrue(geminiRequiresToolCallId("claude-opus-4-6-thinking"))
        assertTrue(geminiRequiresToolCallId("gpt-oss-120b-medium"))
        assertFalse(geminiRequiresToolCallId("gemini-pro-agent"))
        assertFalse(geminiRequiresToolCallId("gemini-3.5-flash-low"))
    }

    @Test
    fun `normalize is deterministic so call and response ids match`() {
        val id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        assertEquals(normalizeGeminiToolCallId(id), normalizeGeminiToolCallId(id))
    }

    @Test
    fun `a uuid passes through unchanged (already valid)`() {
        val id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        assertEquals(id, normalizeGeminiToolCallId(id))
    }

    @Test
    fun `illegal characters are replaced and length is capped at 64`() {
        val normalized = normalizeGeminiToolCallId("call/with spaces:and.dots*" + "x".repeat(80))
        assertTrue(normalized.length <= 64)
        assertTrue(normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    @Test
    fun `empty id yields a non-empty deterministic fallback`() {
        assertTrue(normalizeGeminiToolCallId("").isNotEmpty())
        assertEquals(normalizeGeminiToolCallId(""), normalizeGeminiToolCallId(""))
    }
}
