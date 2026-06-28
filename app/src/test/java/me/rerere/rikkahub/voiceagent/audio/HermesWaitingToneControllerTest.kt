package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.FakeVoiceAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HermesWaitingToneControllerTest {
    @Test
    fun `waiting plays local cue after grace and repeats`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 20L,
            repeatIntervalMs = 30L,
        )

        controller.setWaiting(true)

        withTimeout(500) {
            while (audio.playedLocalCuePcm16.size < 2) {
                delay(10)
            }
        }

        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertEquals(2, audio.playedLocalCuePcm16.size)
        assertTrue(audio.playedLocalCuePcm16.all { it.isNotBlank() })
        Base64.getDecoder().decode(audio.playedLocalCuePcm16.first())

        controller.stop()
    }

    @Test
    fun `stop before grace keeps cue silent`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 50L,
            repeatIntervalMs = 30L,
        )

        controller.setWaiting(true)
        delay(10)
        controller.stop()
        delay(80)

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16)
    }

    @Test
    fun `set waiting true twice starts only one repeat loop`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 20L,
            repeatIntervalMs = 200L,
        )

        controller.setWaiting(true)
        controller.setWaiting(true)

        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        delay(80)

        assertEquals(1, audio.playedLocalCuePcm16.size)

        controller.stop()
    }

    @Test
    fun `local cue playback failure records diagnostic and loop continues`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val audio = FakeVoiceAudioEngine().apply {
            failLocalCuePlayback = true
        }
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 20L,
            repeatIntervalMs = 30L,
            recordDiagnostic = { name, detail -> diagnostics += name to detail },
        )

        controller.setWaiting(true)

        withTimeout(500) {
            while (diagnostics.count { it.first == "hermes_waiting_tone_failed" } < 2) {
                delay(10)
            }
        }

        assertTrue(diagnostics.all { it.second.contains("playback rejected") })

        controller.stop()
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
