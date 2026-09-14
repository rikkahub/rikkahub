package me.rerere.tts.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.SseEvent
import me.rerere.tts.provider.TTSProviderSetting
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VolcengineTTSProviderTest {
    @Test
    fun usesApiKeyAndEscapesText() {
        val setting = TTSProviderSetting.Volcengine(
            apiKey = " key ",
            baseUrl = "https://example.com/", speechRate = 200,
        )
        val text = "你好，\"世界\"！\n第二行"
        val request = buildVolcengineTTSRequest(setting, text)
        assertEquals("https://example.com/api/v3/tts/unidirectional/sse", request.url.toString())
        assertEquals("key", request.header("X-Api-Key"))
        assertNull(request.header("X-Api-App-Id"))
        assertNull(request.header("X-Api-Access-Key"))
        assertEquals("seed-tts-2.0", request.header("X-Api-Resource-Id"))
        assertNotEquals(request.header("X-Api-Request-Id"), buildVolcengineTTSRequest(setting, text).header("X-Api-Request-Id"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val params = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("req_params").jsonObject
        assertEquals(text, params.getValue("text").jsonPrimitive.content)
        assertEquals("100", params.getValue("audio_params").jsonObject.getValue("speech_rate").jsonPrimitive.content)
    }

    @Test
    fun apiKeyAndSettingsSurviveSerialization() {
        val original: TTSProviderSetting = TTSProviderSetting.Volcengine(apiKey = "key")
        val restored = Json.decodeFromString<TTSProviderSetting>(Json.encodeToString(original))
        assertEquals(original, restored)
        val request = buildVolcengineTTSRequest(restored as TTSProviderSetting.Volcengine, "hello")
        assertEquals("key", request.header("X-Api-Key"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankApiKey() {
        buildVolcengineTTSRequest(TTSProviderSetting.Volcengine(apiKey = "  "), "hello")
    }

    @Test
    fun decodesAudioAndPreservesAudioOnTerminalFrame() {
        val processor = VolcengineTTSStreamProcessor()
        assertNull(processor.process(SseEvent.Open))
        assertNull(processor.process(event("""{"code":0,"sentence":"hello"}""")))
        val bytes = byteArrayOf(0, 1, 2, -1)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val chunk = processor.process(event("""{"code":0,"data":"$encoded"}"""))!!
        assertArrayEquals(bytes, chunk.data)
        assertFalse(chunk.isLast)
        val terminal = processor.process(event("""{"code":20000000,"data":"$encoded","usage":{"text_words":5}}"""))!!
        assertArrayEquals(bytes, terminal.data)
        assertTrue(terminal.isLast)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsApiFailureEvenWithHttpSuccess() {
        VolcengineTTSStreamProcessor().process(event("""{"code":55000000,"message":"resource mismatch"}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEmptySynthesis() {
        VolcengineTTSStreamProcessor().process(event("""{"code":20000000}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsTruncatedAudio() {
        val processor = VolcengineTTSStreamProcessor()
        processor.process(event("""{"code":0,"data":"AQID"}"""))
        processor.process(SseEvent.Closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidBase64() {
        VolcengineTTSStreamProcessor().process(event("""{"code":0,"data":"%%%"}"""))
    }

    private fun event(data: String) = SseEvent.Event(null, null, data)
}
