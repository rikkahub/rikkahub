package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TTSProviderSettingFishAudioTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testFishAudioSerialization() {
        val setting = TTSProviderSetting.FishAudio(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Test Fish Audio",
            apiKey = "test-key",
            baseUrl = "https://api.fish.audio",
            model = "s2.1-pro",
            referenceId = "test-voice-id",
            temperature = 0.7f,
            speed = 1.0f,
        )

        val encoded = json.encodeToString(TTSProviderSetting.serializer(), setting)
        assertTrue(encoded.contains("\"fish-audio\""))
        assertTrue(encoded.contains("\"test-key\""))
        assertTrue(encoded.contains("\"s2.1-pro\""))

        val decoded = json.decodeFromString(
            TTSProviderSetting.serializer(),
            encoded
        ) as TTSProviderSetting.FishAudio

        assertEquals(setting.id, decoded.id)
        assertEquals(setting.apiKey, decoded.apiKey)
        assertEquals(setting.model, decoded.model)
        assertEquals(setting.referenceId, decoded.referenceId)
        assertEquals(setting.temperature, decoded.temperature, 0.01f)
        assertEquals(setting.speed, decoded.speed, 0.01f)
    }
}
