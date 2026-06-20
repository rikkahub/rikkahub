package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingStepTest {
    @Test
    fun step_defaults_are_expected() {
        val setting = TTSProviderSetting.Step()

        assertEquals("Step TTS", setting.name)
        assertEquals("https://api.stepfun.com", setting.baseUrl)
        assertEquals("step-tts-mini", setting.model)
        assertEquals("elegantgentle-female", setting.voice)
        assertEquals("mp3", setting.responseFormat)
        assertEquals(1.0f, setting.speed)
        assertEquals(1.0f, setting.volume)
        assertEquals(24000, setting.sampleRate)
        assertEquals("", setting.instruction)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun step_is_registered_in_provider_types() {
        // 没有注册到 Types 列表的话, 设置页的下拉菜单里就不会出现 Step 选项, 也没法新建
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.Step::class))
    }

    @Test
    fun step_copyProvider_preserves_id_and_name() {
        val original = TTSProviderSetting.Step(
            apiKey = "sk-test",
            model = "stepaudio-2.5-tts",
            voice = "cixingnansheng",
        )
        val copied = original.copyProvider(
            id = original.id,
            name = "My Step"
        ) as TTSProviderSetting.Step

        assertEquals(original.id, copied.id)
        assertEquals("My Step", copied.name)
        // 其余字段必须保持不变
        assertEquals("sk-test", copied.apiKey)
        assertEquals("stepaudio-2.5-tts", copied.model)
        assertEquals("cixingnansheng", copied.voice)
    }
}
