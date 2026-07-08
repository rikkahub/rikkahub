package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.voiceagent.telemetry.HermesTelemetryLogSanitizer

/**
 * A bridge attachment owned by the announcer. [active] is flipped off when a newer
 * attachment replaces this one, so a suspended send can detect supersession.
 */
class HermesBridgeAttachment internal constructor(
    val bridge: HermesSessionBridge,
    val sessionId: Long,
) {
    @Volatile
    internal var active: Boolean = true
}

/**
 * The single owner of proactive Hermes announcements AND of bridge attachment.
 * One consumer coroutine drains announcement events through the pure
 * [AnnouncerReducer]; effects execute in order with per-effect failure
 * containment (Phase 3 contract). Tool responses (queued acks, cancel responses)
 * are NOT announcements: callers send those directly via [currentAttachment].
 *
 * Default-bridge policy (moved from the coordinator, preserved exactly): the
 * default bridge attaches via [attachDefaultIfNeeded] and re-attaches after a
 * detach only while no scoped bridge has ever been attached and not closed.
 */
class HermesAnnouncer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val queueStore: HermesQueueStore,
    private val telemetry: HermesJobTelemetry,
    private val defaultBridge: (() -> HermesSessionBridge)? = null,
    private val bridgeSendTimeoutMs: Long = DEFAULT_BRIDGE_SEND_TIMEOUT_MS,
    quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
    maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val reducer = AnnouncerReducer(quietWindowMs = quietWindowMs, maxHoldMs = maxHoldMs)
    private val events = Channel<AnnouncerEvent>(Channel.UNLIMITED)
    private val lock = Any()
    private var attachment: HermesBridgeAttachment? = null
    private var scopedBridgeEverAttached = false
    private var closed = false
    private var defaultInstance: HermesSessionBridge? = null

    // Drain barrier (Task 9 review, close-loss trace): awaitClosed() registers a deferred
    // here under `lock` keyed by a fresh id and posts its own DrainMarker(id); the consumer
    // completes exactly that ack when it reaches the marker. Tying the ack to its own marker
    // (not to any Close) matters: close() posts an EARLIER Close than tail intents a stalled
    // consumer still has queued, so completing on any Close could release the barrier while
    // those tail intents are still undrained.
    private var nextDrainId = 0L
    private val drainAcks = mutableMapOf<Long, CompletableDeferred<Unit>>()

    private var state = AnnouncerState()
    private var quietTimer: Job? = null
    private var holdTimer: Job? = null

    // @Volatile for the same reason as JobActor.consumer: drain helpers may read it
    // from another thread right after construction.
    @Volatile
    var consumer: Job? = null
        private set

    init {
        consumer = scope.launch(dispatcher) {
            for (event in events) {
                val transition = reducer.reduce(state, event)
                state = transition.state
                transition.effects.forEach { effect ->
                    // Effect-failure containment (Phase 3 contract): a throwing store or
                    // bridge must never kill the consumer. State was already committed by
                    // the reducer; log and keep draining. CancellationException is
                    // coroutine cancellation, not an effect failure.
                    try {
                        execute(effect)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        telemetry.diagnostic(
                            "announcer_effect_failed",
                            "effect=${effect::class.simpleName}, error=" +
                                HermesTelemetryLogSanitizer.failureMessage(e.message ?: e.javaClass.simpleName),
                        )
                        // A failed Send effect still owes the reducer a SendReturned,
                        // otherwise inFlight wedges the queue forever.
                        if (effect is AnnouncerEffect.Send) {
                            events.trySend(
                                AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Failed, nowMs())
                            )
                        }
                    }
                }
                if (event is AnnouncerEvent.DrainMarker) {
                    // Everything posted before this marker (including the Close that drains
                    // the queue) has now been processed in FIFO order.
                    synchronized(lock) { drainAcks.remove(event.id) }?.complete(Unit)
                }
            }
        }
    }

    // --- bridge registry ---

    fun attachScoped(bridge: HermesSessionBridge, sessionId: Long) {
        val attached: Boolean
        synchronized(lock) {
            if (closed) {
                attached = false
            } else {
                if (sessionId != HermesJobManager.UNBOUND_BRIDGE_SESSION_ID) {
                    scopedBridgeEverAttached = true
                }
                attachment?.active = false
                attachment = HermesBridgeAttachment(bridge = bridge, sessionId = sessionId)
                attached = true
            }
        }
        if (attached) {
            events.trySend(AnnouncerEvent.BridgeAttached(sessionId, nowMs()))
            enqueueReplayIntents()
        }
    }

    fun detachScoped(bridge: HermesSessionBridge) {
        val detached: Boolean
        val fallbackToDefault: Boolean
        synchronized(lock) {
            val current = attachment
            detached = current?.bridge === bridge
            if (detached) {
                current!!.active = false
                attachment = null
            }
            fallbackToDefault = detached && !closed && !scopedBridgeEverAttached && defaultBridge != null
        }
        if (!detached) return
        if (fallbackToDefault) {
            // Design refinement 2: no transient detached gap — the default attach event
            // replaces the detach event so queued intents survive the handoff.
            attachDefaultIfNeeded()
        } else {
            events.trySend(AnnouncerEvent.BridgeDetached(nowMs()))
        }
    }

    fun attachDefaultIfNeeded() {
        val provider = defaultBridge ?: return
        val attached: Boolean
        synchronized(lock) {
            if (closed || scopedBridgeEverAttached || attachment != null) {
                attached = false
            } else {
                val default = defaultInstance ?: provider().also { defaultInstance = it }
                attachment = HermesBridgeAttachment(
                    bridge = default,
                    sessionId = HermesJobManager.UNBOUND_BRIDGE_SESSION_ID,
                )
                attached = true
            }
        }
        if (attached) {
            events.trySend(
                AnnouncerEvent.BridgeAttached(HermesJobManager.UNBOUND_BRIDGE_SESSION_ID, nowMs())
            )
            enqueueReplayIntents()
        }
    }

    fun currentAttachment(): HermesBridgeAttachment? = synchronized(lock) { attachment }

    fun isCurrent(attachment: HermesBridgeAttachment): Boolean = synchronized(lock) {
        attachment.active &&
            this.attachment?.bridge === attachment.bridge &&
            this.attachment?.sessionId == attachment.sessionId
    }

    // --- intents and conversation-activity taps ---

    fun enqueueCompletion(callId: String, jobId: String?) {
        events.trySend(
            AnnouncerEvent.IntentEnqueued(AnnouncementIntent.Completion(callId, jobId), nowMs())
        )
    }

    fun enqueueTerminal(callId: String, jobId: String?) {
        events.trySend(
            AnnouncerEvent.IntentEnqueued(AnnouncementIntent.Terminal(callId, jobId), nowMs())
        )
    }

    fun enqueueStillWorking(callId: String, jobId: String) {
        events.trySend(
            AnnouncerEvent.IntentEnqueued(AnnouncementIntent.StillWorking(callId, jobId), nowMs())
        )
    }

    fun onInputTranscriptDelta() {
        events.trySend(AnnouncerEvent.InputDelta(nowMs()))
    }

    fun onGenerationComplete() {
        events.trySend(AnnouncerEvent.GenerationComplete(nowMs()))
    }

    fun onAssistantAudioActive(active: Boolean) {
        events.trySend(AnnouncerEvent.AudioActiveChanged(active, nowMs()))
    }

    fun close() {
        synchronized(lock) {
            closed = true
            attachment?.active = false
            attachment = null
        }
        events.trySend(AnnouncerEvent.Close)
    }

    /**
     * Drain barrier: suspends until every event enqueued before this call has been processed
     * by the consumer, so a tail completion/terminal cannot be lost to a racing scope cancel.
     * Closes the announcer (idempotent), then posts its OWN [AnnouncerEvent.DrainMarker] and
     * awaits it; the consumer completes exactly this ack when it reaches the marker. FIFO
     * delivery guarantees all events posted before the marker — including tail intents posted
     * after an earlier Close — were processed (sent or fallback-to-text) first.
     */
    suspend fun awaitClosed() {
        close()
        val ack = CompletableDeferred<Unit>()
        val id = synchronized(lock) {
            val id = nextDrainId++
            drainAcks[id] = ack
            id
        }
        events.trySend(AnnouncerEvent.DrainMarker(id))
        ack.await()
    }

    /** Replay unannounced terminal results through the ordinary intent queue. */
    private fun enqueueReplayIntents() {
        queueStore.unannouncedTerminalRecords().forEach { record ->
            if (record.status == HermesQueueStatus.Complete && record.answer != null) {
                enqueueCompletion(callId = record.callId, jobId = record.jobId)
            } else if (record.status != HermesQueueStatus.Complete) {
                enqueueTerminal(callId = record.callId, jobId = record.jobId)
            }
        }
    }

    // --- effect executor ---

    private suspend fun execute(effect: AnnouncerEffect) {
        when (effect) {
            is AnnouncerEffect.StartQuietTimer -> {
                quietTimer?.cancel()
                quietTimer = scope.launch(dispatcher) {
                    delay(effect.delayMs)
                    events.trySend(AnnouncerEvent.QuietTimerFired(nowMs()))
                }
            }

            AnnouncerEffect.CancelQuietTimer -> quietTimer?.cancel()

            is AnnouncerEffect.StartHoldDeadline -> {
                holdTimer?.cancel()
                holdTimer = scope.launch(dispatcher) {
                    delay(effect.delayMs)
                    events.trySend(AnnouncerEvent.HoldDeadlineFired(nowMs()))
                }
            }

            AnnouncerEffect.CancelHoldDeadline -> holdTimer?.cancel()

            is AnnouncerEffect.Send -> executeSend(effect.intent)

            is AnnouncerEffect.FallbackToText -> when (val intent = effect.intent) {
                is AnnouncementIntent.Completion ->
                    queueStore.appendVisibleResultMessageIfNeeded(callId = intent.callId, jobId = intent.jobId)
                is AnnouncementIntent.Terminal ->
                    queueStore.appendVisibleResultMessageIfNeeded(callId = intent.callId, jobId = intent.jobId)
                is AnnouncementIntent.StillWorking -> Unit // reducer never emits this; keep total anyway
            }

            is AnnouncerEffect.Diagnostic -> telemetry.diagnostic(effect.name, effect.detail)
        }
    }

    private suspend fun executeSend(intent: AnnouncementIntent) {
        val current = currentAttachment()
        if (current == null) {
            events.trySend(AnnouncerEvent.SendReturned(AnnouncementSendOutcome.Failed, nowMs()))
            return
        }
        val outcome = when (intent) {
            is AnnouncementIntent.Completion -> sendCompletion(intent, current)
            is AnnouncementIntent.Terminal -> sendTerminal(intent, current)
            is AnnouncementIntent.StillWorking -> sendStillWorking(intent, current)
        }
        events.trySend(AnnouncerEvent.SendReturned(outcome, nowMs()))
    }

    private suspend fun sendCompletion(
        intent: AnnouncementIntent.Completion,
        current: HermesBridgeAttachment,
    ): AnnouncementSendOutcome {
        val record = queueStore.latestRecord(callId = intent.callId, jobId = intent.jobId)
        if (record?.status != HermesQueueStatus.Complete || record.resultAnnounced || record.answer == null) {
            return AnnouncementSendOutcome.Skipped
        }
        if (!isCurrent(current)) return AnnouncementSendOutcome.Skipped
        val sent = try {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                current.bridge.sendCompletionFollowUp(
                    callId = intent.callId,
                    prompt = record.prompt,
                    answer = requireNotNull(record.answer),
                    sessionId = current.sessionId,
                )
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        return if (sent) {
            // The RPC succeeded: the outcome is Sent no matter what the bookkeeping does.
            // A failed mark leaves the record unannounced -> bounded re-send on the next
            // attach, never a same-cycle duplicate text fallback.
            try {
                queueStore.markResultAnnounced(callId = intent.callId, jobId = intent.jobId)
                telemetry.followUpSent(
                    callId = intent.callId,
                    jobId = intent.jobId,
                    prompt = record.prompt,
                    answer = requireNotNull(record.answer),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                telemetry.diagnostic(
                    "announcer_mark_failed",
                    "callId=${intent.callId}, error=" +
                        HermesTelemetryLogSanitizer.failureMessage(e.message ?: e.javaClass.simpleName),
                )
            }
            AnnouncementSendOutcome.Sent
        } else {
            AnnouncementSendOutcome.Failed
        }
    }

    private suspend fun sendTerminal(
        intent: AnnouncementIntent.Terminal,
        current: HermesBridgeAttachment,
    ): AnnouncementSendOutcome {
        val record = queueStore.latestRecord(callId = intent.callId, jobId = intent.jobId)
        if (
            record == null ||
            record.status == HermesQueueStatus.Complete ||
            !record.status.isTerminal ||
            record.resultAnnounced
        ) {
            return AnnouncementSendOutcome.Skipped
        }
        if (!isCurrent(current)) return AnnouncementSendOutcome.Skipped
        val sent = try {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                current.bridge.sendTerminalFollowUp(
                    callId = intent.callId,
                    prompt = record.prompt,
                    status = record.status,
                    reason = record.error.orEmpty(),
                    sessionId = current.sessionId,
                )
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        return if (sent) {
            // The RPC succeeded: the outcome is Sent no matter what the bookkeeping does.
            try {
                queueStore.markResultAnnounced(callId = intent.callId, jobId = intent.jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                telemetry.diagnostic(
                    "announcer_mark_failed",
                    "callId=${intent.callId}, error=" +
                        HermesTelemetryLogSanitizer.failureMessage(e.message ?: e.javaClass.simpleName),
                )
            }
            AnnouncementSendOutcome.Sent
        } else {
            AnnouncementSendOutcome.Failed
        }
    }

    private suspend fun sendStillWorking(
        intent: AnnouncementIntent.StillWorking,
        current: HermesBridgeAttachment,
    ): AnnouncementSendOutcome {
        val record = queueStore.latestRecord(callId = intent.callId, jobId = intent.jobId)
        if (record == null || record.status.isTerminal || record.stillWorkingAnnounced) {
            return AnnouncementSendOutcome.Skipped
        }
        if (!isCurrent(current)) return AnnouncementSendOutcome.Skipped
        val sent = try {
            withTimeoutOrNull(bridgeSendTimeoutMs) {
                current.bridge.sendStillWorkingUpdate(
                    callId = intent.callId,
                    prompt = record.prompt,
                    sessionId = current.sessionId,
                )
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        return if (sent) {
            // The RPC succeeded: the outcome is Sent no matter what the bookkeeping does.
            try {
                queueStore.markStillWorkingAnnounced(callId = intent.callId, jobId = intent.jobId)
                telemetry.diagnostic(
                    "hermes_still_working_announced",
                    "callId=${intent.callId}, jobId=${intent.jobId}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                telemetry.diagnostic(
                    "announcer_mark_failed",
                    "callId=${intent.callId}, error=" +
                        HermesTelemetryLogSanitizer.failureMessage(e.message ?: e.javaClass.simpleName),
                )
            }
            AnnouncementSendOutcome.Sent
        } else {
            AnnouncementSendOutcome.Failed
        }
    }

    companion object {
        const val DEFAULT_BRIDGE_SEND_TIMEOUT_MS = 5_000L
        const val DEFAULT_QUIET_WINDOW_MS = 2_000L
        const val DEFAULT_MAX_HOLD_MS = 15_000L
    }
}
