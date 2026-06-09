package me.rerere.automation.act

import me.rerere.automation.cap.Verb

/**
 * The out-of-band confirmation seam for a dangerous (submit-class) sink (#198 slice 11, design Q2 /
 * I-act-5 / step 3.6). When a tap is classified as submit-class ([SubmitClassifier]), the core does
 * NOT dispatch it until this channel returns `true`. Mirrors [me.rerere.automation.backend
 * .AutomationBackend] as an injected port (DIP): the pure :automation core depends on this interface;
 * the concrete overlay-backed implementation lives in :app/AccessibilityRuntime (the floating Confirm/
 * Deny affordance on the kill-switch overlay window), and tests supply a fake.
 *
 * Contract:
 *  - returns `true`  ⇒ the user explicitly confirmed → the core proceeds to dispatch;
 *  - returns `false` ⇒ DENIED **or** TIMED OUT (fail-closed). The CHANNEL owns the timeout and the
 *    fail-closed `timeout → false` rule (the real impl wraps the user wait in `withTimeoutOrNull`);
 *    the core never times out itself — it treats `false` (deny or timeout) identically as
 *    [ActDenyReason.CONFIRM_DECLINED] with no dispatch.
 *
 * Out-of-band BY CONTRACT: the in-chat approval gate is structurally unreachable while another app is
 * foreground (design constraint 1), so the confirmation surfaces on the always-reachable overlay, not
 * in the conversation. The call is `suspend` so the real impl can park on the user's tap; it MUST be
 * cancellable so a kill-switch `revoke()` (which cancels the act's owning Job) tears down a pending
 * confirmation prompt.
 *
 * @param app the foreground package the dangerous act targets (shown to the user).
 * @param verb the act verb (a submit-class tap is [Verb.TAP]); shown to the user.
 * @param label the resolved target's visible label, if any (shown to the user so they know WHAT commits).
 */
fun interface ConfirmChannel {
    suspend fun confirm(app: String, verb: Verb, label: String?): Boolean
}

/** A channel that always confirms — for tests / a non-gating default. NEVER use as a production fallback. */
object AlwaysConfirm : ConfirmChannel {
    override suspend fun confirm(app: String, verb: Verb, label: String?): Boolean = true
}

/** A channel that always denies — the fail-closed default when no real confirm surface is reachable. */
object AlwaysDeny : ConfirmChannel {
    override suspend fun confirm(app: String, verb: Verb, label: String?): Boolean = false
}
