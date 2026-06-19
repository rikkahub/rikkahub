package me.rerere.automation.backend

/**
 * Pure freshness reducer for the eyes-open UI-automation runtime (spec §8). Decides, given an
 * incoming accessibility [FreshnessEventImpact], whether the runtime should (a) bump its monotonic
 * epoch counter and (b) pulse its settle signal — the two side effects the real Android runtime's
 * `onAccessibilityEvent` performs on every event. Today the runtime applies them UNCONDITIONALLY on
 * every WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED event, which makes the old seq+hash freshness
 * gate refuse a targeted act whenever ANY subscribed window churns (status-bar updates, non-active
 * system-window content changes) — even though the bound app target is untouched. The eyes-open
 * binding dispatch removes that gate, but the unconditional bump still advances `stateSeq` (turning
 * the next observe's stamp into a churn-induced value) and pulses settle (debouncing against noise
 * the act path no longer cares about).
 *
 * This reducer makes the bump/pulse decision PURE and unit-testable: the Android runtime supplies
 * the event's classified identity (window id / package / system flag) plus the active window's
 * identity, and the reducer returns a [FreshnessDecision] without touching any runtime state. The
 * runtime then applies the decision.
 *
 * Rules (spec §8):
 *  - WINDOW_STATE_CHANGED ⇒ always bump + pulse (a structural window change always advances the epoch).
 *  - WINDOW_CONTENT_CHANGED from a FULLY-classified, KNOWN non-active system window (eventSystemWindow
 *    == true, known eventWindowId, known activeWindowId, eventWindowId != activeWindowId, known
 *    eventPackage, known activePackage, eventPackage != activePackage) ⇒ do NOT bump and do NOT pulse:
 *    the change is in a background system window (status bar / notification shade / dialog backing)
 *    that the eyes-open binding will never match against an app binding, so advancing the epoch would
 *    only add churn to the observe stamp.
 *  - WINDOW_CONTENT_CHANGED from the active window OR the foreground package (eventPackage ==
 *    activePackage) ⇒ bump + pulse: this is real content churn the act / observe path must see, even if
 *    the event was (mis)classified as a system window.
 *  - Any UNKNOWN / null classification ⇒ bump + pulse (fail-closed: the freshness source must not be
 *    silently muted when the event identity cannot be classified).
 *
 * Pure & total (module purity I10): no android.*, no I/O, no globals. Same inputs ⇒ identical output.
 */
enum class FreshnessEventKind { WINDOW_STATE_CHANGED, WINDOW_CONTENT_CHANGED }

/**
 * The classified identity of an incoming freshness event + the active window's identity at the same
 * instant. Each field is nullable: a backend that could not classify a field supplies null, which the
 * reducer treats as unknown (fail-closed bump + pulse).
 *
 *  - [kind]: WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED.
 *  - [eventWindowId]: the window id the event was dispatched for (e.g. the AccessibilityEvent's
 *    source window id), or null when unavailable.
 *  - [eventPackage]: the package owning the event's source node, or null when unavailable.
 *  - [eventSystemWindow]: whether the event's source window is a system window, or null when
 *    unclassified.
 *  - [activeWindowId]: the active window's id at the event instant, or null when unavailable.
 *  - [activePackage]: the foreground package at the event instant, or null when unavailable.
 */
data class FreshnessEventImpact(
    val kind: FreshnessEventKind,
    val eventWindowId: Int?,
    val eventPackage: String?,
    val eventSystemWindow: Boolean?,
    val activeWindowId: Int?,
    val activePackage: String?,
)

/**
 * The reducer's decision: whether to bump the monotonic epoch counter and whether to pulse the settle
 * signal. The Android runtime applies both side effects inline today; with the reducer, it applies
 * whichever this decision names.
 */
data class FreshnessDecision(
    val bumpEpoch: Boolean,
    val pulseSettle: Boolean,
)

/**
 * The pure decision function. See the file kdoc for the rules. Not a member so it is reachable from
 * JVM unit tests with zero fixture (a top-level object — the Android runtime calls
 * `FreshnessReducer.decide(...)`).
 */
object FreshnessReducer {
    fun decide(event: FreshnessEventImpact): FreshnessDecision {
        // WINDOW_STATE_CHANGED always advances the epoch and pulses settle: a structural window
        // change (window added/removed/focused) is never benign churn.
        if (event.kind == FreshnessEventKind.WINDOW_STATE_CHANGED) {
            return FreshnessDecision(bumpEpoch = true, pulseSettle = true)
        }

        // WINDOW_CONTENT_CHANGED: the ONLY case we suppress is a FULLY-classified, KNOWN non-active
        // system window — a status-bar / shade / non-active dialog churn that the eyes-open binding will
        // never match against an app binding. Every identity field (system flag, both window ids, both
        // packages) must be KNOWN — a window id is known only when non-null AND non-negative (the
        // framework's -1 sentinel is unknown, never a real id) — AND the event must NOT belong to the
        // foreground package: an event whose package equals the foreground is the app's own content and
        // must bump even if it was (mis)classified as a system window. Any null/negative field or a
        // foreground-package event fails closed to bump + pulse (spec §8 foreground / unknown rules).
        val isNonActiveSystem =
            event.eventSystemWindow == true &&
                event.eventWindowId != null && event.eventWindowId >= 0 &&
                event.activeWindowId != null && event.activeWindowId >= 0 &&
                event.eventWindowId != event.activeWindowId &&
                event.eventPackage != null &&
                event.activePackage != null &&
                event.eventPackage != event.activePackage
        if (isNonActiveSystem) {
            return FreshnessDecision(bumpEpoch = false, pulseSettle = false)
        }

        // Active window / foreground / unknown: bump + pulse (fail-closed on every unclassified case).
        return FreshnessDecision(bumpEpoch = true, pulseSettle = true)
    }
}
