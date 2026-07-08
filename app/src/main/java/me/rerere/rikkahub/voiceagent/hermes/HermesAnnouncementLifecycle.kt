package me.rerere.rikkahub.voiceagent.hermes

/**
 * Pure announcement lifecycle. The reducer decides WHEN a proactive announcement
 * may go out (quiet window, assistant audio, generation-complete pacing, hold
 * deadline, bridge presence); the effect executor in HermesAnnouncer decides
 * WHETHER it is still needed, by re-fetching the durable record at send time.
 *
 * Rules (Phase 3 precedent): pure and total — no clocks, no I/O, no exceptions.
 * Time arrives in events as nowMs.
 */

sealed interface AnnouncementIntent {
    data class Completion(val callId: String, val jobId: String?) : AnnouncementIntent
    data class Terminal(val callId: String, val jobId: String?) : AnnouncementIntent
    data class StillWorking(val callId: String, val jobId: String) : AnnouncementIntent
}

internal fun AnnouncementIntent.label(): String = when (this) {
    is AnnouncementIntent.Completion -> "completion:$callId"
    is AnnouncementIntent.Terminal -> "terminal:$callId"
    is AnnouncementIntent.StillWorking -> "still-working:$callId"
}

enum class AnnouncementSendOutcome { Sent, Failed, Skipped }

data class AnnouncerState(
    /** null = no bridge attached. */
    val bridgeSessionId: Long? = null,
    val audioActive: Boolean = false,
    val lastInputDeltaAtMs: Long? = null,
    /** Set after each delivered announcement; cleared by GenerationComplete. */
    val awaitingGenerationComplete: Boolean = false,
    val queue: List<AnnouncementIntent> = emptyList(),
    val inFlight: AnnouncementIntent? = null,
    /** Hold deadline for the current head intent; null = not yet blocked. */
    val holdDeadlineAtMs: Long? = null,
    val closed: Boolean = false,
)

sealed interface AnnouncerEvent {
    data class IntentEnqueued(val intent: AnnouncementIntent, val nowMs: Long) : AnnouncerEvent
    data class BridgeAttached(val sessionId: Long, val nowMs: Long) : AnnouncerEvent
    data class BridgeDetached(val nowMs: Long) : AnnouncerEvent
    data class AudioActiveChanged(val active: Boolean, val nowMs: Long) : AnnouncerEvent
    data class InputDelta(val nowMs: Long) : AnnouncerEvent
    data class GenerationComplete(val nowMs: Long) : AnnouncerEvent
    data class QuietTimerFired(val nowMs: Long) : AnnouncerEvent
    data class HoldDeadlineFired(val nowMs: Long) : AnnouncerEvent
    data class SendReturned(val outcome: AnnouncementSendOutcome, val nowMs: Long) : AnnouncerEvent
    data object Close : AnnouncerEvent

    /**
     * Shell-level FIFO sentinel for [HermesAnnouncer.awaitClosed]: a pure no-op in the
     * reducer. When the consumer reaches it, every event posted before it (including the
     * Close that drains the queue) has already been processed.
     */
    data class DrainMarker(val id: Long) : AnnouncerEvent
}

sealed interface AnnouncerEffect {
    /** Replaces any previously started quiet timer. */
    data class StartQuietTimer(val delayMs: Long) : AnnouncerEffect
    data object CancelQuietTimer : AnnouncerEffect

    /** Replaces any previously started hold-deadline timer. */
    data class StartHoldDeadline(val delayMs: Long) : AnnouncerEffect
    data object CancelHoldDeadline : AnnouncerEffect

    data class Send(val intent: AnnouncementIntent, val sessionId: Long) : AnnouncerEffect

    /** Only ever emitted for Completion/Terminal — still-working never falls back to text. */
    data class FallbackToText(val intent: AnnouncementIntent) : AnnouncerEffect

    data class Diagnostic(val name: String, val detail: String) : AnnouncerEffect
}

data class AnnouncerTransition(
    val state: AnnouncerState,
    val effects: List<AnnouncerEffect>,
)

