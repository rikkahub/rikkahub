package me.rerere.automation.backend

import me.rerere.automation.observe.TargetBinding

/**
 * What [AutomationBackend.perform] dispatches — the backend-facing act vocabulary. Deliberately
 * coordinate-free AND stateSeq-free: a node action names a decision-time [TargetBinding] (the
 * "eyes-open" hybrid tap design), never a screen point and never a blind `(stateSeq, tid)` token.
 *
 * The backend re-resolves the [TargetBinding] against a FRESH live capture and dispatches ONLY when
 * exactly one node strictly matches it — atomically, under the same capture/dispatch lock. This is
 * the load-bearing change from the old `(stateSeq, tid)` dispatch: a same-shaped node that re-flowed
 * into a different window/path, or a same-label replacement, is refused (the binding's strict match
 * fails), while benign status-bar/SystemUI churn (which never matches an app binding) no longer stale
 * a targeted dispatch. The model never sees the binding or any of its fields (the renderer emits the
 * compact text table only); it is built in the pure core from the grounded target.
 */
sealed interface PerformAction {
    /**
     * A node-targeted action. [binding] is the strict structural identity the backend re-resolves
     * against a fresh capture; [kind] is the verb (a scroll or CLICK). The backend walks its live tree,
     * re-projects each candidate, and dispatches [kind] on the unique node whose [TargetBinding] is
     * equal — or refuses (zero/multiple matches) without mutating anything.
     */
    data class Node(
        val binding: TargetBinding,
        val kind: NodeActionKind,
        val allowedPackages: Set<String>,
        /** YOLO host policy: when true the replay walk may also match host-package windows. */
        val includeHost: Boolean = false,
        /**
         * CLICK only: dispatch as a real touch (`dispatchGesture`) at the resolved node's bounds rather
         * than `ACTION_CLICK`, for views whose navigation responds only to raw touch. Ignored for scrolls.
         */
        val gesture: Boolean = false,
    ) : PerformAction

    /** A global navigation action — BACK / HOME / RECENTS. No node target (performGlobalAction). */
    data class Global(val nav: GlobalNav) : PerformAction

    /**
     * Set the text of an editable node (the input sink). A SEPARATE variant rather than a
     * [NodeActionKind] because it carries a String [text] payload a bare action enum cannot. [binding]
     * is the SAME strict structural identity as [Node]'s (never carrying the requested text or the
     * field's editable value — a binding is identity, not payload); the backend re-resolves it against
     * a fresh capture and dispatches ACTION_SET_TEXT on the unique matching node.
     */
    data class SetText(
        val binding: TargetBinding,
        val text: String,
        val allowedPackages: Set<String>,
        /** YOLO host policy: when true the replay walk may also match host-package windows. */
        val includeHost: Boolean = false,
        /**
         * After ACTION_SET_TEXT lands, also fire the field's IME editor action (ACTION_IME_ENTER,
         * API 30+) on the same node — the keyboard Search/Go/Send/Done button. For apps whose
         * live-search controller ignores a programmatic set (no per-keystroke event) or that need an
         * explicit submit. Best-effort: a node without an IME action just no-ops the follow-on.
         */
        val submit: Boolean = false,
    ) : PerformAction
}

/**
 * Node action kinds. SCROLL_FORWARD/SCROLL_BACKWARD ride [PerformAction.Node] as a payload-less verb;
 * CLICK rides the SAME variant (a general tap carries no String payload). set_text still travels on
 * the dedicated [PerformAction.SetText] variant (it needs a String payload). The vocabulary grows
 * additively, no kernel change (the verb/sink mapping lives in the core: CLICK ⇒ Verb.TAP with a sink
 * only when the resolved target is submit-class, derived from this kind).
 */
enum class NodeActionKind {
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    CLICK,
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
