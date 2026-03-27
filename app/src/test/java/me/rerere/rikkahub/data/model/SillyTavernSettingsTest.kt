package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SillyTavernSettingsTest {
    @Test
    fun `active st preset sampling should override assistant params when enabled`() {
        val preset = SillyTavernPreset(
            sampling = SillyTavernPresetSampling(
                temperature = 0.72f,
                topP = null,
                maxTokens = 4096,
                frequencyPenalty = 0.4f,
                presencePenalty = null,
                minP = 0.12f,
                topK = 24,
                topA = 0.25f,
                repetitionPenalty = 1.08f,
                seed = 42L,
                stopSequences = emptyList(),
                openAIReasoningEffort = "",
                openAIVerbosity = "low",
            )
        )
        val settings = Settings(
            stPresetEnabled = true,
            stPresets = listOf(preset),
            selectedStPresetId = preset.id,
        )
        val assistant = Assistant(
            temperature = 1.1f,
            topP = 0.95f,
            maxTokens = 1024,
            frequencyPenalty = 0.9f,
            presencePenalty = 0.7f,
            minP = 0.3f,
            topK = 64,
            topA = 0.8f,
            repetitionPenalty = 1.3f,
            seed = 999L,
            stopSequences = listOf("User:"),
            openAIReasoningEffort = "high",
            openAIVerbosity = "high",
        )

        val applied = settings.applyActiveStPresetSampling(assistant)

        assertEquals(0.72f, applied.temperature!!, 0f)
        assertEquals(null, applied.topP)
        assertEquals(4096, applied.maxTokens)
        assertEquals(0.4f, applied.frequencyPenalty!!, 0f)
        assertEquals(null, applied.presencePenalty)
        assertEquals(0.12f, applied.minP!!, 0f)
        assertEquals(24, applied.topK)
        assertEquals(0.25f, applied.topA!!, 0f)
        assertEquals(1.08f, applied.repetitionPenalty!!, 0f)
        assertEquals(42L, applied.seed)
        assertEquals(emptyList<String>(), applied.stopSequences)
        assertEquals("", applied.openAIReasoningEffort)
        assertEquals("low", applied.openAIVerbosity)
    }

    @Test
    fun `active st preset sampling should keep assistant params when disabled`() {
        val preset = SillyTavernPreset(
            sampling = SillyTavernPresetSampling(
                temperature = 0.5f,
                topP = 0.8f,
            )
        )
        val settings = Settings(
            stPresetEnabled = false,
            stPresets = listOf(preset),
            selectedStPresetId = preset.id,
        )
        val assistant = Assistant(
            temperature = 1.1f,
            topP = 0.95f,
        )

        val applied = settings.applyActiveStPresetSampling(assistant)

        assertSame(assistant, applied)
    }

    @Test
    fun `active st preset sampling should ignore empty legacy preset sampling`() {
        val preset = SillyTavernPreset(
            sampling = SillyTavernPresetSampling(),
        )
        val settings = Settings(
            stPresetEnabled = true,
            stPresets = listOf(preset),
            selectedStPresetId = preset.id,
        )
        val assistant = Assistant(
            temperature = 1.1f,
            topP = 0.95f,
            openAIVerbosity = "high",
        )

        val applied = settings.applyActiveStPresetSampling(assistant)

        assertSame(assistant, applied)
    }
}