class AnnouncerReducer(
    private val quietWindowMs: Long,
    private val maxHoldMs: Long,
) {
    fun reduce(state: AnnouncerState, event: AnnouncerEvent): AnnouncerTransition = when (event) {
        is AnnouncerEvent.Close -> drainOnClose(state)

        is AnnouncerEvent.DrainMarker -> noChange(state)

        is AnnouncerEvent.IntentEnqueued ->
            if (state.closed) {
                AnnouncerTransition(state, fallbackOrDrop(event.intent))
            } else {
                settle(state.copy(queue = state.queue + event.intent), event.nowMs)
            }

        is AnnouncerEvent.BridgeAttached ->
            if (state.closed) noChange(state)
            else settle(state.copy(bridgeSessionId = event.sessionId), event.nowMs)

        is AnnouncerEvent.BridgeDetached ->
            if (state.closed) noChange(state)
            else settle(state.copy(bridgeSessionId = null), event.nowMs)

        is AnnouncerEvent.AudioActiveChanged ->
            if (state.closed) noChange(state)
            else settle(state.copy(audioActive = event.active), event.nowMs)

        is AnnouncerEvent.InputDelta ->
            if (state.closed) noChange(state)
            else settle(state.copy(lastInputDeltaAtMs = event.nowMs), event.nowMs)

        is AnnouncerEvent.GenerationComplete ->
            if (state.closed) noChange(state)
            else settle(state.copy(awaitingGenerationComplete = false), event.nowMs)

        is AnnouncerEvent.QuietTimerFired ->
            if (state.closed) noChange(state) else settle(state, event.nowMs)

        is AnnouncerEvent.HoldDeadlineFired ->
            if (state.closed) noChange(state) else settle(state, event.nowMs)

        is AnnouncerEvent.SendReturned -> reduceSendReturned(state, event)
    }

    private fun reduceSendReturned(
        state: AnnouncerState,
        event: AnnouncerEvent.SendReturned,
    ): AnnouncerTransition {
        val intent = state.inFlight ?: return noChange(state)
        val cleared = state.copy(inFlight = null)
        return when (event.outcome) {
            AnnouncementSendOutcome.Sent ->
                settle(cleared.copy(awaitingGenerationComplete = true), event.nowMs)

            AnnouncementSendOutcome.Failed -> {
                val fallback = fallbackOrDrop(intent)
                val settled = if (cleared.closed) noChange(cleared) else settle(cleared, event.nowMs)
                AnnouncerTransition(settled.state, fallback + settled.effects)
            }

            AnnouncementSendOutcome.Skipped ->
                if (cleared.closed) noChange(cleared) else settle(cleared, event.nowMs)
        }
    }

    private fun drainOnClose(state: AnnouncerState): AnnouncerTransition {
        val effects = mutableListOf<AnnouncerEffect>(
            AnnouncerEffect.CancelQuietTimer,
            AnnouncerEffect.CancelHoldDeadline,
        )
        state.queue.forEach { effects += fallbackOrDrop(it) }
        return AnnouncerTransition(
            state.copy(closed = true, queue = emptyList(), holdDeadlineAtMs = null),
            effects,
        )
    }

    /** Completion/Terminal fall back to a visible text message; still-working is dropped. */
    private fun fallbackOrDrop(intent: AnnouncementIntent): List<AnnouncerEffect> = when (intent) {
        is AnnouncementIntent.Completion,
        is AnnouncementIntent.Terminal,
            -> listOf(AnnouncerEffect.FallbackToText(intent))

        is AnnouncementIntent.StillWorking -> listOf(
            AnnouncerEffect.Diagnostic("hermes_announcement_dropped_bridge_unavailable", intent.label())
        )
    }

    private fun noChange(state: AnnouncerState) = AnnouncerTransition(state, emptyList())

    /**
     * Decide what to do with the head of the queue. Called after every state change;
     * loops so a detached bridge drains the whole queue in one transition.
     */
    private fun settle(initial: AnnouncerState, nowMs: Long): AnnouncerTransition {
        var state = initial
        val effects = mutableListOf<AnnouncerEffect>()
        while (true) {
            if (state.closed || state.inFlight != null) return AnnouncerTransition(state, effects)
            if (state.queue.isEmpty()) {
                if (state.holdDeadlineAtMs != null) {
                    state = state.copy(holdDeadlineAtMs = null)
                    effects += AnnouncerEffect.CancelHoldDeadline
                }
                return AnnouncerTransition(state, effects)
            }
            val head = state.queue.first()
            val sessionId = state.bridgeSessionId
            if (sessionId == null) {
                // Deliberate PR-2 delta 3: no bridge means immediate fallback, no hold.
                effects += fallbackOrDrop(head)
                state = state.copy(queue = state.queue.drop(1), holdDeadlineAtMs = null)
                continue
            }
            val quietRemainingMs = state.lastInputDeltaAtMs
                ?.let { (quietWindowMs - (nowMs - it)).coerceAtLeast(0L) }
                ?: 0L
            val blocked = state.audioActive || quietRemainingMs > 0L || state.awaitingGenerationComplete
            val deadline = state.holdDeadlineAtMs
            if (!blocked || (deadline != null && nowMs >= deadline)) {
                if (blocked) {
                    effects += AnnouncerEffect.Diagnostic(
                        "hermes_announcement_released_at_deadline",
                        head.label(),
                    )
                }
                if (deadline != null) {
                    effects += AnnouncerEffect.CancelHoldDeadline
                }
                effects += AnnouncerEffect.CancelQuietTimer
                effects += AnnouncerEffect.Send(head, sessionId)
                return AnnouncerTransition(
                    state.copy(queue = state.queue.drop(1), inFlight = head, holdDeadlineAtMs = null),
                    effects,
                )
            }
            if (deadline == null) {
                state = state.copy(holdDeadlineAtMs = nowMs + maxHoldMs)
                effects += AnnouncerEffect.StartHoldDeadline(maxHoldMs)
            }
            if (quietRemainingMs > 0L) {
                effects += AnnouncerEffect.StartQuietTimer(quietRemainingMs)
            }
            return AnnouncerTransition(state, effects)
        }
    }
}
