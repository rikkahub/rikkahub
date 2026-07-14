package me.rerere.rikkahub.voiceagent.hermes

/**
 * Pure announcement lifecycle. The reducer owns announcement ordering and decides
 * when a proactive turn may start. Time and playback ownership arrive as events;
 * the reducer performs no I/O and never treats a diagnostic timer as permission
 * to speak.
 */

sealed interface AnnouncementIntent {
    data class Completion(val callId: String, val jobId: String?) : AnnouncementIntent
    data class Terminal(val callId: String, val jobId: String?) : AnnouncementIntent
    data class StillWorking(
        val callId: String,
        val jobId: String,
        val allowTerminalRecord: Boolean = false,
    ) : AnnouncementIntent
}

internal fun AnnouncementIntent.label(): String = when (this) {
    is AnnouncementIntent.Completion -> "completion:$callId"
    is AnnouncementIntent.Terminal -> "terminal:$callId"
    is AnnouncementIntent.StillWorking -> "still-working:$callId"
}

data class AnnouncementJobKey(val value: String)

internal fun AnnouncementIntent.jobKey(): AnnouncementJobKey {
    val (callId, jobId) = when (this) {
        is AnnouncementIntent.Completion -> callId to jobId
        is AnnouncementIntent.Terminal -> callId to jobId
        is AnnouncementIntent.StillWorking -> callId to jobId
    }
    return AnnouncementJobKey(jobId?.let { "job:$it" } ?: "call:$callId")
}

enum class AnnouncementSendOutcome { Sent, Failed, Skipped }

sealed interface GeminiTurnGate {
    data object Idle : GeminiTurnGate
    data class SendReserved(
        val activityObserved: Boolean = false,
        val completed: Boolean = false,
    ) : GeminiTurnGate
    data object Active : GeminiTurnGate
}

data class PlaybackGate(
    val generation: Long = 0L,
    val drained: Boolean = true,
)

data class PendingAnnouncementJob(
    val key: AnnouncementJobKey,
    val progress: AnnouncementIntent.StillWorking? = null,
    val final: AnnouncementIntent? = null,
) {
    fun head(): AnnouncementIntent? = progress ?: final

    fun flatten(): List<AnnouncementIntent> = listOfNotNull(progress, final)
}

data class AnnouncerState(
    val bridgeSessionId: Long? = null,
    val geminiTurn: GeminiTurnGate = GeminiTurnGate.Idle,
    val playback: PlaybackGate = PlaybackGate(),
    val lastInputDeltaAtMs: Long? = null,
    val pendingJobs: List<PendingAnnouncementJob> = emptyList(),
    val finalizedJobKeys: Set<AnnouncementJobKey> = emptySet(),
    val inFlight: AnnouncementIntent? = null,
    val blockedWatchdogAtMs: Long? = null,
    val closed: Boolean = false,
)

sealed interface AnnouncerEvent {
    data class IntentEnqueued(val intent: AnnouncementIntent, val nowMs: Long) : AnnouncerEvent
    data class BridgeAttached(val sessionId: Long, val nowMs: Long) : AnnouncerEvent
    data class BridgeDetached(val nowMs: Long) : AnnouncerEvent
    data class GeminiTurnActive(val nowMs: Long) : AnnouncerEvent
    data class GeminiTurnComplete(val nowMs: Long) : AnnouncerEvent
    data class PlaybackActive(val generation: Long, val nowMs: Long) : AnnouncerEvent
    data class PlaybackDrainStarted(val generation: Long, val nowMs: Long) : AnnouncerEvent
    data class PlaybackDrained(val generation: Long, val nowMs: Long) : AnnouncerEvent
    data class InputDelta(val nowMs: Long) : AnnouncerEvent
    data class QuietTimerFired(val nowMs: Long) : AnnouncerEvent
    data class BlockedWatchdogFired(val nowMs: Long) : AnnouncerEvent
    data class SendReturned(val outcome: AnnouncementSendOutcome, val nowMs: Long) : AnnouncerEvent
    data object Close : AnnouncerEvent

