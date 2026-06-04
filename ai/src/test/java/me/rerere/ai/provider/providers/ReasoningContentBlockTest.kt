package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the OpenAI -> Claude "thinking.signature: Field required" 400.
 *
 * Anthropic rejects any `thinking` block replayed without its cryptographic signature.
 * Cross-provider reasoning (OpenAI/DeepSeek/think-tags) has no Anthropic signature, so the
 * Claude request serializer must DROP it; native-Claude reasoning (signed) is preserved.
 */
class ReasoningContentBlockTest {

    private fun text(p: Any?) = (p as JsonPrimitive).content

    @Test
    fun `signed reasoning is kept as a thinking block`() {
        val md = buildJsonObject { put("signature", "EpgCCmMIDhgC-fake-sig") }
        val block = reasoningContentBlock("let me think", md)
        requireNotNull(block)
        assertEquals("thinking", text(block["type"]))
        assertEquals("let me think", text(block["thinking"]))
        assertEquals("EpgCCmMIDhgC-fake-sig", text(block["signature"]))
    }

    @Test
    fun `unsigned cross-provider reasoning is dropped`() {
        // OpenAI Responses / DeepSeek reasoning carries no Anthropic signature.
        val md = buildJsonObject { put("provider", "openai") }
        assertNull(reasoningContentBlock("openai reasoning summary", md))
    }

    @Test
    fun `null metadata is dropped`() {
        assertNull(reasoningContentBlock("reasoning with no metadata", null))
    }

    @Test
    fun `explicit JsonNull signature is dropped`() {
        val md = buildJsonObject { put("signature", JsonNull) }
        assertNull(reasoningContentBlock("x", md))
    }

    @Test
    fun `blank or empty signature is dropped`() {
        // An empty/blank signature would only move the 400 from "field required" to
        // "invalid signature" — treat it as no signature.
        assertNull(reasoningContentBlock("x", buildJsonObject { put("signature", "") }))
        assertNull(reasoningContentBlock("x", buildJsonObject { put("signature", "   ") }))
    }

    @Test
    fun `non-string signature is dropped`() {
        val objSig = buildJsonObject { put("signature", buildJsonObject { put("nested", "v") }) }
        assertNull(reasoningContentBlock("x", objSig))
    }
}
