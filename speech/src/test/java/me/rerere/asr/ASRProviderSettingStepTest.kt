package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingStepTest {
    @Test
    fun step_defaults_are_expected() {
        val setting = ASRProviderSetting.Step()

        assertEquals("Step ASR", setting.name)
        assertEquals("https://api.stepfun.com", setting.baseUrl)
        assertEquals("stepaudio-2.5-asr", setting.model)
        assertEquals("auto", setting.language)
        assertEquals(16000, setting.sampleRate)
        assertEquals(30, setting.segmentDurationSec)
        assertEquals(true, setting.enableItn)
        assertEquals(false, setting.enableTimestamp)
        assertTrue(setting.hotwords.isEmpty())
        assertEquals("", setting.apiKey)
    }

    @Test
    fun step_is_registered_in_provider_types() {
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.Step::class))
    }

    @Test
    fun step_copy_provider_preserves_extra_fields() {
        val original = ASRProviderSetting.Step(
            apiKey = "sk-test",
            baseUrl = "https://api.stepfun.ai",
            model = "stepaudio-2-asr-pro",
            language = "zh",
            sampleRate = 8000,
            segmentDurationSec = 60,
            enableItn = false,
            enableTimestamp = true,
            hotwords = listOf("热词1", "热词2"),
        )
        val copied = original.copyProvider(id = original.id, name = "renamed")

        assertTrue(copied is ASRProviderSetting.Step)
        val step = copied as ASRProviderSetting.Step
        assertEquals("renamed", step.name)
        assertEquals("sk-test", step.apiKey)
        assertEquals("https://api.stepfun.ai", step.baseUrl)
        assertEquals("stepaudio-2-asr-pro", step.model)
        assertEquals("zh", step.language)
        assertEquals(8000, step.sampleRate)
        assertEquals(60, step.segmentDurationSec)
        assertEquals(false, step.enableItn)
        assertEquals(true, step.enableTimestamp)
        assertEquals(listOf("热词1", "热词2"), step.hotwords)
    }
}
