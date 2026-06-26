package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.Job
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReconnectControllerTest {
    @Test
    fun `eligible failure creates first reconnect plan`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        controller.markEligible(sessionId = 7L)

        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(decision is VoiceReconnectDecision.Schedule)
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertEquals(1, schedule.plan.attempt)
        assertEquals(10L, schedule.plan.delayMs)
        assertEquals(VoiceSessionStopReason.WebSocketFailure, schedule.plan.reason)
        assertFalse(controller.hasPendingReconnect())
    }

    @Test
    fun `duplicate failure for planned session is coalesced without another retry attempt`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        controller.markEligible(sessionId = 7L)

        val firstDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop one"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        val secondDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop two"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(firstDecision is VoiceReconnectDecision.Schedule)
        assertSame(VoiceReconnectDecision.AlreadyPlanned, secondDecision)
        assertEquals(1, controller.retryAttempt())
        assertFalse(controller.hasPendingReconnect())
    }

    @Test
    fun `ineligible stale failure is ignored`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        controller.markEligible(sessionId = 7L)

        val decision = controller.planReconnect(
            failedSessionId = 6L,
            event = GeminiLiveEvent.WebSocketFailure("stale"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertSame(VoiceReconnectDecision.Ignore, decision)
    }

    @Test
    fun `exhausted retry budget clears reconnect state`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 0, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        controller.markEligible(sessionId = 7L)

        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(decision is VoiceReconnectDecision.Exhausted)
        val exhausted = decision as VoiceReconnectDecision.Exhausted
        assertEquals(VoiceSessionStopReason.WebSocketFailure, exhausted.reason)
        assertEquals(0, exhausted.attempts)
        assertFalse(controller.hasPendingReconnect())
    }

    @Test
    fun `manual cancellation returns current scheduled job and clears state`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()

        controller.markEligible(sessionId = 7L)
        controller.setScheduled(job = job)
        val cancellation = controller.cancel()

        assertSame(job, cancellation.job)
        assertTrue(cancellation.hadAutomaticReconnect)
        assertFalse(controller.hasPendingReconnect())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling mere reconnect eligibility does not report automatic reconnect`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )

        controller.markEligible(sessionId = 7L)
        val cancellation = controller.cancel()

        assertNull(cancellation.job)
        assertFalse(cancellation.hadAutomaticReconnect)
    }

    @Test
    fun `activation without pending reconnect is not pending automatic reconnect`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))

        assertFalse(controller.hasPendingReconnect())
    }

    @Test
    fun `activation failure is deferred until activation cleanup point`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"),
            reason = VoiceSessionStopReason.WebSocketClosed,
        )

        assertSame(VoiceReconnectDecision.DeferredForActivation, decision)
        val pending = controller.consumePendingActivation(sessionId = 7L)
        assertEquals(1, pending?.attempt)
        assertNull(controller.consumePendingActivation(sessionId = 7L))
    }

    @Test
    fun `consumed activation deferred reconnect preserves completed attempt`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"),
            reason = VoiceSessionStopReason.WebSocketClosed,
        )

        assertSame(VoiceReconnectDecision.DeferredForActivation, decision)
        val pending = controller.consumePendingActivation(sessionId = 7L)
        assertEquals(1, pending?.attempt)
        controller.setScheduled(job = job)
        assertTrue(controller.beginAttempt(job = job, newSessionId = 8L))

        assertEquals(1, controller.completeAttempt(job = job))
    }

    @Test
    fun `finished activation deferred reconnect failure advances retry attempt`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"),
            reason = VoiceSessionStopReason.WebSocketClosed,
        )

        assertSame(VoiceReconnectDecision.DeferredForActivation, decision)
        val pending = controller.finishActivation(sessionId = 7L)
        assertEquals(1, pending?.attempt)
        controller.setScheduled(job = job)
        assertTrue(controller.beginAttempt(job = job, newSessionId = 8L))
        val retryDecision = controller.planReconnect(
            failedSessionId = 8L,
            event = GeminiLiveEvent.WebSocketFailure("retry failed"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(retryDecision is VoiceReconnectDecision.Schedule)
        retryDecision as VoiceReconnectDecision.Schedule
        assertEquals(2, retryDecision.plan.attempt)
    }

    @Test
    fun `successful activation keeps session eligible for later reconnect`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))
        assertNull(controller.finishActivation(sessionId = 7L))
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop after connected"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(decision is VoiceReconnectDecision.Schedule)
    }

    @Test
    fun `automatic reconnect job remains current through activation`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()

        controller.markEligible(sessionId = 7L)
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(decision is VoiceReconnectDecision.Schedule)
        controller.setScheduled(job)
        assertTrue(controller.beginAttempt(job = job, newSessionId = 8L))
        controller.markEligible(sessionId = 8L)
        assertTrue(controller.reserveActivation(sessionId = 8L))

        assertTrue(controller.isCurrentJob(job))
    }
}
