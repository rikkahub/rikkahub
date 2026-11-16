package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingDeepSeekTest {
    @Test
    fun deepseek_defaults_are_expected() {
        val setting = TTSProviderSetting.DeepSeek()

        assertEquals("DeepSeek TTS", setting.name)
        assertEquals("https://api.deepseek.com", setting.baseUrl)
        assertEquals("stella", setting.voiceId)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun deepseek_is_registered_and_round_trips() {
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.DeepSeek::class))

        val original: TTSProviderSetting = TTSProviderSetting.DeepSeek(apiKey = "sk-test", voiceId = "tide")
        val json = Json.encodeToString(TTSProviderSetting.serializer(), original)
        assertTrue(json.contains("\"type\":\"deepseek\""))
        assertEquals(original, Json.decodeFromString(TTSProviderSetting.serializer(), json))
    }
}
