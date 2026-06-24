package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingMiMoTest {
    @Test
    fun mimo_defaults_are_expected() {
        val setting = ASRProviderSetting.MiMo()

        assertEquals("MiMo ASR", setting.name)
        assertEquals("https://api.xiaomimimo.com/v1", setting.baseUrl)
        assertEquals("mimo-v2.5-asr", setting.model)
        assertEquals("auto", setting.language)
        assertEquals(16000, setting.sampleRate)
        assertEquals(30, setting.segmentDurationSec)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun mimo_is_registered_in_provider_types() {
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.MiMo::class))
    }

    @Test
    fun mimo_copy_provider_preserves_extra_fields() {
        val original = ASRProviderSetting.MiMo(
            apiKey = "sk-test",
            baseUrl = "https://example.com/v1",
            model = "mimo-v2.5-asr",
            language = "zh",
            sampleRate = 8000,
            segmentDurationSec = 60,
        )
        val copied = original.copyProvider(id = original.id, name = "renamed")

        assertTrue(copied is ASRProviderSetting.MiMo)
        val mimo = copied as ASRProviderSetting.MiMo
        assertEquals("renamed", mimo.name)
        assertEquals("sk-test", mimo.apiKey)
        assertEquals("https://example.com/v1", mimo.baseUrl)
        assertEquals("mimo-v2.5-asr", mimo.model)
        assertEquals("zh", mimo.language)
        assertEquals(8000, mimo.sampleRate)
        assertEquals(60, mimo.segmentDurationSec)
    }
}
