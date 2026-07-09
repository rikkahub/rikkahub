package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.hermes.CancelHermesOutcome
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueEvent
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHermesSessionBridgeFactoryTest {
    private data class Harness(
        val gemini: FakeGeminiLiveVoiceClient,
        val diagnostics: VoiceDiagnostics,
        val queueEvents: MutableList<HermesQueueEvent>,
        val factory: VoiceHermesSessionBridgeFactory,
        val suppressionCleared: () -> Int,
    )

    @Test
    fun `unbound sessionId sends ungated, bound sessionId gates`() = runTest {
        val harness = harness()
        val bridge = harness.factory.create(sessionId = BOUND_SESSION_ID)

        assertTrue(bridge.sendQueuedAcknowledgement(callId = "c1", sessionId = UNBOUND_SESSION_ID))
        assertTrue(bridge.sendQueuedAcknowledgement(callId = "c2", sessionId = BOUND_SESSION_ID))

        assertEquals(listOf(null, BOUND_SESSION_ID), harness.gemini.toolResponseSessionIds)
    }

    @Test
    fun `completion follow-up clears suppression, writes queue event, and records diagnostics`() = runTest {
        val harness = harness()
        val bridge = harness.factory.create(sessionId = BOUND_SESSION_ID)

        val sent = bridge.sendCompletionFollowUp(
            callId = "c1",
            prompt = "original prompt",
            answer = "answer body",
            sessionId = BOUND_SESSION_ID,
        )

        assertTrue(sent)
        assertEquals(1, harness.suppressionCleared())
        assertEquals(listOf(BOUND_SESSION_ID to hermesCompletionFollowUpText("original prompt", "answer body")), harness.gemini.textTurns)
        assertEquals(
            HermesQueueEvent(type = "late_text_turn_sent", callId = "c1", jobId = "none", sent = true),
            harness.queueEvents.single(),
        )
        assertEquals("hermes_completion_follow_up_sent", harness.diagnostics.events.value.single().name)
        assertEquals("callId=c1, jobId=none, answerChars=11", harness.diagnostics.events.value.single().detail)
    }

    @Test
    fun `failed terminal follow-up records the failed diagnostic and queue event`() = runTest {
        val harness = harness(textTurnsSucceed = false)
        val bridge = harness.factory.create(sessionId = BOUND_SESSION_ID)

        val sent = bridge.sendTerminalFollowUp(
            callId = "c1",
            prompt = "original prompt",
            status = HermesQueueStatus.Expired,
            reason = "too slow",
            sessionId = BOUND_SESSION_ID,
        )

        assertFalse(sent)
        assertEquals(1, harness.suppressionCleared())
        assertTrue(harness.gemini.textTurns.isEmpty())
        assertEquals(
            HermesQueueEvent(type = "late_terminal_text_turn_sent", callId = "c1", jobId = "none", sent = false),
            harness.queueEvents.single(),
        )
        assertEquals("hermes_terminal_follow_up_failed", harness.diagnostics.events.value.single().name)
        assertEquals("callId=c1, jobId=none, status=expired, reasonChars=8", harness.diagnostics.events.value.single().detail)
    }

    @Test
    fun `still-working update clears suppression, writes queue event, and records diagnostics`() = runTest {
        val harness = harness()
        val bridge = harness.factory.create(sessionId = BOUND_SESSION_ID)

        val sent = bridge.sendStillWorkingUpdate(
            callId = "c1",
            prompt = "original prompt",
            sessionId = UNBOUND_SESSION_ID,
        )

        assertTrue(sent)
        assertEquals(1, harness.suppressionCleared())
        assertEquals(listOf(null to hermesStillWorkingUpdateText("original prompt")), harness.gemini.textTurns)
        assertEquals(
            HermesQueueEvent(type = "still_working_text_turn_sent", callId = "c1", jobId = "none", sent = true),
            harness.queueEvents.single(),
        )
        assertEquals("hermes_still_working_sent", harness.diagnostics.events.value.single().name)
        assertEquals("callId=c1, jobId=none", harness.diagnostics.events.value.single().detail)
    }

    @Test
    fun `cancel response is a cancel_hermes tool response and does not clear suppression`() = runTest {
        val harness = harness()
        val bridge = harness.factory.create(sessionId = BOUND_SESSION_ID)

        assertTrue(
            bridge.sendCancelResponse(
                callId = "c1",
                outcome = CancelHermesOutcome.NothingPending,
                sessionId = BOUND_SESSION_ID,
            )
        )

        assertEquals(listOf(VoiceAgentToolNames.CANCEL_HERMES), harness.gemini.toolResponseNames)
        assertEquals(listOf(BOUND_SESSION_ID), harness.gemini.toolResponseSessionIds)
        assertTrue(harness.queueEvents.isEmpty())
        assertEquals(0, harness.suppressionCleared())
        assertTrue(harness.diagnostics.events.value.isEmpty())
    }

    private fun harness(textTurnsSucceed: Boolean = true): Harness {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            activateOutboundSession(BOUND_SESSION_ID)
            failTextTurns = !textTurnsSucceed
        }
        val diagnostics = VoiceDiagnostics()
        val queueEvents = mutableListOf<HermesQueueEvent>()
        var suppressionCleared = 0
        val factory = VoiceHermesSessionBridgeFactory(
            gemini = gemini,
            diagnostics = diagnostics,
            unboundSessionId = UNBOUND_SESSION_ID,
            writeQueueEvent = { queueEvents += it },
            clearOutputAudioSuppressionForNewTurn = { suppressionCleared += 1 },
        )
        return Harness(
            gemini = gemini,
            diagnostics = diagnostics,
            queueEvents = queueEvents,
            factory = factory,
            suppressionCleared = { suppressionCleared },
        )
    }

    private companion object {
        const val UNBOUND_SESSION_ID = -1L
        const val BOUND_SESSION_ID = 7L
    }
}
