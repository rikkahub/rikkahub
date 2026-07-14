package me.rerere.rikkahub.voiceagent.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAnnouncementLifecycleTest {

    private val reducer = AnnouncerReducer(quietWindowMs = 2_000L, blockedWatchdogMs = 15_000L)
    private val completion = AnnouncementIntent.Completion(callId = "c1", jobId = "j1")
    private val terminal = AnnouncementIntent.Terminal(callId = "c2", jobId = "j2")
    private val stillWorking = AnnouncementIntent.StillWorking(callId = "c3", jobId = "j3")
    private val attached = AnnouncerState(bridgeSessionId = 7L)

    private fun AnnouncerTransition.sends(): List<AnnouncementIntent> =
        effects.filterIsInstance<AnnouncerEffect.Send>().map { it.intent }

    private fun AnnouncerTransition.fallbacks(): List<AnnouncementIntent> =
        effects.filterIsInstance<AnnouncerEffect.FallbackToText>().map { it.intent }

    @Test
    fun `intent on an idle attached drained state sends immediately and reserves the turn`() {
        val transition = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L))

        assertEquals(listOf(completion), transition.sends())
        assertEquals(completion, transition.state.inFlight)
        assertEquals(GeminiTurnGate.SendReserved(), transition.state.geminiTurn)
        assertTrue(transition.state.pendingJobs.isEmpty())
        assertEquals(7L, transition.effects.filterIsInstance<AnnouncerEffect.Send>().single().sessionId)
    }

    @Test
    fun `turn complete alone cannot release while playback is active`() {
        var state = attached
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(10L)).state
        state = reducer.reduce(state, AnnouncerEvent.PlaybackActive(4L, 20L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 30L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(40L))

        assertTrue(transition.sends().isEmpty())
    }

    @Test
    fun `playback drain alone cannot release while Gemini turn is active`() {
        var state = attached
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(10L)).state
        state = reducer.reduce(state, AnnouncerEvent.PlaybackActive(4L, 20L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 30L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.PlaybackDrained(4L, 40L))

        assertTrue(transition.sends().isEmpty())
    }

    @Test
    fun `turn complete then matching playback drain release one intent`() {
        var state = attached
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(10L)).state
        state = reducer.reduce(state, AnnouncerEvent.PlaybackActive(4L, 20L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 30L)).state
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(40L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.PlaybackDrained(4L, 50L))

        assertEquals(listOf(completion), transition.sends())
        assertEquals(GeminiTurnGate.SendReserved(), transition.state.geminiTurn)
    }

    @Test
    fun `matching playback drain then turn complete release one intent`() {
        var state = attached
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(10L)).state
        state = reducer.reduce(state, AnnouncerEvent.PlaybackActive(4L, 20L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 30L)).state
        state = reducer.reduce(state, AnnouncerEvent.PlaybackDrained(4L, 40L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(50L))

        assertEquals(listOf(completion), transition.sends())
        assertEquals(GeminiTurnGate.SendReserved(), transition.state.geminiTurn)
    }

    @Test
    fun `session retirement clears old turn ownership without releasing on the old bridge`() {
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 10L)).state

        val retired = reducer.reduce(state, AnnouncerEvent.GeminiSessionRetired(20L))

        assertEquals(GeminiTurnGate.Idle, retired.state.geminiTurn)
        assertEquals(PlaybackGate(), retired.state.playback)
        assertTrue(retired.state.bridgeRetired)
        assertTrue(retired.sends().isEmpty())
        assertEquals(completion, retired.state.pendingJobs.single().final)

        val settlingEvent = reducer.reduce(
            retired.state,
            AnnouncerEvent.PlaybackDrained(generation = 1L, nowMs = 25L),
        )
        assertTrue(settlingEvent.sends().isEmpty())
        assertEquals(completion, settlingEvent.state.pendingJobs.single().final)

        val detached = reducer.reduce(
            settlingEvent.state,
            AnnouncerEvent.BridgeDetached(nowMs = 27L),
        )
        assertTrue(detached.fallbacks().isEmpty())
        assertEquals(completion, detached.state.pendingJobs.single().final)

        val replacementAttached = reducer.reduce(
            detached.state,
            AnnouncerEvent.BridgeAttached(sessionId = 8L, nowMs = 30L),
        )
        assertFalse(replacementAttached.state.bridgeRetired)
        assertEquals(listOf(completion), replacementAttached.sends())
        assertEquals(8L, replacementAttached.effects.filterIsInstance<AnnouncerEffect.Send>().single().sessionId)
    }

    @Test
    fun `blocked watchdog diagnoses but never sends`() {
        var state = attached
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 100L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.BlockedWatchdogFired(15_100L))

        assertTrue(transition.sends().isEmpty())
        assertTrue(transition.effects.any {
            it is AnnouncerEffect.Diagnostic &&
                it.name == "hermes_announcement_blocked_watchdog" &&
                it.detail.contains("gemini=Active")
        })
        assertEquals(30_100L, transition.state.blockedWatchdogAtMs)
        assertTrue(transition.effects.contains(AnnouncerEffect.StartBlockedWatchdog(15_000L)))
    }

    @Test
    fun `blocked watchdog cannot substitute for an elapsed quiet timer boundary`() {
        var state = attached.copy(lastInputDeltaAtMs = 0L)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 1L)).state

        val watchdog = reducer.reduce(state, AnnouncerEvent.BlockedWatchdogFired(20_000L))

        assertTrue(watchdog.sends().isEmpty())
        assertEquals(completion, watchdog.state.pendingJobs.single().final)

        val quietBoundary = reducer.reduce(
            watchdog.state,
            AnnouncerEvent.QuietTimerFired(20_001L),
        )
        assertEquals(listOf(completion), quietBoundary.sends())
    }

    @Test
    fun `turn complete racing ahead of send success does not reopen the turn`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(20L)).state

        val transition = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 30L),
        )

        assertEquals(GeminiTurnGate.Idle, transition.state.geminiTurn)
    }

    @Test
    fun `activity racing ahead of a failed send keeps the observed Gemini turn active`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.GeminiTurnActive(20L)).state

        val transition = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Failed, 30L),
        )

        assertEquals(GeminiTurnGate.Active, transition.state.geminiTurn)
    }

    @Test
    fun `new progress replaces pending progress for the same job`() {
        val first = AnnouncementIntent.StillWorking("c1", "j1")
        val newer = AnnouncementIntent.StillWorking("c1-new", "j1")
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(first, 10L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(newer, 20L))

        assertEquals(newer, transition.state.pendingJobs.single().progress)
        assertTrue(transition.effects.any {
            it is AnnouncerEffect.Diagnostic && it.name == "hermes_announcement_progress_replaced"
        })
    }

    @Test
    fun `final stays behind latest progress and later progress is ignored`() {
        val progress = AnnouncementIntent.StillWorking("c1", "j1")
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(progress, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 20L)).state
        val ignored = reducer.reduce(
            state,
            AnnouncerEvent.IntentEnqueued(AnnouncementIntent.StillWorking("c1-late", "j1"), 30L),
        )

        val job = ignored.state.pendingJobs.single()
        assertEquals(progress.copy(allowTerminalRecord = true), job.progress)
        assertEquals(completion, job.final)
        assertTrue(ignored.effects.any {
            it is AnnouncerEffect.Diagnostic &&
                it.name == "hermes_announcement_progress_ignored_after_final"
        })
    }

    @Test
    fun `duplicate finals collapse to one final slot`() {
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 20L)).state
        assertEquals(completion, state.pendingJobs.single().final)
        assertEquals(1, state.pendingJobs.size)
    }

    @Test
    fun `distinct jobs retain first enqueue order`() {
        val progressA = AnnouncementIntent.StillWorking("c-a", "j-a")
        val finalB = AnnouncementIntent.Completion("c-b", "j-b")
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(progressA, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(finalB, 20L)).state
        assertEquals(
            listOf(AnnouncementJobKey("job:j-a"), AnnouncementJobKey("job:j-b")),
            state.pendingJobs.map { it.key },
        )
    }

    @Test
    fun `progress then final remain ahead of a later job`() {
        val progressA = AnnouncementIntent.StillWorking("c-a", "j-a")
        val finalA = AnnouncementIntent.Completion("c-a", "j-a")
        val finalB = AnnouncementIntent.Completion("c-b", "j-b")
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(progressA, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(finalA, 20L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(finalB, 30L)).state

        var transition = reducer.reduce(state, AnnouncerEvent.GeminiTurnComplete(40L))
        assertEquals(listOf(progressA.copy(allowTerminalRecord = true)), transition.sends())
        transition = reducer.reduce(
            transition.state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Skipped, 50L),
        )
        assertEquals(listOf(finalA), transition.sends())
        transition = reducer.reduce(
            transition.state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Skipped, 60L),
        )
        assertEquals(listOf(finalB), transition.sends())
    }

    @Test
    fun `stale playback drain cannot release current generation`() {
        var state = attached.copy(geminiTurn = GeminiTurnGate.Idle)
        state = reducer.reduce(state, AnnouncerEvent.PlaybackActive(9L, 10L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 20L)).state
        val transition = reducer.reduce(state, AnnouncerEvent.PlaybackDrained(8L, 30L))
        assertTrue(transition.sends().isEmpty())
        assertFalse(transition.state.playback.drained)
        assertTrue(transition.effects.any {
            it is AnnouncerEffect.Diagnostic && it.name == "hermes_announcement_stale_playback_event"
        })
    }

    @Test
    fun `quiet window remains an independent safe-boundary blocker`() {
        var state = attached.copy(lastInputDeltaAtMs = 900L)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 1_000L)).state
        assertTrue(state.inFlight == null)

        val transition = reducer.reduce(state, AnnouncerEvent.QuietTimerFired(2_950L))

        assertEquals(listOf(completion), transition.sends())
    }

    @Test
    fun `a new input delta restarts the quiet timer`() {
        var state = attached.copy(lastInputDeltaAtMs = 900L)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 1_000L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.InputDelta(1_500L))

        assertTrue(transition.sends().isEmpty())
        assertTrue(transition.effects.contains(AnnouncerEffect.StartQuietTimer(2_000L)))
    }

    @Test
    fun `failed send falls back and releases the next job only when reservation is idle`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state

        val transition = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Failed, 200L),
        )

        assertEquals(listOf(completion), transition.fallbacks())
        assertEquals(listOf(terminal), transition.sends())
    }

    @Test
    fun `skipped send emits no fallback and releases the next job`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state

        val transition = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Skipped, 200L),
        )

        assertTrue(transition.fallbacks().isEmpty())
        assertEquals(listOf(terminal), transition.sends())
    }

    @Test
    fun `attachment invalidation after close falls back an in-flight final exactly once`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.Close).state

        val transition = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.AttachmentInvalidated, 200L),
        )

        assertEquals(listOf(completion), transition.fallbacks())
        assertEquals(null, transition.state.inFlight)
        val repeated = reducer.reduce(
            transition.state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.AttachmentInvalidated, 201L),
        )
        assertTrue(repeated.fallbacks().isEmpty())
    }

    @Test
    fun `sent announcement requires a later turn boundary before another job`() {
        var state = reducer.reduce(attached, AnnouncerEvent.IntentEnqueued(completion, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state

        val returned = reducer.reduce(
            state,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 200L),
        )
        assertTrue(returned.sends().isEmpty())
        assertEquals(GeminiTurnGate.Active, returned.state.geminiTurn)

        val released = reducer.reduce(returned.state, AnnouncerEvent.GeminiTurnComplete(300L))
        assertEquals(listOf(terminal), released.sends())
    }

    @Test
    fun `bridge detach drains grouped intents in progress then final and cross-job order`() {
        val progress = AnnouncementIntent.StillWorking("c1", "j1")
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(progress, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(completion, 101L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 102L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.BridgeDetached(200L))

        assertEquals(listOf(completion, terminal), transition.fallbacks())
        assertTrue(transition.state.pendingJobs.isEmpty())
        assertTrue(transition.effects.any {
            it is AnnouncerEffect.Diagnostic &&
                it.name == "hermes_announcement_dropped_bridge_unavailable"
        })
    }

    @Test
    fun `detached completion falls back and detached progress is dropped`() {
        val completionTransition = reducer.reduce(
            AnnouncerState(),
            AnnouncerEvent.IntentEnqueued(completion, 100L),
        )
        val progressTransition = reducer.reduce(
            AnnouncerState(),
            AnnouncerEvent.IntentEnqueued(stillWorking, 100L),
        )

        assertEquals(listOf(completion), completionTransition.fallbacks())
        assertTrue(progressTransition.fallbacks().isEmpty())
        assertTrue(progressTransition.effects.any {
            it is AnnouncerEffect.Diagnostic &&
                it.name == "hermes_announcement_dropped_bridge_unavailable"
        })
    }

    @Test
    fun `close drains pending finals exactly once and drops progress`() {
        var state = attached.copy(geminiTurn = GeminiTurnGate.Active)
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(stillWorking, 100L)).state
        state = reducer.reduce(state, AnnouncerEvent.IntentEnqueued(terminal, 101L)).state

        val transition = reducer.reduce(state, AnnouncerEvent.Close)

        assertEquals(listOf(terminal), transition.fallbacks())
        assertTrue(transition.state.closed)
        assertTrue(transition.state.pendingJobs.isEmpty())
        assertTrue(transition.effects.contains(AnnouncerEffect.CancelBlockedWatchdog))

        val repeated = reducer.reduce(transition.state, AnnouncerEvent.Close)
        assertTrue(repeated.fallbacks().isEmpty())
        assertTrue(repeated.effects.isEmpty())
    }

    @Test
    fun `intent after close falls back without entering pending jobs`() {
        val closed = reducer.reduce(AnnouncerState(), AnnouncerEvent.Close).state
        val transition = reducer.reduce(closed, AnnouncerEvent.IntentEnqueued(completion, 100L))

        assertEquals(listOf(completion), transition.fallbacks())
        assertTrue(transition.state.pendingJobs.isEmpty())
    }

    @Test
    fun `sendReturned with nothing in flight is a no-op`() {
        val transition = reducer.reduce(
            attached,
            AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Sent, 100L),
        )
        assertEquals(attached, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `drain marker is a pure no-op`() {
        val open = attached.copy(geminiTurn = GeminiTurnGate.Active)
        val transition = reducer.reduce(open, AnnouncerEvent.DrainMarker(1L))
        assertEquals(open, transition.state)
        assertTrue(transition.effects.isEmpty())
    }
}
