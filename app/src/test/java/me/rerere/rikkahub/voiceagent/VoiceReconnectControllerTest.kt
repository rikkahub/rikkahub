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
        assertFalse(controller.hasPendingReconnect())
    }

    @Test
    fun `duplicate failure for scheduled session is coalesced without another retry attempt`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()

        controller.markEligible(sessionId = 7L)
        val firstDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop one"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(firstDecision is VoiceReconnectDecision.Schedule)
        val schedule = firstDecision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        val secondDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop two"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertSame(VoiceReconnectDecision.AlreadyPlanned, secondDecision)
        assertTrue(controller.isCurrentJob(job))
    }

    @Test
    fun `duplicate activation failure with pending reconnect is coalesced without another retry attempt`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )

        controller.markEligible(sessionId = 7L)
        assertTrue(controller.reserveActivation(sessionId = 7L))
        val firstDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"),
            reason = VoiceSessionStopReason.WebSocketClosed,
        )
        val secondDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("duplicate activation drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertSame(VoiceReconnectDecision.DeferredForActivation, firstDecision)
        assertSame(VoiceReconnectDecision.AlreadyPlanned, secondDecision)
        val pending = controller.consumePendingActivation(sessionId = 7L)
        assertEquals(1, pending?.attempt)
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
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(decision is VoiceReconnectDecision.Schedule)
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        val cancellation = controller.cancel()

        assertSame(job, cancellation.job)
        assertTrue(cancellation.hadAutomaticReconnect)
        assertFalse(controller.hasPendingReconnect())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelled planned reconnect cannot be scheduled later`() {
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        val cancellation = controller.cancel()

        assertNull(cancellation.job)
        assertTrue(cancellation.hadAutomaticReconnect)
        assertFalse(controller.setScheduled(plan = schedule.plan, job = job))
        assertFalse(controller.isCurrentJob(job))
    }

    @Test
    fun `stale plan cannot schedule a newer planned reconnect`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val staleJob = Job()
        val currentJob = Job()

        controller.markEligible(sessionId = 7L)
        val staleDecision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop one"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(staleDecision is VoiceReconnectDecision.Schedule)
        val staleSchedule = staleDecision as VoiceReconnectDecision.Schedule
        controller.cancel()

        controller.markEligible(sessionId = 8L)
        val currentDecision = controller.planReconnect(
            failedSessionId = 8L,
            event = GeminiLiveEvent.WebSocketFailure("drop two"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(currentDecision is VoiceReconnectDecision.Schedule)
        val currentSchedule = currentDecision as VoiceReconnectDecision.Schedule

        assertFalse(controller.setScheduled(plan = staleSchedule.plan, job = staleJob))
        assertFalse(controller.isCurrentJob(staleJob))
        assertTrue(controller.setScheduled(plan = currentSchedule.plan, job = currentJob))
        assertTrue(controller.isCurrentJob(currentJob))
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
        assertTrue(controller.setScheduled(plan = pending!!, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })

        assertEquals(1, controller.completeAttempt(job = job))
    }

    @Test
    fun `consumed activation deferred reconnect cancels superseded automatic job`() {
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
        controller.markEligible(sessionId = 8L)
        assertTrue(controller.reserveActivation(sessionId = 8L))
        val retryDecision = controller.planReconnect(
            failedSessionId = 8L,
            event = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"),
            reason = VoiceSessionStopReason.WebSocketClosed,
        )
        assertSame(VoiceReconnectDecision.DeferredForActivation, retryDecision)
        assertFalse(job.isCancelled)

        val pending = controller.consumePendingActivation(sessionId = 8L)

        assertEquals(2, pending?.attempt)
        assertTrue(job.isCancelled)
        assertFalse(controller.isCurrentJob(job))
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
        assertTrue(controller.setScheduled(plan = pending!!, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
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
    fun `retryable failure during automatic attempt cancels superseded job`() {
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
        val retryDecision = controller.planReconnect(
            failedSessionId = 8L,
            event = GeminiLiveEvent.WebSocketFailure("retry failed"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(retryDecision is VoiceReconnectDecision.Schedule)
        assertTrue(job.isCancelled)
        assertFalse(controller.isCurrentJob(job))
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
        controller.markEligible(sessionId = 8L)
        assertTrue(controller.reserveActivation(sessionId = 8L))

        assertTrue(controller.isCurrentJob(job))
    }

    @Test
    fun `successful reconnect completion clears automatic reconnect state`() {
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
        controller.markEligible(sessionId = 8L)
        assertTrue(controller.reserveActivation(sessionId = 8L))
        assertNull(controller.finishActivation(sessionId = 8L))

        assertEquals(1, controller.completeAttempt(job = job))
        assertFalse(controller.isCurrentJob(job))
        assertFalse(controller.hasPendingReconnect())
        assertFalse(controller.cancel().hadAutomaticReconnect)
    }

    @Test
    fun `attempt belongs to new session as soon as it begins`() {
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
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        assertEquals(8L, controller.beginAttempt(job = job) { 8L })
        val retryDecision = controller.planReconnect(
            failedSessionId = 8L,
            event = GeminiLiveEvent.WebSocketFailure("attached"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )

        assertTrue(retryDecision is VoiceReconnectDecision.Schedule)
    }

    @Test
    fun `stale scheduled attempt does not allocate session id`() {
        val controller = VoiceReconnectController(
            policy = VoiceReconnectPolicy(maxAttempts = 3, baseDelayMs = 10L, maxDelayMs = 10L, jitterRatio = 0.0),
            nowMs = { 1_000L },
        )
        val job = Job()
        var allocated = false

        controller.markEligible(sessionId = 7L)
        val decision = controller.planReconnect(
            failedSessionId = 7L,
            event = GeminiLiveEvent.WebSocketFailure("drop"),
            reason = VoiceSessionStopReason.WebSocketFailure,
        )
        assertTrue(decision is VoiceReconnectDecision.Schedule)
        val schedule = decision as VoiceReconnectDecision.Schedule
        assertTrue(controller.setScheduled(plan = schedule.plan, job = job))
        controller.cancel()

        val attemptSessionId = controller.beginAttempt(job = job) {
            allocated = true
            8L
        }

        assertNull(attemptSessionId)
        assertFalse(allocated)
    }
}
