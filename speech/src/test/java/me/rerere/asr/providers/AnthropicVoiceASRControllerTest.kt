package me.rerere.asr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicVoiceASRControllerTest {

    @Test
    fun `toDeepgramLang maps blank and auto to multi`() {
        assertEquals("multi", toDeepgramLang(""))
        assertEquals("multi", toDeepgramLang("   "))
        assertEquals("multi", toDeepgramLang("auto"))
        assertEquals("multi", toDeepgramLang("AUTO"))
    }

    @Test
    fun `toDeepgramLang passes through and normalizes explicit codes`() {
        assertEquals("en", toDeepgramLang("en"))
        assertEquals("zh", toDeepgramLang("ZH"))
        assertEquals("es", toDeepgramLang(" es "))
    }

    @Test
    fun `buildVoiceStreamUrl uses wss path and deepgram params`() {
        val url = buildVoiceStreamUrl("https://api.anthropic.com", "en", 16000)

        assertTrue(url.startsWith("wss://api.anthropic.com/api/ws/speech_to_text/voice_stream?"))
        assertTrue(url.contains("encoding=linear16"))
        assertTrue(url.contains("sample_rate=16000"))
        assertTrue(url.contains("channels=1"))
        assertTrue(url.contains("endpointing_ms=300"))
        assertTrue(url.contains("utterance_end_ms=1000"))
        assertTrue(url.contains("language=en"))
    }

    @Test
    fun `buildVoiceStreamUrl rewrites http scheme and trims trailing slash`() {
        val url = buildVoiceStreamUrl("http://localhost:8080/", "", 24000)

        assertTrue(url.startsWith("ws://localhost:8080/api/ws/speech_to_text/voice_stream?"))
        assertTrue(url.contains("sample_rate=24000"))
        // blank language -> multi
        assertTrue(url.contains("language=multi"))
    }

    @Test
    fun `buildVoiceStreamUrl percent-encodes language so it cannot inject query params`() {
        // A user-editable language value containing '&'/'=' must be encoded, not
        // concatenated raw — otherwise it injects/overrides stream-config params.
        val url = buildVoiceStreamUrl("https://api.anthropic.com", "en&endpointing_ms=0", 16000)

        // The injected key must not appear as a real parameter value of its own.
        assertFalse(url.contains("&endpointing_ms=0&"))
        assertFalse(url.endsWith("&endpointing_ms=0"))
        // The legitimate endpointing param is preserved unaltered.
        assertTrue(url.contains("endpointing_ms=300"))
        // The whole malicious string is carried as the encoded language value.
        assertTrue(url.contains("language=en%26endpointing_ms%3D0"))
    }

    @Test
    fun `parseVoiceFrame maps transcript text`() {
        val frame = parseVoiceFrame("""{"type":"TranscriptText","data":"hello world"}""")
        assertEquals(VoiceFrame.TranscriptText("hello world"), frame)
    }

    @Test
    fun `parseVoiceFrame maps endpoint`() {
        assertEquals(VoiceFrame.TranscriptEndpoint, parseVoiceFrame("""{"type":"TranscriptEndpoint"}"""))
    }

    @Test
    fun `parseVoiceFrame maps transcript error with fallbacks`() {
        assertEquals(
            VoiceFrame.TranscriptError("bad audio"),
            parseVoiceFrame("""{"type":"TranscriptError","description":"bad audio"}""")
        )
        assertEquals(
            VoiceFrame.TranscriptError("E_AUTH"),
            parseVoiceFrame("""{"type":"TranscriptError","error_code":"E_AUTH"}""")
        )
        assertEquals(
            VoiceFrame.TranscriptError("Transcription error"),
            parseVoiceFrame("""{"type":"TranscriptError"}""")
        )
    }

    @Test
    fun `parseVoiceFrame maps generic server error`() {
        assertEquals(
            VoiceFrame.TranscriptError("boom"),
            parseVoiceFrame("""{"type":"error","message":"boom"}""")
        )
    }

    @Test
    fun `parseVoiceFrame ignores unknown and rejects invalid json`() {
        assertEquals(VoiceFrame.Ignored, parseVoiceFrame("""{"type":"Metadata"}"""))
        assertEquals(VoiceFrame.Invalid, parseVoiceFrame("not json"))
    }

    @Test
    fun `accumulator commits interim on endpoint and keeps running interim`() {
        val acc = VoiceTranscriptAccumulator()

        acc.onInterim("hello")
        assertEquals("hello", acc.transcript())

        acc.onInterim("hello world")
        assertEquals("hello world", acc.transcript())

        acc.onEndpoint()
        // committed segment retained, interim cleared
        assertEquals("hello world", acc.transcript())

        acc.onInterim("second")
        // committed + new interim joined
        assertEquals("hello world second", acc.transcript())

        acc.onEndpoint()
        assertEquals("hello world second", acc.transcript())
    }

    @Test
    fun `accumulator ignores blank endpoints and resets`() {
        val acc = VoiceTranscriptAccumulator()

        acc.onInterim("   ")
        acc.onEndpoint()
        assertEquals("", acc.transcript())

        acc.onInterim("kept")
        acc.onEndpoint()
        acc.reset()
        assertEquals("", acc.transcript())
    }

    @Test
    fun `closeCodeError maps fatal codes only`() {
        assertEquals("Voice stream rejected (policy violation)", closeCodeError(1008))
        assertEquals("Voice stream authentication failed", closeCodeError(4401))
        assertEquals("Voice stream access forbidden", closeCodeError(4403))
        assertNull(closeCodeError(1000))
        assertNull(closeCodeError(1006))
    }
}
