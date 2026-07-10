package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VoiceAgentFakesTest {
    @Test
    fun `outbound recording observations are detached snapshots`() {
        val gemini = FakeGeminiLiveVoiceClient()
        gemini.activateOutboundSession(1L)
        assertTrue(gemini.sendAudio("audio-1", 1L))
        assertTrue(gemini.sendAudioStreamEnd(1L))
        assertTrue(gemini.sendTextTurn("text-1", 1L))
        val observedAudio = gemini.audioMessages
        val observedAudioEnds = gemini.audioStreamEndSessionIds
        val observedTextTurns = gemini.textTurns

        assertTrue(gemini.sendAudio("audio-2", 1L))
        assertTrue(gemini.sendAudioStreamEnd(1L))
        assertTrue(gemini.sendTextTurn("text-2", 1L))

        assertEquals(listOf("audio-1"), observedAudio)
        assertEquals(listOf(1L), observedAudioEnds)
        assertEquals(listOf(1L to "text-1"), observedTextTurns)
        assertEquals(listOf("audio-1", "audio-2"), gemini.audioMessages)
        assertEquals(listOf(1L, 1L), gemini.audioStreamEndSessionIds)
        assertEquals(listOf(1L to "text-1", 1L to "text-2"), gemini.textTurns)
    }

    @Test
    fun `tool response observation is a stable snapshot while another response is recorded`() {
        val gemini = FakeGeminiLiveVoiceClient()
        assertTrue(gemini.sendToolResponse("call-1", "answer-1", null, "tool-1"))
        val observedResponses = gemini.toolResponses
        val recorded = CountDownLatch(1)

        val sender = thread {
            gemini.sendToolResponse("call-2", "answer-2", null, "tool-2")
            recorded.countDown()
        }

        assertTrue(recorded.await(1, TimeUnit.SECONDS))
        sender.join()
        assertEquals(listOf("call-1" to "answer-1"), observedResponses)
        assertEquals(listOf("call-1" to "answer-1", "call-2" to "answer-2"), gemini.toolResponses)
        assertEquals(listOf("tool-1", "tool-2"), gemini.toolResponseNames)
        assertEquals(listOf(null, null), gemini.toolResponseSessionIds)
    }

    @Test
    fun `close publishes session invalidation before invoking callback`() {
        val gemini = FakeGeminiLiveVoiceClient()
        gemini.activateOutboundSession(1L)
        val callbackSendResult = AtomicReference<Boolean>()
        gemini.onClose = {
            callbackSendResult.set(gemini.sendAudio("stale", 1L))
        }

        gemini.close()

        assertEquals(false, callbackSendResult.get())
        assertTrue(gemini.audioMessages.isEmpty())
    }

    @Test
    fun `activation is visible to callback work without holding the outbound lock`() {
        val gemini = FakeGeminiLiveVoiceClient()
        val callbackCompleted = AtomicBoolean(false)
        val callbackSendResult = AtomicReference<Boolean>()
        gemini.activateOutboundSessionEvent = GeminiLiveEvent.InputTranscript("activated")
        gemini.eventHandlers += {
            val sender = thread {
                callbackSendResult.set(gemini.sendAudio("audio", 7L))
                callbackCompleted.set(true)
            }
            sender.join(1_000)
        }

        gemini.activateOutboundSession(7L)

        assertTrue("callback work was blocked by the outbound lock", callbackCompleted.get())
        assertEquals(true, callbackSendResult.get())
        assertEquals(listOf("audio"), gemini.audioMessages)
    }

    @Test
    fun `tool response callback can invalidate the session without waiting for the outbound lock`() {
        val gemini = FakeGeminiLiveVoiceClient()
        gemini.activateOutboundSession(9L)
        val callbackCompleted = AtomicBoolean(false)
        gemini.onBeforeToolResponseRecorded = {
            val invalidator = thread {
                gemini.invalidateOutboundSession()
                callbackCompleted.set(true)
            }
            invalidator.join(1_000)
        }

        val sent = gemini.sendToolResponse("call", "answer", 9L, "tool")

        assertTrue("callback work was blocked by the outbound lock", callbackCompleted.get())
        assertEquals(false, sent)
        assertTrue(gemini.toolResponses.isEmpty())
    }
}
