package me.rerere.automation.backend

/**
 * What [AutomationBackend.perform] dispatches — the backend-facing act vocabulary (#198 slice 8).
 * Deliberately coordinate-free: a node action names a `(stateSeq, tid)` the core already resolved and
 * asserted, never a screen point (design D1 — `performAction`, not `dispatchGesture`). A real backend
 * maps `(stateSeq, tid)` to its live node by replaying the SAME projection order the snapshot used.
 */
sealed interface PerformAction {
    /**
     * A node-targeted action. [stateSeq] + [tid] identify the element within the snapshot the model
     * acted on; the backend re-walks its live tree to the [tid]-th projected node and performs [kind].
     */
    data class Node(val stateSeq: Long, val tid: Int, val kind: NodeActionKind) : PerformAction

    /** A global navigation action — BACK / HOME / RECENTS. No node target (performGlobalAction). */
    data class Global(val nav: GlobalNav) : PerformAction
}

/**
 * Node action kinds wired in slice 8 (lowest-risk nav). Slice 9 adds `SET_TEXT`, slice 10 adds
 * `CLICK` — the enum grows additively, no kernel change (the verb/sink mapping lives in the core).
 */
enum class NodeActionKind {
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

/**
 * Global navigation targets (design Q2: `ui_global` is a budgeted [me.rerere.automation.cap.Sink]
 * `GLOBAL_NAV`, but NOT a dangerous sink — nav is reversible/non-committing, so no out-of-band
 * confirm). BACK/HOME/RECENTS map to `performGlobalAction` on the real backend.
 */
enum class GlobalNav {
    BACK,
    HOME,
    RECENTS,
}
