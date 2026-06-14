package me.rerere.rikkahub.data.ai.agentevent

/**
 * The conversation-turn state the agent-event drain gates on (issue #290 state machine). This is a
 * projection of the live `ChatService` session state onto exactly the distinctions the drain
 * policy needs — nothing finer.
 *
 * IDLE is the only state a drain may run in. An approval pause ([PAUSED_FOR_APPROVAL]) is a
 * SEPARATE non-idle state on purpose: it has no active provider collection (the generation job is
 * not running) yet the turn still owns a pending step, so gating on the generation job alone would
 * wrongly admit a drain there. That is the exact first-break point the proposal names for
 * IDLE_GATING, hence a dedicated state rather than folding it into [IDLE].
 */
enum class TurnGateState {
    /** No active generation and no pending tool approval — the only drainable state. */
    IDLE,

    /** A provider collection is in flight for this conversation. */
    GENERATING,

    /** A tool approval is outstanding; the turn is paused waiting for the user, not finished. */
    PAUSED_FOR_APPROVAL,

    /** The turn is being torn down by a user stop (an abandoned turn, never a turn-end). */
    STOPPING,
}

/**
 * PURE delivery-decision logic for the agent-event queue (issue #290). Extracted to file level —
 * the SAME functions the `ChatService` drain calls AND the property suite exercises — so CI's JVM
 * gate pins the queue's correctness without a device/Room/network runtime (the
 * StreamingUiCoalescer / sequenceTurnEnd precedent).
 *
 * This object owns no state; it answers two questions:
 *  - [canDrain]: may a drain run right now? (NO_DOUBLE_GENERATION + IDLE_GATING)
 *  - [nextDelivery]: which pending event drains first? (FIFO_PER_CONVERSATION)
 */
object AgentEventQueueReducer {

    /**
     * Whether the queue may drain in [state] (NO_DOUBLE_GENERATION + IDLE_GATING). True ONLY for
     * [TurnGateState.IDLE]: a drain in any other state would either supersede a live job
     * ([GENERATING][TurnGateState.GENERATING]), deliver into a turn the user owns the next step of
     * ([PAUSED_FOR_APPROVAL][TurnGateState.PAUSED_FOR_APPROVAL]), or deliver into an abandoned turn
     * ([STOPPING][TurnGateState.STOPPING] — the keep-queued rule, productDecision #4).
     *
     * Crucially this gates on BOTH the active job and a pending approval, because an approval pause
     * has no active provider collection yet still owns a pending step.
     */
    fun canDrain(state: TurnGateState): Boolean = state == TurnGateState.IDLE

    /**
     * The next event to deliver from [pending] (FIFO_PER_CONVERSATION): the one with the smallest
     * [AgentEventDelivery.enqueueSeq]. Returns null for an empty queue (an empty-queue drain is a
     * no-op). Ordering is derived from the stable enqueue cursor only — never from insertion order
     * of the input list or any timestamp.
     */
    fun nextDelivery(pending: List<AgentEventDelivery>): AgentEventDelivery? =
        pending.minByOrNull { it.enqueueSeq }
}

/**
 * The minimal projection of a pending event the [AgentEventQueueReducer] orders by. Decoupled from
 * the Room entity so the pure reducer (and its tests) carry no Android/Room dependency.
 */
data class AgentEventDelivery(
    val id: String,
    val enqueueSeq: Long,
)
