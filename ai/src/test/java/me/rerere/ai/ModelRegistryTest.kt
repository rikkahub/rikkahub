package me.rerere.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }

    // region Reasoning Level Tests

    @Test
    fun testSupportedReasoningLevels_oSeries() {
        val expected = listOf(
            ReasoningLevel.OFF, ReasoningLevel.AUTO,
            ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH
        )
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("o1"))
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("o3-mini"))
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("o4-mini"))
    }

    @Test
    fun testSupportedReasoningLevels_gpt5() {
        val expected = listOf(
            ReasoningLevel.OFF, ReasoningLevel.AUTO,
            ReasoningLevel.MINIMAL, ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM, ReasoningLevel.HIGH
        )
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("gpt-5"))
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("gpt-5-mini"))
    }

    @Test
    fun testSupportedReasoningLevels_gpt5Pro() {
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.HIGH),
            ModelRegistry.supportedReasoningLevels("gpt-5-pro")
        )
    }

    @Test
    fun testSupportedReasoningLevels_gpt5Codex() {
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            ModelRegistry.supportedReasoningLevels("gpt-5-codex")
        )
    }

    @Test
    fun testSupportedReasoningLevels_gpt51() {
        val expected = listOf(
            ReasoningLevel.OFF, ReasoningLevel.AUTO,
            ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH, ReasoningLevel.XHIGH
        )
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("gpt-5.1"))
        assertEquals(expected, ModelRegistry.supportedReasoningLevels("gpt-5.2"))
    }

    @Test
    fun testSupportedReasoningLevels_gpt51CodexMax() {
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            ModelRegistry.supportedReasoningLevels("gpt-5.1-codex-max")
        )
    }

    @Test
    fun testSupportedReasoningLevels_default() {
        assertEquals(
            ReasoningLevel.entries.toList(),
            ModelRegistry.supportedReasoningLevels("unknown-model")
        )
    }

    @Test
    fun testReasoningEffortOrNull() {
        assertNull(ModelRegistry.reasoningEffortOrNull("o4-mini", ReasoningLevel.AUTO))
        assertEquals("low", ModelRegistry.reasoningEffortOrNull("o4-mini", ReasoningLevel.OFF))
        assertEquals("low", ModelRegistry.reasoningEffortOrNull("gpt-5", ReasoningLevel.LOW))
        assertEquals("medium", ModelRegistry.reasoningEffortOrNull("gpt-5", ReasoningLevel.MEDIUM))
        assertEquals("high", ModelRegistry.reasoningEffortOrNull("gpt-5", ReasoningLevel.HIGH))
        assertEquals("minimal", ModelRegistry.reasoningEffortOrNull("gpt-5", ReasoningLevel.MINIMAL))
        assertEquals("xhigh", ModelRegistry.reasoningEffortOrNull("gpt-5.2", ReasoningLevel.XHIGH))
    }

    // endregion
}
