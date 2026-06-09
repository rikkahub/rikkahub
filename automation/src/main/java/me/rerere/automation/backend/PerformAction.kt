package me.rerere.automation.backend

/**
 * What [AutomationBackend.perform] dispatches — the backend-facing act vocabulary (#198 slice 8/9).
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

    /**
     * Set the text of an editable node (#198 slice 9, the input sink). A SEPARATE variant rather than
     * a [NodeActionKind] because it carries a String [text] payload a bare action enum cannot, and
     * keeping it off [Node] leaves the slice-8 scroll dispatch (and its byte-for-byte equality tests)
     * untouched. [stateSeq] + [tid] are the same coordinate-free addressing as [Node]: the backend
     * re-walks to the [tid]-th projected node and replaces its text (ACTION_SET_TEXT), and re-verifies
     * [stateSeq] at dispatch exactly as [Node] does (I-act-1 / MR3 — the grounding must not have moved).
     */
    data class SetText(val stateSeq: Long, val tid: Int, val text: String) : PerformAction
}

/**
 * Node action kinds wired in slice 8 (lowest-risk nav). Slice 9's set_text travels on the dedicated
 * [PerformAction.SetText] variant (it needs a String payload), not here; slice 10 adds `CLICK` — the
 * vocabulary grows additively, no kernel change (the verb/sink mapping lives in the core).
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
