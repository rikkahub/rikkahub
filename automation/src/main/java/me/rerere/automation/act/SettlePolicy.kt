package me.rerere.automation.act

/**
 * Pure settle policy for the act path (design §1 step 6 / D3 / property P13). After an act the screen
 * emits content-change events; "settled" means a [quietWindowMs] gap passed with no new event, OR a
 * [hardCapMs] ceiling elapsed — NEVER a fixed sleep (untestable + brittle). This is the offline,
 * deterministic decision function: given the event offsets since the act it returns when settle
 * fires. The real backend implements the equivalent ONLINE debounce over its live event stream;
 * [me.rerere.automation.backend.FakeBackend] settles immediately. Stating it as a pure function makes
 * P13 a hermetic boundary test instead of a brittle wall-clock assertion.
 */
class SettlePolicy(
    val quietWindowMs: Long = 250,
    val hardCapMs: Long = 1500,
) {
    init {
        require(quietWindowMs in 1..hardCapMs) {
            "quietWindowMs ($quietWindowMs) must be in 1..hardCapMs ($hardCapMs)"
        }
    }

    /**
     * The settle offset in ms after the act, given the content-change [eventOffsetsMs] (offsets ≥ 0,
     * any order). With no events settle fires at [quietWindowMs]; each event still inside the pending
     * quiet window pushes settle to `event + quietWindowMs`; an event arriving after a full quiet
     * window already elapsed cannot delay it. The result is always clamped to [hardCapMs].
     *
     * Pure & total: same input ⇒ same output, no clock read. Result is always in
     * `[quietWindowMs, hardCapMs]` — the P13 boundary invariant.
     */
    fun settleOffsetMs(eventOffsetsMs: List<Long>): Long {
        var settleAt = quietWindowMs
        for (e in eventOffsetsMs.asSequence().filter { it >= 0 }.sorted()) {
            if (e >= hardCapMs) break          // an event at/after the cap cannot extend settle past it
            if (e <= settleAt) {
                settleAt = e + quietWindowMs   // event within the pending quiet window ⇒ extend
            } else {
                break                          // a full quiet window already elapsed before this event
            }
        }
        return settleAt.coerceAtMost(hardCapMs)
    }
}
