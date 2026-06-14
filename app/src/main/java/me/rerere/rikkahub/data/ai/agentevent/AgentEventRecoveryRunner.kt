package me.rerere.rikkahub.data.ai.agentevent

import android.util.Log
import kotlin.uuid.Uuid

/**
 * The cold-start replay pass for the agent-event queue (issue #290), composed beside the existing
 * task startup-recovery runner and invoked once from RikkaHubApp.onCreate.
 *
 * Pending events survive a process kill in Room (SURVIVES_RESTART). On a cold start this scans the
 * store for conversations that still hold PENDING events and asks ChatService to drain each through
 * the SAME idle-gated `claimAndAppendAndConsume` path the live turn-end drain uses — [drainIfIdle] is
 * bound to `ChatService.maybeDrainAgentEventsWhenIdle` at the composition root. The "central race
 * rule" holds: whichever drain (this replay or a live turn-end) reaches a row first wins the single
 * transactional claim; the other is a no-op — so replay is AT_MOST_ONCE and never double-delivers.
 * The drain is idle-gated, so a conversation that is somehow already generating is left to its own
 * turn-end drain (NO_DOUBLE_GENERATION).
 *
 * Failures are swallowed and logged so a recovery hiccup can never block the UI from coming up,
 * mirroring the existing startup-recovery posture.
 */
class AgentEventRecoveryRunner(
    private val store: AgentEventStore,
    private val drainIfIdle: (Uuid) -> Unit,
) {
    /**
     * Run the cold-start replay once: drain every conversation that still holds PENDING events
     * through the idle-gated drain. Returns the number of conversations replayed (for logging/tests).
     */
    suspend fun runStartupReplay(): Int {
        val pendingConversations = runCatching { store.conversationsWithPending() }
            .onFailure { Log.e(TAG, "agent-event replay scan failed", it) }
            .getOrDefault(emptyList())
        if (pendingConversations.isNotEmpty()) {
            Log.i(
                TAG,
                "agent-event replay: draining ${pendingConversations.size} conversation(s) with pending events",
            )
            pendingConversations.forEach { conversationId ->
                runCatching { drainIfIdle(conversationId) }
                    .onFailure { Log.e(TAG, "agent-event replay drain failed for $conversationId", it) }
            }
        }
        return pendingConversations.size
    }

    private companion object {
        const val TAG = "AgentEventRecovery"
    }
}
