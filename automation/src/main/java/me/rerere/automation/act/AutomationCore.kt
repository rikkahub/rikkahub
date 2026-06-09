package me.rerere.automation.act

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.cap.AuthRequest
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Verb
import me.rerere.automation.cap.isDangerous
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiFlag
import me.rerere.automation.observe.UiSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * The state-grounded observation loop (#187 design §5). v1 is **read-only**: it exposes only
 * [observe], which captures the backend's raw tree, projects it, and returns an authoritative,
 * freshly-grounded [UiSnapshot]. The full act path —
 *   `resolve(selector) → assert(expectedSeq + windowContentHash) → act → awaitSettle → re-snapshot`
 * — is documented here as the v2 seam but is intentionally NOT implemented (no write verb ships in
 * v1; see #198). Adding it is purely additive: a new `act(...)` method beside [observe].
 *
 * Invariants this enforces (properties P10/P11/P12):
 *  - **P11** the observed `stateSeq` is monotonic and never decreases across observes. The core is
 *    the source of truth for the observed sequence: it tracks the last value and rejects a backend
 *    that regresses (a regressing a11y backend is a bug, not a state to silently accept). It does
 *    NOT fabricate forward progress — a stale-but-equal seq stays equal (the TOCTOU close for the
 *    v2 act path is the content-hash in [AutomationBackend.windowContentHash], not a faked bump).
 *  - **P10** every returned snapshot has `stateSeq ≥` the sequence at entry.
 *  - **P12** when the foreground is the host app, [observe] returns [ScreenState.FOREGROUND_IS_HOST]
 *    with no targets — the agent must pause and re-ground rather than act on host UI.
 */
class AutomationCore(
    private val backend: AutomationBackend,
    private val projector: SnapshotProjector = SnapshotProjector(),
) {
    // Highest stateSeq observed so far. Monotonic guard for P11; starts below any real seq.
    private val lastObservedSeq = AtomicLong(Long.MIN_VALUE)

    /**
     * Capture and project the current UI. Returns a self-grounded snapshot whose `stateSeq` is ≥
     * every prior observe (P10/P11). The snapshot text is the mandatory, self-sufficient channel —
     * :app maps it to a `UIMessagePart.Text` (tool-output images are dropped by most providers).
     */
    suspend fun observe(): UiSnapshot {
        val raw = backend.snapshotRawTree()
        // Capture the TOCTOU token atomically with the tree: the backend computes [RawTree.contentHash]
        // from the SAME capture instant as the nodes (under its capture lock), so the grounding's nodes
        // and its token describe one instant. Stamping a SECOND live windowContentHash() read here would
        // let a content change between the two reads make the token describe a different tree than the
        // nodes (gate finding). The projector leaves it "" (a bare projection is not grounded); the core
        // is the one place a snapshot is bound to a live backend, so it stamps the captured hash here.
        val snapshot = projector.project(raw)
            .copy(windowContentHash = raw.contentHash)

        // P11: enforce non-decreasing observed sequence. A backend that hands back a lower seq than
        // we've already seen is malfunctioning; fail loud rather than let the model act on a tree
        // that appears to have travelled backwards in time.
        val previous = lastObservedSeq.get()
        check(snapshot.stateSeq >= previous) {
            "backend stateSeq regressed: got ${snapshot.stateSeq}, last observed $previous"
        }
        lastObservedSeq.set(snapshot.stateSeq)

        return snapshot
    }

    /**
     * The act path (#198 slice 8, design §1 — the act state machine on the proven OCap kernel).
     * Sequence, every step grounded in a kernel seam:
     *
     *   host-pause → resolve(selector) → assert(seq + windowContentHash) → authorize → perform → settle → re-snapshot
     *
     *  0. **host-pause** (I-act-6 / P12 extended): if [grounded] is [ScreenState.FOREGROUND_IS_HOST]
     *     the act refuses with [ActOutcome.StaleState] BEFORE resolve/authorize — no act dispatches
     *     while the host app is foreground, independently of the capability surface. The model must
     *     re-ground (GoHost = pause + re-ground), so it is StaleState, never Denied.
     *  1. **resolve** the [Act] against [grounded] (pure, over the snapshot the model already holds):
     *     a selector matching nothing ⇒ [ActOutcome.StaleState] (re-observe); matching >1 ⇒
     *     [ActOutcome.Denied] AMBIGUOUS (fail closed, never guess — I-act-9). Global acts skip resolve.
     *  2. **assert** the grounding is still fresh: `backend.currentStateSeq() == grounded.stateSeq`
     *     AND `backend.windowContentHash(grounded.stateSeq) == grounded.windowContentHash`. Either
     *     mismatch ⇒ [ActOutcome.StaleState]. Both are required — a dropped event leaves the seq
     *     stale-but-equal; the hash catches it (MR3 / P8 / assert-both, the TOCTOU core).
     *  3. **authorize** via the [CapabilityGuard] BEFORE any dispatch (S2). The OCap is derived from
     *     the [Act] variant (the model never supplies it). DENY ⇒ [ActOutcome.Denied] GUARD.
     *  3.6. **dangerous-sink confirm** (#198 slice 11, design Q2 / I-act-5): if the derived [Sink] is
     *     dangerous ([me.rerere.automation.cap.isDangerous] — only `SUBMIT`, a submit-class tap), the
     *     act awaits the out-of-band [confirm] channel BEFORE any dispatch. A `false` return (the user
     *     DENIED, or the channel TIMED OUT — the channel owns the timeout, fail-closed) ⇒
     *     [ActOutcome.Denied] CONFIRM_DECLINED, no dispatch. A non-dangerous act NEVER consults the
     *     channel. The confirm runs INSIDE the revoke-cancellable region (step 4's `guardInFlight`),
     *     so a kill-switch [CapabilityGuard.revoke] cancels a pending confirmation prompt.
     *  4. **perform → settle → re-snapshot**, all routed through the capability's revocation token
     *     ([CapabilityGuard.guardInFlight]) so a kill-switch [CapabilityGuard.revoke] cancels the act
     *     in flight, and a revoke that fires between authorize and perform lands in `onAlreadyRevoked`
     *     so the backend is never touched (I-act-10 / P20 extended). Success is the re-snapshot
     *     postcondition (the screen actually changed), NOT the backend's dispatch boolean (D4).
     *
     * Verbs reachable here: the lowest-risk nav — scroll (no sink) and global nav (`Sink.GLOBAL_NAV`,
     * not dangerous) — plus the slice-9 input sink set_text (`Verb.SET_TEXT` + `Sink.TYPE_INTO`) and
     * the slice-10 general tap (`Verb.TAP`, derived from [NodeActionKind.CLICK] on [Act.Targeted], no
     * sink — a general tap is verb-gated only, not submit-class). A tap rides the SAME shared SM as
     * scroll: it is NEVER no-op'd (P9 is set_text-only — step 3.5 is gated `request is Act.SetText`),
     * so it always dispatches subject to the shared assert/guard. System-UI/permission-dialog and
     * password taps are DENIED here BEFORE any dispatch — the system-window/PASSWORD provenance travels
     * on the resolved target (the variant-independent `sensitiveNode`/`systemUiTarget` plumbing below),
     * so a tap on an "Allow"/"Grant" button inside a permission window authorizes as the system-UI
     * target it is and the guard refuses it (I-act-3/I8/P18 — observable, never actionable). set_text
     * carries one extra rule on TOP of the shared SM: the restricted P9 no-op (step 3.5) — if the
     * resolved field already projects the requested text, the act succeeds WITHOUT dispatching
     * (idempotent; it still ADMITs/audits, never short-circuiting the password/system-UI guard above).
     * Dangerous-sink (submit-class) confirmation (#198 slice 11) is now wired (step 3.6): a CLICK whose
     * RESOLVED target is submit-class ([SubmitClassifier]) derives `Sink.SUBMIT` and is gated behind
     * [confirm] before it can dispatch. The sink is still core-derived, never model-supplied (I2).
     */
    suspend fun act(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        request: Act,
        confirm: ConfirmChannel,
    ): ActOutcome {
        // 0. GoHost (I-act-6 / P12 extended): no act dispatches while the host app is foreground.
        // Enforced here, BEFORE resolve/authorize, so it covers Act.Global (which skips resolve) and
        // does NOT silently depend on the surface DENY — host-pause is an admission invariant in its
        // own right (design §2 I-act-6, §4 property "host-pause"; the GoHost arrow = pause + re-ground).
        // The model must re-ground, so this is a StaleState, not a Denied (re-observe, never replay).
        if (grounded.screenState == ScreenState.FOREGROUND_IS_HOST) return ActOutcome.StaleState

        // 1. resolve (pure, over the grounded snapshot the tid came from). Targeted + SetText both name
        // a Selector; a stable-key selector (BySemanticKey / ByFormKey) self-heals to the CURRENT tid
        // here (I-act-9 / P14/MR2) — re-resolution happens against this grounding, and step 2's seq+hash
        // assert STILL runs afterward, so the heal can never bypass the freshness check (no TOCTOU).
        val selector: Selector? = when (request) {
            is Act.Global -> null
            is Act.Targeted -> request.selector
            is Act.SetText -> request.selector
        }
        val tid: Int? = selector?.let {
            when (val r = resolve(grounded, it)) {
                is Resolve.Found -> r.tid
                Resolve.NotFound -> return ActOutcome.StaleState
                Resolve.Ambiguous -> return ActOutcome.Denied(ActDenyReason.AMBIGUOUS)
            }
        }

        // 2. assert: grounding still fresh in BOTH seq and content hash (the TOCTOU close).
        if (backend.currentStateSeq() != grounded.stateSeq ||
            backend.windowContentHash(grounded.stateSeq) != grounded.windowContentHash
        ) {
            return ActOutcome.StaleState
        }

        // Resolve once: the SAME target backs the PASSWORD (sensitiveNode) and system-window
        // (systemUiTarget) provenance the guard DENYs on (I-act-3 / I8/P18) AND the submit-class
        // classification below. A global act has no node target, so neither flag nor the classifier
        // applies. Without systemUiTarget the guard's system-UI branch is dead code: a scroll/set_text
        // resolving a node inside a system/permission window would authorize as if it were app content
        // (the provenance must travel WITH the projected target). For set_text the sensitiveNode flag is
        // the HEADLINE input-sink safety check: a write into a PASSWORD field is DENIED here before any
        // dispatch (typing into a credential field is never permitted, never short-circuited by the P9
        // no-op below — the guard runs first). Hoisted ABOVE the verb/sink derivation so the submit-class
        // classifier can read the resolved target's label/key (#198 slice 11).
        val target = tid?.let { id -> grounded.targets.first { it.tid == id } }

        // 3. authorize BEFORE the backend (S2). OCap derived from the variant; target is the screen
        // the grounding came from. sensitiveNode guards a (pathological) scroll of a password node.
        val (verb, sink) = when (request) {
            // The targeted verb is derived from the node-action kind (I2 — never model-supplied). A
            // scroll (SCROLL_FORWARD/BACKWARD ⇒ Verb.SCROLL) carries NO sink. A CLICK ⇒ Verb.TAP carries
            // a sink ONLY when the RESOLVED target is submit-class ([SubmitClassifier], #198 slice 11):
            // an ordinary tap stays sink-less (verb-gated only, #198 slice 10), but a send/pay/checkout-
            // class tap derives Sink.SUBMIT — STILL derived here in the core from the resolved target's
            // label/key, never model-supplied (I2). The SUBMIT sink is then (a) checked against the
            // sink budget by the guard below — a lease without SUBMIT in budget DENYs here, before the
            // confirm gate is even reached — and (b) gated behind the out-of-band confirm at step 3.6.
            // All taps still flow through the sensitiveNode/systemUiTarget DENY below — a password or
            // system-UI tap is denied before dispatch exactly as a scroll is.
            is Act.Targeted -> when (request.kind) {
                NodeActionKind.SCROLL_FORWARD, NodeActionKind.SCROLL_BACKWARD -> Verb.SCROLL to null
                NodeActionKind.CLICK ->
                    Verb.TAP to (if (target != null && SubmitClassifier.isSubmitClass(target)) Sink.SUBMIT else null)
            }
            is Act.Global -> Verb.GLOBAL to Sink.GLOBAL_NAV
            is Act.SetText -> Verb.SET_TEXT to Sink.TYPE_INTO
        }
        val authRequest = AuthRequest(
            verb = verb,
            targetPkg = grounded.foregroundPkg,
            sink = sink,
            sensitiveNode = target?.flags?.contains(UiFlag.PASSWORD) == true,
            systemUiTarget = target?.systemWindow == true,
        )
        if (guard.authorize(authRequest) == Decision.DENY) {
            return ActOutcome.Denied(ActDenyReason.GUARD)
        }

        // 3.5. P9 restricted idempotency (design §3 I-act-4 — set_text only, NEVER submit/pay). The
        // act has been ADMITted (so it consumed exactly one step / left one audit entry, mirroring
        // how core.act always authorizes before deciding to dispatch), but the postcondition the model
        // wants already holds: the resolved field's CURRENT VALUE already equals the requested text.
        // Compare against `target.editableText` (the ground-truth editable value), NOT `target.text`
        // (the DISPLAY projection): `text` is `node.text ?: node.contentDescription`, so an EMPTY field
        // (node.text == null) whose contentDescription is a hint/label (e.g. "Email") projects
        // `text = "Email"` — matching `set_text("Email")` against THAT would skip the dispatch and
        // leave the field empty while the model believes the write landed (the design's "clean
        // postconditions only" rule, violated). `editableText` is null for an empty/non-editable/
        // password field, so the compare is exact and a null value (unknown postcondition) fails toward
        // dispatch. The compare is case-sensitive, no trim; any difference dispatches.
        if (request is Act.SetText && target?.editableText == request.text) {
            return ActOutcome.Acted(grounded)
        }

        // 4. perform → settle → re-snapshot under the revocation token (revoke cancels in-flight).
        val performAction = when (request) {
            is Act.Global -> PerformAction.Global(request.nav)
            is Act.Targeted -> PerformAction.Node(grounded.stateSeq, tid!!, request.kind)
            is Act.SetText -> PerformAction.SetText(grounded.stateSeq, tid!!, request.text)
        }
        val job = currentCoroutineContext()[Job]
        return guard.guardInFlight(
            cancel = { job?.cancel(CancellationException("automation revoked")) },
            onAlreadyRevoked = { ActOutcome.Denied(ActDenyReason.REVOKED) },
            block = block@{
                // 3.6. Dangerous-sink (submit-class) out-of-band confirm (#198 slice 11, design Q2 /
                // I-act-5). Placed FIRST inside the revoke-cancellable block so a pending confirmation
                // prompt is torn down by a kill-switch revoke (the block's `cancel` cancels the owning
                // Job, unparking a suspended confirm), and so onAlreadyRevoked still wins a pre-revoke.
                // Runs ONLY after the guard ADMITted (the budget/sensitive/lease checks already passed)
                // and ONLY for a dangerous sink — a non-dangerous act never consults the channel. A
                // false return is the user's DENY *or* the channel's fail-closed timeout (the channel
                // owns the timeout); the core treats both identically as CONFIRM_DECLINED with NO
                // dispatch. A submit-class tap is always a TAP, so this never collides with the P9
                // set_text no-op (step 3.5), which already returned above for an unchanged-text set.
                if (sink?.isDangerous == true) {
                    // Fail-closed on EVERY non-confirm outcome: a `false` (the user's DENY or the
                    // channel's fail-closed timeout) AND any thrown exception (e.g. the overlay could
                    // not attach) both deny — a confirm that did not return `true` is not a confirm, so
                    // it must never fall through to dispatch. CancellationException is rethrown so a
                    // kill-switch revoke during the prompt still tears the act down (the suspend is
                    // cancellable); only a non-cancellation throwable is normalized to a deny.
                    val confirmed = try {
                        confirm.confirm(grounded.foregroundPkg, verb, target?.text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        false
                    }
                    if (!confirmed) {
                        return@block ActOutcome.Denied(ActDenyReason.CONFIRM_DECLINED)
                    }
                }
                // The backend returns false WITHOUT dispatching when the grounding moved in the
                // assert→dispatch gap (a node action whose carried stateSeq no longer matches the live
                // tree — I-act-1/MR3 — re-checked atomically with the walk). That false is the safety
                // signal the dispatch-time re-check exists to produce; HONOR it as StaleState (the
                // model must re-observe, never replay), do not settle/re-snapshot and report success.
                // D4 licenses not TRUSTING a true return (success is the re-snapshot, below); it does
                // NOT license IGNORING a false that means "I refused to dispatch — the grounding moved".
                if (!backend.perform(performAction)) {
                    ActOutcome.StaleState
                } else {
                    backend.awaitSettle()
                    // D4: act success is the fresh re-grounding, not perform()'s boolean.
                    val resnapshot = observe()
                    // Surface re-assert (mirrors UiAutomationTools' ui_observe bind): the post-act
                    // re-snapshot must NOT disclose an app the capability never admitted. The act
                    // authorized against `grounded.foregroundPkg`; a global nav (HOME/BACK/RECENTS) can
                    // surface a DIFFERENT app (the launcher / whatever HOME reveals), which the surface
                    // guard would DENY on a fresh observe — so returning its content here would leak
                    // past the capability. Conservative policy: if the re-snapshot left the authorized
                    // target, return StaleState so the model re-observes (and ui_observe re-authorizes
                    // the new surface), never Acted(other-app). A reversible nav that changed nothing
                    // still binds.
                    if (resnapshot.foregroundPkg != grounded.foregroundPkg) {
                        ActOutcome.StaleState
                    } else {
                        ActOutcome.Acted(resnapshot)
                    }
                }
            },
        )
    }

    /** Resolve a [Selector] against [grounded]; ambiguity is a deny, not a guess (design I-act-9). */
    private fun resolve(grounded: UiSnapshot, selector: Selector): Resolve = when (selector) {
        is Selector.ByTid ->
            if (grounded.targets.any { it.tid == selector.tid }) Resolve.Found(selector.tid)
            else Resolve.NotFound

        is Selector.ByText -> grounded.targets
            .filter { it.text == selector.text && (selector.role == null || it.role == selector.role) }
            .toResolve()

        is Selector.BySemanticKey -> grounded.targets
            .filter { it.semanticKey == selector.semanticKey }
            .toResolve()

        is Selector.ByFormKey -> grounded.targets
            .filter { it.formKey == selector.formKey }
            .toResolve()
    }

    private fun List<me.rerere.automation.observe.UiTarget>.toResolve(): Resolve = when (size) {
        0 -> Resolve.NotFound
        1 -> Resolve.Found(this[0].tid)
        else -> Resolve.Ambiguous
    }

    private sealed interface Resolve {
        data class Found(val tid: Int) : Resolve
        object NotFound : Resolve
        object Ambiguous : Resolve
    }

    /** True once the current foreground is the host app (P12) — caller pauses the agent loop. */
    fun isHostForeground(snapshot: UiSnapshot): Boolean =
        snapshot.screenState == ScreenState.FOREGROUND_IS_HOST
}
