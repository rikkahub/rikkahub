package me.rerere.rikkahub.data.ai.agentevent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

private const val TAG = "AgentEventDrain"

/**
 * The durable agent-event drain COORDINATION (issue #290), extracted from ChatService (#360 P4) so the
 * race-relevant orchestration — idle gating, the no-double-generation slot claim, the snapshot-bounded
 * drain loop, and the re-poke — is JVM-unit-testable with fake stores / gates / delivery, without the
 * full ChatService.
 *
 * The per-event DELIVERY ([drainOne]) stays in ChatService and is injected as a callback: it resolves
 * kind-specific tool anchors and CONTINUES the model via the turn runner (`handleMessageComplete`), so it
 * is turn-runner-entangled and extracts cleanly only with P6. Everything the coordinator does around it
 * is preserved here 1:1 from the former inline ChatService methods.
 *
 * Ports (all injected; the coordinator names no ChatService type):
 * @param store the durable queue (enqueue + listPending). Already AT_MOST_ONCE at the store layer.
 * @param scope app-lifetime scope the async drain + re-poke launch on.
 * @param turnGate the live turn-gate projection for [conversationId] (GENERATING / PAUSED_FOR_APPROVAL /
 *   IDLE); [AgentEventQueueReducer.canDrain] gates on it so a drain never supersedes a live turn.
 * @param claimIdleSlot the session's race-safe idle generation-slot claim
 *   ([me.rerere.rikkahub.service.ConversationSession.tryClaimIdleGenerationSlot]); makes the drain visible
 *   to idle-gating + stop/cancel (NO_DOUBLE_GENERATION). Returns true iff this caller won the slot.
 * @param withConversationRef runs [block] off-thread holding a session reference (keeps the session alive
 *   across the async hop), i.e. ChatService.launchWithConversationReference.
 * @param hydrate loads the persisted conversation into a BLANK session before the drain appends (cold-start
 *   replay data-loss guard).
 * @param drainOne delivers (or terminalizes) the single oldest pending event; true iff more may remain.
 * @param signalDrainPass fires after a drain pass completes (ChatService emits generationDoneFlow).
 * @param reportError surfaces a non-cancellation failure as a conversation error (never swallowed).
 */
class AgentEventDrainCoordinator(
    private val store: AgentEventStore,
    private val scope: CoroutineScope,
    private val turnGate: (Uuid) -> TurnGateState,
    private val claimIdleSlot: (Uuid, () -> Job) -> Boolean,
    private val withConversationRef: (Uuid, suspend () -> Unit) -> Unit,
    private val hydrate: suspend (Uuid) -> Unit,
    private val drainOne: suspend (Uuid) -> Boolean,
    private val signalDrainPass: suspend (Uuid) -> Unit,
    private val reportError: (Throwable, Uuid) -> Unit,
) {
    /**
     * Persist an agent-event PENDING, then kick an idle drain. Off the caller's thread (the store + drain
     * are suspend), holding a session reference across the hop. A failure is surfaced, never swallowed.
     */
    fun enqueue(conversationId: Uuid, kind: String, payloadJson: String, dedupeKey: String) {
        withConversationRef(conversationId) {
            val persisted = runCatching {
                store.enqueue(conversationId = conversationId, kind = kind, payloadJson = payloadJson, dedupeKey = dedupeKey)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "enqueueAgentEvent failed", e)
                reportError(e, conversationId)
            }.isSuccess
            if (persisted) maybeDrainWhenIdle(conversationId)
        }
    }

    /**
     * Deliver freshly-enqueued events immediately IFF the conversation is idle, as a TRACKED generation
     * (the slot claim makes it visible to idle-gating + stop/cancel, so no second drain runs concurrently
     * — NO_DOUBLE_GENERATION). When not idle the events stay buffered (PENDING) for the next turn-end.
     * Each pass drains only a bounded snapshot; once the slot clears, re-poke if events remain (each
     * re-poke is its own bounded pass with the slot released between, so the user can interject).
     */
    fun maybeDrainWhenIdle(conversationId: Uuid) {
        if (!AgentEventQueueReducer.canDrain(turnGate(conversationId))) return
        claimIdleSlot(conversationId) {
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    hydrate(conversationId)
                    drainAtTurnEnd(conversationId)
                    signalDrainPass(conversationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "agent-event idle drain failed", e)
                    reportError(e, conversationId)
                }
            }.also { drainJob ->
                drainJob.invokeOnCompletion {
                    scope.launch {
                        if (store.listPending(conversationId).isNotEmpty()) {
                            maybeDrainWhenIdle(conversationId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Drain the SNAPSHOT of events pending NOW, oldest first, one continuation each. The snapshot count
     * BOUNDS the loop: a continuation can enqueue a fresh completion concurrently, but chasing those this
     * pass would not provably terminate — they are delivered by their own poke / the next turn-end (FIFO
     * enqueue order means the oldest `budget` events are exactly those pending at entry).
     */
    suspend fun drainAtTurnEnd(conversationId: Uuid) {
        var budget = store.listPending(conversationId).size
        while (budget-- > 0 && drainOne(conversationId)) {
            // deliver the snapshot, oldest first
        }
    }
}
