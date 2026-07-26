package me.rerere.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
    }

    @Test
    fun testKimiDialectMatchers() {
        // K3 系列（含 Kimi Code 网关的裸 id k3 / k3-256k）
        assertTrue(ModelRegistry.KIMI_K3_FAMILY.match("kimi-k3"))
        assertTrue(ModelRegistry.KIMI_K3_FAMILY.match("Kimi-K3.5"))
        assertTrue(ModelRegistry.KIMI_K3_FAMILY.match("k3"))
        assertTrue(ModelRegistry.KIMI_K3_FAMILY.match("k3-256k"))
        assertFalse(ModelRegistry.KIMI_K3_ALIAS.match("kimi-k3"))
        assertFalse(ModelRegistry.KIMI_K3_FAMILY.match("kimi-k2.5"))

        // K2.7 Code 系列：kimi-k2.7-code（含 -highspeed）与网关的 kimi-for-coding（含 -highspeed）
        assertTrue(ModelRegistry.KIMI_K2_7.match("kimi-k2.7-code"))
        assertTrue(ModelRegistry.KIMI_K2_7.match("kimi-k2.7-code-highspeed"))
        assertTrue(ModelRegistry.KIMI_K2_7.match("kimi-for-coding"))
        assertTrue(ModelRegistry.KIMI_K2_7.match("kimi-for-coding-highspeed"))
        assertFalse(ModelRegistry.KIMI_K2_7.match("kimi-k2.5"))
        assertFalse(ModelRegistry.KIMI_K2_7.match("kimi-k2-0905-preview"))

        // K2.5 及以上（采样参数固定的模型集合）
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2.5"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2.6"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2.7-code"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-for-coding"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k3"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("k3"))
        assertTrue(ModelRegistry.KIMI_K2_5_PLUS.match("k3-256k"))
        assertFalse(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2"))
        assertFalse(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2-0905-preview"))
        assertFalse(ModelRegistry.KIMI_K2_5_PLUS.match("kimi-k2-thinking"))

        // 网关模型 id 的能力注册
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("k3-256k")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("kimi-for-coding")
        )
    }
}
