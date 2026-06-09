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
    /** Scroll a resolved element. Maps to `Verb.SCROLL` with no sink (viewport movement, no side effect). */
    data class Targeted(val selector: Selector, val kind: NodeActionKind) : Act

    /** Global navigation. Maps to `Verb.GLOBAL` + `Sink.GLOBAL_NAV` (budgeted, not dangerous — Q2). */
    data class Global(val nav: GlobalNav) : Act
}

/**
 * The terminal outcome of [AutomationCore.act] (design §1 — the act state machine's exits). Three
 * outcomes, never lumped (each is a distinct recovery for the model):
 *  - [Acted]: dispatched; [snapshot] is the fresh re-grounding — success is THIS postcondition, not
 *    the backend's dispatch boolean (design D4).
 *  - [Denied]: the capability refused, or the selector was ambiguous — a policy stop, not a retry.
 *  - [StaleState]: the grounding moved under the act (seq/hash mismatch) or the tid no longer
 *    resolves — the model must re-observe and re-decide (re-resolve), NEVER replay the stale act.
 */
sealed interface ActOutcome {
    data class Acted(val snapshot: UiSnapshot) : ActOutcome
    data class Denied(val reason: ActDenyReason) : ActOutcome
    object StaleState : ActOutcome
}

/** Why an act was denied. Kept coarse — the deny reason is internal, never leaked to the model. */
enum class ActDenyReason {
    /** [me.rerere.automation.cap.CapabilityGuard] returned DENY (verb/sink/surface/lease/sensitive). */
    GUARD,

    /** The selector matched more than one target — fail closed rather than guess (design I-act-9). */
    AMBIGUOUS,

    /** The capability was revoked between authorize and dispatch (kill-switch — I-act-10/P20). */
    REVOKED,
}
