package me.rerere.automation.act

import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.UiSnapshot

/**
 * What the model asks the act path to do (#198 slice 8). Coordinate-free by construction: a targeted
 * act names a [Selector] (resolved against the grounded snapshot, never a coordinate); a global act
 * names only the nav key. The core derives the OCap (verb + sink) from the variant — the model never
 * supplies authority, so a mismatched verb/sink is structurally impossible (design I2).
 */
sealed interface Act {
    /**
     * A node action on a resolved element — the [kind] drives the verb (the model never supplies it,
     * I2). SCROLL_FORWARD/SCROLL_BACKWARD ⇒ `Verb.SCROLL` (viewport movement, no side effect); CLICK ⇒
     * `Verb.TAP` (a general tap, #198 slice 10). A scroll carries no sink. A CLICK carries a sink ONLY
     * when the core classifies the RESOLVED target as submit-class (#198 slice 11): an ordinary tap is
     * verb-gated only (no sink), but a send/pay/checkout-class tap carries `Sink.SUBMIT` — still DERIVED
     * in the core from the resolved target's label/key ([SubmitClassifier]), never model-supplied (I2),
     * and then gated behind an out-of-band confirmation ([ConfirmChannel]). System-UI/permission-dialog
     * and password taps DENY before dispatch via the same target-provenance plumbing as scroll/set_text
     * — observable, never actionable (I-act-3/I8).
     */
    /**
     * [gesture] (CLICK only): dispatch the tap as a real touch at the resolved node's bounds
     * (`dispatchGesture`) instead of the accessibility `ACTION_CLICK`. Some apps wire their navigation
     * to raw touch (an `OnTouchListener`/custom view) and ignore the synthesized click — e.g. opening
     * Instagram's explore search screen. Still coordinate-free from the model: it names a [selector],
     * the backend derives the touch point from the resolved node. Default false keeps the click path.
     */
    data class Targeted(
        val selector: Selector,
        val kind: NodeActionKind,
        val gesture: Boolean = false,
    ) : Act

    /** Global navigation. Maps to `Verb.GLOBAL` + `Sink.GLOBAL_NAV` (budgeted, not dangerous — Q2). */
    data class Global(val nav: GlobalNav) : Act

    /**
     * Set the text of a resolved input field (#198 slice 9, the input sink). Maps to `Verb.SET_TEXT`
     * + `Sink.TYPE_INTO` — the model supplies neither (I2): it names a [selector] and the desired
     * [text], the core derives the OCap from this variant. Coordinate-free (a [Selector], never a
     * position). Subject to the restricted-idempotency no-op (P9): if the resolved target already
     * projects [text], the core succeeds WITHOUT dispatching (design §3 I-act-4, set_text only).
     *
     * [submit]: after the text lands, also fire the field's IME editor action (the keyboard
     * Search/Go/Send/Done button) via ACTION_IME_ENTER. Some apps' live-search controllers do not
     * react to a programmatic ACTION_SET_TEXT (no per-keystroke input event) and need an explicit
     * submit to run the query (observed: Instagram's explore search) — and a login/search form often
     * needs the action pressed regardless. WORK-FIRST: submit stays `Sink.TYPE_INTO` (NOT the
     * dangerous `Sink.SUBMIT`), so it is verb-gated like any type but NEVER behind an out-of-band
     * confirm — the maintainer's call (even a send-class IME action is ungated here). When [submit] is
     * true the P9 no-op does NOT apply: the action must fire even if the text is already present.
     */
    data class SetText(val selector: Selector, val text: String, val submit: Boolean = false) : Act
}

/**
 * The terminal outcome of [AutomationCore.act] (design §1 — the act state machine's exits). Three
 * outcomes, never lumped (each is a distinct recovery for the model):
 *  - [Acted]: dispatched; [snapshot] is the fresh re-grounding — success is THIS postcondition, not
 *    the backend's dispatch boolean (design D4).
 *  - [Denied]: the capability refused, or the selector was ambiguous — a policy stop, not a retry.
 *  - [StaleState]: the grounding moved under the act (the bound target no longer resolves to exactly
 *    one live node) or the tid no longer resolves — the model must re-observe and re-decide
 *    (re-resolve), NEVER replay the stale act. When the backend captured a FRESH snapshot at the
 *    mismatch, [snapshot] carries it so the tool layer can re-ground the model on the current screen
 *    and steer a re-decide instead of a blind re-observe; it is `null` when no fresh capture is
 *    available (host-foreground pause, foreground switched off the authorized surface, or a missing
 *    tid) — in every case the model must re-observe.
 */
sealed interface ActOutcome {
    data class Acted(val snapshot: UiSnapshot) : ActOutcome
    data class Denied(val reason: ActDenyReason) : ActOutcome
    data class StaleState(val snapshot: UiSnapshot? = null) : ActOutcome
}

/** Why an act was denied. Kept coarse — the deny reason is internal, never leaked to the model. */
enum class ActDenyReason {
    /** [me.rerere.automation.cap.CapabilityGuard] returned DENY (verb/sink/surface/lease/sensitive). */
    GUARD,

    /** The selector matched more than one target — fail closed rather than guess (design I-act-9). */
    AMBIGUOUS,

    /** The capability was revoked between authorize and dispatch (kill-switch — I-act-10/P20). */
    REVOKED,

    /**
     * The out-of-band confirmation for a DANGEROUS (submit-class) sink returned false — the user
     * DENIED, or the confirm channel TIMED OUT (fail-closed; the channel owns the timeout, #198 slice
     * 11 / I-act-5). No dispatch happens. Like every other reason this is internal and never leaked to
     * the model — the tool layer maps it to the same vague ACT_DENIED text.
     */
    CONFIRM_DECLINED,
}
