package me.rerere.rikkahub.voiceagent.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAnnouncementLifecycleTest {

    private val reducer = AnnouncerReducer(quietWindowMs = 2_000L, maxHoldMs = 15_000L)
    private val completion = AnnouncementIntent.Completion(callId = "c1", jobId = "j1")
    private val terminal = AnnouncementIntent.Terminal(callId = "c2", jobId = "j2")
    private val stillWorking = AnnouncementIntent.StillWorking(callId = "c3", jobId = "j3")
    private val attached = AnnouncerState(bridgeSessionId = 7L)

    private fun AnnouncerTransition.sends(): List<AnnouncementIntent> =
        effects.filterIsInstance<AnnouncerEffect.Send>().map { it.intent }

    private fun AnnouncerTransition.fallbacks(): List<AnnouncementIntent> =
        effects.filterIsInstance<AnnouncerEffect.FallbackToText>().map { it.intent }
    // --- immediate dispatch ---

    @Test
    fun `intent on an idle attached quiet state sends immediately`() {
        val transition = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, nowMs = 100L))
        assertEquals(listOf(completion), transition.sends())
        assertEquals(completion, transition.state.inFlight)
        assertTrue(transition.state.queue.isEmpty())
    }

    @Test
    fun `send carries the attached session id`() {
        val transition = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, nowMs = 100L))
        assertEquals(7L, transition.effects.filterIsInstance<AnnouncerEffect.Send>().single().sessionId)
    }

    @Test
    fun `second intent queues while one is in flight`() {
        val inFlightState = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        val transition = reducer.reduce(inFlightState, AnnouncerEvent.IntentEnqueued(terminal, 101L))
        assertTrue(transition.sends().isEmpty())
        assertEquals(listOf(terminal), transition.state.queue)
    }

    // --- gating ---

    @Test
    fun `audio active blocks and arms the hold deadline`() {
        val state = attached.copy(audioActive = true)
        val transition = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 100L))
        assertTrue(transition.sends().isEmpty())
        assertEquals(100L + 15_000L, transition.state.holdDeadlineAtMs)
        assertTrue(transition.effects.contains(AnnouncerEffect.StartHoldDeadline(15_000L)))
    }

    @Test
    fun `audio going inactive releases the blocked head`() {
        val blocked = reducer.reduce(
            attached.copy(audioActive = true), AnnouncerEvent.IntentEnqueued(completion, 100L)
        ).state
        val transition = reducer.reduce(blocked, AnnouncerEvent.AudioActiveChanged(active = false, nowMs = 200L))
        assertEquals(listOf(completion), transition.sends())
    }

    @Test
    fun `input delta inside the quiet window blocks and starts the quiet timer with the remaining delay`() {
        val state = attached.copy(lastInputDeltaAtMs = 900L)
        val transition = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 1_000L))
        assertTrue(transition.sends().isEmpty())
        assertTrue(transition.effects.contains(AnnouncerEffect.StartQuietTimer(1_900L)))
    }

    @Test
    fun `quiet timer firing after the window sends`() {
        val blocked = reducer.reduce(
            attached.copy(lastInputDeltaAtMs = 900L), AnnouncerEvent.IntentEnqueued(completion, 1_000L)
        ).state
        val transition = reducer.reduce(blocked, AnnouncerEvent.QuietTimerFired(nowMs = 2_950L))
        assertEquals(listOf(completion), transition.sends())
    }

    @Test
    fun `a new input delta while blocked restarts the quiet timer`() {
        val blocked = reducer.reduce(
            attached.copy(lastInputDeltaAtMs = 900L), AnnouncerEvent.IntentEnqueued(completion, 1_000L)
        ).state
        val transition = reducer.reduce(blocked, AnnouncerEvent.InputDelta(nowMs = 1_500L))
        assertTrue(transition.sends().isEmpty())
        assertTrue(transition.effects.contains(AnnouncerEffect.StartQuietTimer(2_000L)))
    }

    @Test
    fun `after a sent announcement the next waits for generation complete`() {
        val afterSend = reducer.reduce(
            reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 200L),
        ).state
        val transition = reducer.reduce(afterSend, AnnouncerEvent.IntentEnqueued(terminal, 300L))
        assertTrue(transition.sends().isEmpty())
        val released = reducer.reduce(transition.state, AnnouncerEvent.GenerationComplete(400L))
        assertEquals(listOf(terminal), released.sends())
    }

    @Test
    fun `the first announcement does not wait for generation complete`() {
        val transition = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L))
        assertEquals(listOf(completion), transition.sends())
    }

    // --- hold deadline release ---

    @Test
    fun `hold deadline firing releases a still-blocked head with the deadline diagnostic`() {
        val blocked = reducer.reduce(
            attached.copy(audioActive = true), AnnouncerEvent.IntentEnqueued(completion, 100L)
        ).state
        val transition = reducer.reduce(blocked, AnnouncerEvent.HoldDeadlineFired(nowMs = 15_100L))
        assertEquals(listOf(completion), transition.sends())
        assertTrue(
            transition.effects.contains(
                AnnouncerEffect.Diagnostic("hermes_announcement_released_at_deadline", "completion:c1")
            )
        )
    }

    @Test
    fun `hold deadline is per head intent and cleared on send`() {
        val blocked = reducer.reduce(
            attached.copy(audioActive = true), AnnouncerEvent.IntentEnqueued(completion, 100L)
        ).state
        val sent = reducer.reduce(blocked, AnnouncerEvent.AudioActiveChanged(active = false, nowMs = 500L))
        assertEquals(null, sent.state.holdDeadlineAtMs)
        assertTrue(sent.effects.contains(AnnouncerEffect.CancelHoldDeadline))
    }

    // --- detached bridge (PR-2 delta 3) ---

    @Test
    fun `completion intent while detached falls back to text immediately`() {
        val transition = reducer.reduce(AnnouncerState(), AnnouncerEvent.IntentEnqueued(completion, 100L))
        assertEquals(listOf(completion), transition.fallbacks())
        assertTrue(transition.state.queue.isEmpty())
    }

    @Test
    fun `still-working intent while detached is dropped with the unavailable diagnostic, no fallback`() {
        val transition = reducer.reduce(AnnouncerState(), AnnouncerEvent.IntentEnqueued(stillWorking, 100L))
        assertTrue(transition.fallbacks().isEmpty())
        assertTrue(
            transition.effects.contains(
                AnnouncerEffect.Diagnostic("hermes_announcement_dropped_bridge_unavailable", "still-working:c3")
            )
        )
    }

    @Test
    fun `bridge detach drains the whole queue in order`() {
        var state = attached.copy(audioActive = true)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(stillWorking, 101L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 102L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.BridgeDetached(nowMs = 200L))
        assertEquals(listOf(completion, terminal), transition.fallbacks())
        assertTrue(transition.state.queue.isEmpty())
    }

    // --- send outcomes ---

    @Test
    fun `failed send falls back to text and the queue continues`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Failed, 200L))
        assertEquals(listOf(completion), transition.fallbacks())
        assertEquals(listOf(terminal), transition.sends())
    }

    @Test
    fun `skipped send emits no fallback and continues the queue`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Skipped, 200L))
        assertTrue(transition.fallbacks().isEmpty())
        assertEquals(listOf(terminal), transition.sends())
    }

    @Test
    fun `sent outcome sets awaitingGenerationComplete so the next head waits`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 200L))
        assertTrue(transition.sends().isEmpty())
        assertTrue(transition.state.awaitingGenerationComplete)
        assertEquals(listOf(terminal), transition.state.queue)
    }

    @Test
    fun `sendReturned with nothing in flight is a no-op`() {
        val transition = reducer.reduce(attached, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 100L))
        assertEquals(attached, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    // --- FIFO (PR-2 delta 4) ---

    @Test
    fun `intents dispatch strictly in enqueue order across jobs`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(stillWorking, 102L)).state
        var sent = mutableListOf<AnnouncementIntent>()
        var current = state
        repeat(3) {
            val transition = reducer.reduce(
                current, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Skipped, 200L)
            )
            sent += transition.sends()
            current = transition.state
        }
        assertEquals(listOf(terminal, stillWorking), sent) // completion was already in flight in `state`
    }

    // --- close ---

    @Test
    fun `close drains queued completion and terminal intents to text fallback and drops still-working`() {
        var state = attached.copy(audioActive = true)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(stillWorking, 101L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.Close)
        assertEquals(listOf(completion), transition.fallbacks())
        assertTrue(transition.state.closed)
        assertTrue(transition.effects.contains(AnnouncerEffect.CancelQuietTimer))
        assertTrue(transition.effects.contains(AnnouncerEffect.CancelHoldDeadline))
    }

    @Test
    fun `intent enqueued after close falls back immediately`() {
        val closed = reducer.reduce(AnnouncerState(), AnnouncerEvent.Close).state
        val transition = reducer.reduce(closed, AnnouncerEvent.IntentEnqueued(completion, 100L))
        assertEquals(listOf(completion), transition.fallbacks())
        assertTrue(transition.state.queue.isEmpty())
    }

    @Test
    fun `sendReturned after close clears inFlight without new sends`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state
        state = reducer.reduce(state, AnnouncerEvent.Close).state
        val transition = reducer.reduce(state, AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 200L))
        assertEquals(null, transition.state.inFlight)
        assertTrue(transition.sends().isEmpty())
    }
}