    /** FIFO shell sentinel used by HermesAnnouncer.awaitClosed. */
    data class DrainMarker(val id: Long) : AnnouncerEvent
}

sealed interface AnnouncerEffect {
    data class StartQuietTimer(val delayMs: Long) : AnnouncerEffect
    data object CancelQuietTimer : AnnouncerEffect

    data class StartBlockedWatchdog(val delayMs: Long) : AnnouncerEffect
    data object CancelBlockedWatchdog : AnnouncerEffect

    data class Send(val intent: AnnouncementIntent, val sessionId: Long) : AnnouncerEffect
    data class FallbackToText(val intent: AnnouncementIntent) : AnnouncerEffect
    data class Diagnostic(val name: String, val detail: String) : AnnouncerEffect
}

data class AnnouncerTransition(
    val state: AnnouncerState,
    val effects: List<AnnouncerEffect>,
)

class AnnouncerReducer(
    private val quietWindowMs: Long,
    private val blockedWatchdogMs: Long,
) {
    init {
        require(quietWindowMs >= 0L) { "quietWindowMs must be non-negative" }
        require(blockedWatchdogMs > 0L) { "blockedWatchdogMs must be positive" }
    }

    fun reduce(state: AnnouncerState, event: AnnouncerEvent): AnnouncerTransition = when (event) {
        AnnouncerEvent.Close -> drainOnClose(state)
        is AnnouncerEvent.DrainMarker -> noChange(state)

        is AnnouncerEvent.IntentEnqueued -> when {
            state.closed -> AnnouncerTransition(state, fallbackOrDrop(event.intent))
            else -> enqueueIntent(state, event.intent).settleAt(event.nowMs)
        }

        is AnnouncerEvent.BridgeAttached -> when {
            state.closed -> noChange(state)
            else -> settle(state.copy(bridgeSessionId = event.sessionId), event.nowMs)
        }

        is AnnouncerEvent.BridgeDetached -> when {
            state.closed -> noChange(state)
            else -> settle(state.copy(bridgeSessionId = null), event.nowMs)
        }

        is AnnouncerEvent.GeminiTurnActive -> when {
            state.closed -> noChange(state)
            else -> settle(state.copy(geminiTurn = state.geminiTurn.onActivity()), event.nowMs)
        }

        is AnnouncerEvent.GeminiTurnComplete -> when {
            state.closed -> noChange(state)
            else -> settle(state.copy(geminiTurn = state.geminiTurn.onComplete()), event.nowMs)
        }

        is AnnouncerEvent.PlaybackActive -> reducePlaybackEvent(
            state = state,
            generation = event.generation,
            nowMs = event.nowMs,
            drained = false,
        )

        is AnnouncerEvent.PlaybackDrainStarted -> reduceDrainStarted(state, event)

        is AnnouncerEvent.PlaybackDrained -> reducePlaybackEvent(
            state = state,
            generation = event.generation,
            nowMs = event.nowMs,
            drained = true,
        )

        is AnnouncerEvent.InputDelta -> when {
            state.closed -> noChange(state)
            else -> settle(state.copy(lastInputDeltaAtMs = event.nowMs), event.nowMs)
        }

        is AnnouncerEvent.QuietTimerFired -> {
            if (state.closed) noChange(state) else settle(state, event.nowMs)
        }

        is AnnouncerEvent.BlockedWatchdogFired -> reduceBlockedWatchdog(state, event.nowMs)
        is AnnouncerEvent.SendReturned -> reduceSendReturned(state, event)
    }

    private fun reducePlaybackEvent(
        state: AnnouncerState,
        generation: Long,
        nowMs: Long,
        drained: Boolean,
    ): AnnouncerTransition {
        if (state.closed) return noChange(state)
        if (generation < state.playback.generation) {
            return stalePlaybackEvent(state, generation)
        }
        return settle(
            state.copy(playback = PlaybackGate(generation = generation, drained = drained)),
            nowMs,
        )
    }

    private fun reduceDrainStarted(
        state: AnnouncerState,
        event: AnnouncerEvent.PlaybackDrainStarted,
    ): AnnouncerTransition {
        if (state.closed) return noChange(state)
        if (event.generation < state.playback.generation) {
            return stalePlaybackEvent(state, event.generation)
        }
        return AnnouncerTransition(
            state,
            listOf(
                AnnouncerEffect.Diagnostic(
                    name = "hermes_announcement_playback_drain_started",
                    detail = "generation=${event.generation}",
                )
            ),
        )
    }

    private fun stalePlaybackEvent(state: AnnouncerState, generation: Long) = AnnouncerTransition(
        state,
        listOf(
            AnnouncerEffect.Diagnostic(
                name = "hermes_announcement_stale_playback_event",
                detail = "generation=$generation, current=${state.playback.generation}",
            )
        ),
    )

    private fun reduceBlockedWatchdog(state: AnnouncerState, nowMs: Long): AnnouncerTransition {
        if (state.closed) return noChange(state)
        val scheduledAt = state.blockedWatchdogAtMs ?: return noChange(state)
        if (nowMs < scheduledAt) {
            return AnnouncerTransition(
                state,
                listOf(AnnouncerEffect.StartBlockedWatchdog(scheduledAt - nowMs)),
            )
        }
        if (state.inFlight != null || state.pendingJobs.isEmpty() || state.bridgeSessionId == null) {
            return AnnouncerTransition(
                state.copy(blockedWatchdogAtMs = null),
                listOf(AnnouncerEffect.CancelBlockedWatchdog),
            )
        }

        // A watchdog is observability only. Even when its clock sample shows that all
        // gates are currently safe (for example, a quiet timer event was lost), it must
        // fail closed and wait for an ordinary boundary event to call settle().
        val quietRemainingMs = quietRemainingMs(state, nowMs)
        val head = requireNotNull(state.pendingJobs.first().head())
        return AnnouncerTransition(
            state.copy(blockedWatchdogAtMs = nowMs + blockedWatchdogMs),
            listOf(
                AnnouncerEffect.Diagnostic(
                    name = "hermes_announcement_blocked_watchdog",
                    detail = "intent=${head.label()}, gemini=${state.geminiTurn}, " +
                        "playbackGeneration=${state.playback.generation}, " +
                        "playbackDrained=${state.playback.drained}, quietRemainingMs=$quietRemainingMs",
                ),
                AnnouncerEffect.StartBlockedWatchdog(blockedWatchdogMs),
            ),
        )
    }

    private fun reduceSendReturned(
        state: AnnouncerState,
        event: AnnouncerEvent.SendReturned,
    ): AnnouncerTransition {
        val intent = state.inFlight ?: return noChange(state)
        val cleared = state.copy(
            inFlight = null,
            geminiTurn = state.geminiTurn.onSendReturned(event.outcome),
        )
        return when (event.outcome) {
            AnnouncementSendOutcome.Sent -> {
                if (cleared.closed) noChange(cleared) else settle(cleared, event.nowMs)
            }

            AnnouncementSendOutcome.Failed -> {
                val settled = if (cleared.closed) noChange(cleared) else settle(cleared, event.nowMs)
                AnnouncerTransition(settled.state, fallbackOrDrop(intent) + settled.effects)
            }

            AnnouncementSendOutcome.Skipped -> {
                if (cleared.closed) noChange(cleared) else settle(cleared, event.nowMs)
            }
        }
    }

    private fun GeminiTurnGate.onActivity(): GeminiTurnGate = when (this) {
        GeminiTurnGate.Idle -> GeminiTurnGate.Active
        is GeminiTurnGate.SendReserved -> copy(activityObserved = true)
        GeminiTurnGate.Active -> this
    }

    private fun GeminiTurnGate.onComplete(): GeminiTurnGate = when (this) {
        GeminiTurnGate.Idle -> this
        is GeminiTurnGate.SendReserved -> copy(completed = true)
        GeminiTurnGate.Active -> GeminiTurnGate.Idle
    }

    private fun GeminiTurnGate.onSendReturned(outcome: AnnouncementSendOutcome): GeminiTurnGate =
        when (this) {
            GeminiTurnGate.Idle -> this
            GeminiTurnGate.Active -> this
            is GeminiTurnGate.SendReserved -> when (outcome) {
                AnnouncementSendOutcome.Sent -> if (completed) GeminiTurnGate.Idle else GeminiTurnGate.Active
                AnnouncementSendOutcome.Failed,
                AnnouncementSendOutcome.Skipped,
                    -> if (activityObserved && !completed) GeminiTurnGate.Active else GeminiTurnGate.Idle
            }
        }

    private fun enqueueIntent(
        state: AnnouncerState,
        intent: AnnouncementIntent,
    ): AnnouncerTransition {
        val key = intent.jobKey()
        return when (intent) {
            is AnnouncementIntent.StillWorking -> enqueueProgress(state, key, intent)
            is AnnouncementIntent.Completion,
            is AnnouncementIntent.Terminal,
                -> enqueueFinal(state, key, intent)
        }
    }

    private fun enqueueProgress(
        state: AnnouncerState,
        key: AnnouncementJobKey,
        intent: AnnouncementIntent.StillWorking,
    ): AnnouncerTransition {
        if (key in state.finalizedJobKeys) {
            return AnnouncerTransition(
                state,
                listOf(
                    AnnouncerEffect.Diagnostic(
                        "hermes_announcement_progress_ignored_after_final",
                        intent.label(),
                    )
                ),
            )
        }
        val index = state.pendingJobs.indexOfFirst { it.key == key }
        if (index < 0) {
            return AnnouncerTransition(
                state.copy(pendingJobs = state.pendingJobs + PendingAnnouncementJob(key, progress = intent)),
                emptyList(),
            )
        }
        val existing = state.pendingJobs[index]
        val updated = state.pendingJobs.toMutableList().apply {
            this[index] = existing.copy(progress = intent)
        }
        val effects = if (existing.progress == null) {
            emptyList()
        } else {
            listOf(
                AnnouncerEffect.Diagnostic(
                    "hermes_announcement_progress_replaced",
                    intent.label(),
                )
            )
        }
        return AnnouncerTransition(state.copy(pendingJobs = updated), effects)
    }

    private fun enqueueFinal(
        state: AnnouncerState,
        key: AnnouncementJobKey,
        intent: AnnouncementIntent,
    ): AnnouncerTransition {
        val index = state.pendingJobs.indexOfFirst { it.key == key }
        val pendingJobs = if (index < 0) {
            state.pendingJobs + PendingAnnouncementJob(key = key, final = intent)
        } else {
            state.pendingJobs.toMutableList().apply {
                val existing = this[index]
                this[index] = existing.copy(
                    progress = existing.progress?.copy(allowTerminalRecord = true),
                    final = intent,
                )
            }
        }
        return AnnouncerTransition(
            state.copy(
                pendingJobs = pendingJobs,
                finalizedJobKeys = state.finalizedJobKeys + key,
            ),
            emptyList(),
        )
    }

    private fun AnnouncerTransition.settleAt(nowMs: Long): AnnouncerTransition {
        val settled = settle(state, nowMs)
        return AnnouncerTransition(settled.state, effects + settled.effects)
    }

    private fun drainOnClose(state: AnnouncerState): AnnouncerTransition {
        if (state.closed) return noChange(state)
        val effects = mutableListOf<AnnouncerEffect>(
            AnnouncerEffect.CancelQuietTimer,
            AnnouncerEffect.CancelBlockedWatchdog,
        )
        state.pendingJobs.flatMap(PendingAnnouncementJob::flatten).forEach {
            effects += fallbackOrDrop(it)
        }
        return AnnouncerTransition(
            state.copy(
                pendingJobs = emptyList(),
                blockedWatchdogAtMs = null,
                closed = true,
            ),
            effects,
        )
    }

    private fun fallbackOrDrop(intent: AnnouncementIntent): List<AnnouncerEffect> = when (intent) {
        is AnnouncementIntent.Completion,
        is AnnouncementIntent.Terminal,
            -> listOf(AnnouncerEffect.FallbackToText(intent))

        is AnnouncementIntent.StillWorking -> listOf(
            AnnouncerEffect.Diagnostic(
                "hermes_announcement_dropped_bridge_unavailable",
                intent.label(),
            )
        )
    }

    private fun noChange(state: AnnouncerState) = AnnouncerTransition(state, emptyList())

    private fun settle(
        initial: AnnouncerState,
        nowMs: Long,
    ): AnnouncerTransition {
        var state = initial
        val effects = mutableListOf<AnnouncerEffect>()

        if (state.closed || state.inFlight != null) return AnnouncerTransition(state, effects)

        if (state.pendingJobs.isEmpty()) {
            if (state.blockedWatchdogAtMs != null) {
                state = state.copy(blockedWatchdogAtMs = null)
                effects += AnnouncerEffect.CancelBlockedWatchdog
            }
            return AnnouncerTransition(state, effects)
        }

        if (state.bridgeSessionId == null) {
            state.pendingJobs.flatMap(PendingAnnouncementJob::flatten).forEach {
                effects += fallbackOrDrop(it)
            }
            if (state.blockedWatchdogAtMs != null) {
                effects += AnnouncerEffect.CancelBlockedWatchdog
            }
            return AnnouncerTransition(
                state.copy(pendingJobs = emptyList(), blockedWatchdogAtMs = null),
                effects,
            )
        }

        val head = requireNotNull(state.pendingJobs.first().head())
        val quietRemainingMs = quietRemainingMs(state, nowMs)
        val blocked = state.geminiTurn != GeminiTurnGate.Idle ||
            !state.playback.drained ||
            quietRemainingMs > 0L

        if (blocked) {
            if (state.blockedWatchdogAtMs == null) {
                state = state.copy(blockedWatchdogAtMs = nowMs + blockedWatchdogMs)
                effects += AnnouncerEffect.StartBlockedWatchdog(blockedWatchdogMs)
            }
            if (quietRemainingMs > 0L) {
                effects += AnnouncerEffect.StartQuietTimer(quietRemainingMs)
            }
            return AnnouncerTransition(state, effects)
        }

        if (state.blockedWatchdogAtMs != null) {
            effects += AnnouncerEffect.CancelBlockedWatchdog
        }
        effects += AnnouncerEffect.CancelQuietTimer
        effects += AnnouncerEffect.Diagnostic(
            name = "hermes_announcement_released_safe_boundary",
            detail = head.label(),
        )
        effects += AnnouncerEffect.Send(head, requireNotNull(state.bridgeSessionId))

        val firstJob = state.pendingJobs.first()
        val remainder = when {
            firstJob.progress != null -> firstJob.copy(progress = null)
            else -> firstJob.copy(final = null)
        }
        val pendingJobs = if (remainder.head() == null) {
            state.pendingJobs.drop(1)
        } else {
            listOf(remainder) + state.pendingJobs.drop(1)
        }
        return AnnouncerTransition(
            state.copy(
                geminiTurn = GeminiTurnGate.SendReserved(),
                pendingJobs = pendingJobs,
                inFlight = head,
                blockedWatchdogAtMs = null,
            ),
            effects,
        )
    }

    private fun quietRemainingMs(state: AnnouncerState, nowMs: Long): Long =
        state.lastInputDeltaAtMs
            ?.let { (quietWindowMs - (nowMs - it)).coerceAtLeast(0L) }
            ?: 0L
}
