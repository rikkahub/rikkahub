package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VoiceAgentFakesTest {
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
}
