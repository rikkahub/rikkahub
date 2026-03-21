package me.rerere.tts.provider.providers

import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun emits_single_terminal_chunk_on_done_and_closed() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val audioData = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val first = processor.process(SseEvent.Event(id = null, type = null, data = audioData))
        val done = processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
        val terminal = processor.process(SseEvent.Closed)

        assertNotNull(first)
        assertEquals(AudioFormat.PCM, first?.format)
        assertFalse(first?.isLast ?: true)
        assertNull(done)
        assertNotNull(terminal)
        assertTrue(terminal?.isLast ?: false)
    }

    @Test
    fun throws_when_stream_closed_without_audio() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")

        var thrown: Throwable? = null
        try {
            processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
            processor.process(SseEvent.Closed)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }
}
