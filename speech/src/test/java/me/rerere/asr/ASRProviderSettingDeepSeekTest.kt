package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingDeepSeekTest {
    @Test
    fun deepseek_defaults_are_expected() {
        val setting = ASRProviderSetting.DeepSeek()

        assertEquals("DeepSeek ASR", setting.name)
        assertEquals("https://api.deepseek.com", setting.baseUrl)
        assertEquals(16000, setting.sampleRate)
        assertEquals(30, setting.segmentDurationSec)
        assertEquals(false, setting.supportsServerVadVoiceMode)
    }

    @Test
    fun deepseek_is_registered_and_round_trips() {
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.DeepSeek::class))

        val original: ASRProviderSetting = ASRProviderSetting.DeepSeek(apiKey = "sk-test", sampleRate = 24000)
        val json = Json.encodeToString(ASRProviderSetting.serializer(), original)
        assertTrue(json.contains("\"type\":\"deepseek\""))
        assertEquals(original, Json.decodeFromString(ASRProviderSetting.serializer(), json))
    }

    @Test
    fun pcm16ToWav_writes_riff_header() {
        val pcm = ByteArray(10) { it.toByte() }
        val wav = pcm16ToWav(pcm, sampleRate = 16000)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        // sample rate little-endian at offset 24
        assertEquals(16000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}
