package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ASRProviderSettingVolcengineTest {
    @Test fun `defaults select bidirectional API with server VAD`() {
        val provider = ASRProviderSetting.Volcengine()
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", provider.websocketUrl)
        assertEquals("volc.seedasr.sauc.duration", provider.resourceId)
        assertEquals(800, provider.silenceDurationMs)
        assertTrue(provider.supportsServerVadVoiceMode)
    }

    @Test fun `legacy persisted official endpoint remains unchanged`() {
        val old = Json.decodeFromString<ASRProviderSetting>("""{"type":"volcengine","apiKey":"test-key","websocketUrl":"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel","resourceId":"volc.bigasr.sauc.duration"}""") as ASRProviderSetting.Volcengine
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel", old.websocketUrl)
        assertEquals("test-key", old.apiKey)
        assertEquals("volc.bigasr.sauc.duration", old.resourceId)
        assertEquals(800, old.silenceDurationMs)
    }

    @Test fun `custom endpoints remain unchanged and copy preserves silence setting`() {
        val provider = ASRProviderSetting.Volcengine(websocketUrl = "wss://proxy.example/asr", silenceDurationMs = 1100)
        val copy = provider.copyProvider(name = "renamed") as ASRProviderSetting.Volcengine
        assertEquals(1100, copy.silenceDurationMs)
        assertEquals(provider.websocketUrl, copy.websocketUrl)
    }
}
