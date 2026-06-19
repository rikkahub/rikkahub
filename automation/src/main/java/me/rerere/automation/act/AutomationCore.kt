package me.rerere.automation.act

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.BindingRequest
import me.rerere.automation.backend.BindingResolution
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.PerformResult
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
import me.rerere.automation.observe.UiTarget
import me.rerere.automation.observe.toTargetBinding
import java.util.concurrent.atomic.AtomicLong

/**
 * The state-grounded observation loop (#187 design §5). v1 is **read-only**: it exposes only
 * [observe], which captures the backend's raw tree, projects it, and returns an authoritative,
 * freshly-grounded [UiSnapshot]. The act path —
 *   `resolve(selector) → build TargetBinding → authorize → perform (fresh re-resolve + dispatch) → awaitSettle → re-snapshot`
 * — is the eyes-open hybrid tap design: a targeted act no longer gates on a blind `(stateSeq, tid)`
 * token. Instead it carries a strict [me.rerere.automation.observe.TargetBinding] the backend
 * re-resolves against a FRESH live capture and dispatches ONLY when exactly one node matches — so a
 * same-shaped node that re-flowed into a different window/path, or a same-label replacement, is
 * refused, while benign status-bar/SystemUI churn no longer stale a targeted dispatch.
 *
 * Invariants this enforces (properties P10/P11/P12):
 *  - **P11** the observed `stateSeq` is monotonic and never decreases across observes. The core is
 *    the source of truth for the observed sequence: it tracks the last value and rejects a backend
 *    that regresses. It does NOT fabricate forward progress — the eyes-open freshness signal is the
 *    strict TargetBinding match at dispatch time, not a faked seq bump.
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
    suspend fun observe(allowedPackages: Set<String>, includeHost: Boolean = false): UiSnapshot {
        val raw = backend.snapshotRawTree()
        // The projector leaves windowContentHash "" (a bare projection is not grounded); the core
        // is the one place a snapshot is bound to a live backend, so it stamps the captured hash here.
        val snapshot = projector.project(raw, allowedPackages, includeHost)
            .copy(windowContentHash = raw.contentHash)
        return acceptMonotonic(snapshot)
    }

    /**
     * P11 monotonic guard for EVERY backend-produced snapshot shown to the model — `observe`'s capture
     * AND the fresh snapshots the eyes-open act path surfaces (a P9 no-op's `Acted`, a binding
     * mismatch's `StaleState`). Enforces a non-decreasing observed sequence: a backend that hands back a
     * lower seq than one already shown is malfunctioning, so fail loud rather than let the model act on a
     * tree that appears to have travelled backwards in time; and advance [lastObservedSeq] so a LATER
     * `observe` cannot silently accept a seq below a fresh snapshot already rendered. Funnelling both
     * paths through here keeps the act's new snapshots from bypassing the invariant `observe` enforces.
     */
    private fun acceptMonotonic(snapshot: UiSnapshot): UiSnapshot {
        val previous = lastObservedSeq.get()
        check(snapshot.stateSeq >= previous) {
            "backend stateSeq regressed: got ${snapshot.stateSeq}, last observed $previous"
        }
        lastObservedSeq.set(snapshot.stateSeq)
        return snapshot
    }

    /**
     * The eyes-open act path (design §1 / spec §6 — the act state machine on the proven OCap kernel).
     * Sequence, every step grounded in a kernel seam:
     *
     *   host-pause → resolve(selector) → build TargetBinding → authorize → fresh re-resolve + dispatch → awaitSettle → re-snapshot
     *
     *  0. **host-pause** (I-act-6 / P12 extended): if [grounded] is [ScreenState.FOREGROUND_IS_HOST]
     *     the act refuses with [ActOutcome.StaleState] BEFORE resolve/authorize — no act dispatches
     *     while the host app is foreground, independently of the capability surface. The model must
     *     re-ground (GoHost = pause + re-ground), so it is StaleState, never Denied.
     *  1. **resolve** the [Act] against [grounded] (pure, over the snapshot the model already holds):
     *     a selector matching nothing ⇒ [ActOutcome.StaleState] (re-observe); matching >1 ⇒
     *     [ActOutcome.Denied] AMBIGUOUS (fail closed, never guess — I-act-9). Global acts skip resolve.
     *     The resolved [UiTarget] is then turned into a strict [me.rerere.automation.observe.TargetBinding]
     *     (requireVisibleTextMatch = true for a CLICK, false for scroll / set_text).
     *  2. **authorize** via the [CapabilityGuard] BEFORE any dispatch or backend resolveBinding (S2).
     *     The OCap is derived from the [Act] variant (the model never supplies it). DENY ⇒
     *     [ActOutcome.Denied] GUARD. The PASSWORD (sensitiveNode) and system-window (systemUiTarget)
     *     provenance — which make system/permission UI observable but non-actionable (I-act-3 / I8/P18)
     *     and refuse typing into a credential field — travel on the resolved target into the AuthRequest.
     *  3. **dangerous-sink confirm** (#198 slice 11, design Q2 / I-act-5): if the derived [Sink] is
     *     dangerous ([me.rerere.automation.cap.isDangerous] — only `SUBMIT`, a submit-class tap), the
     *     act FIRST fresh-resolves the binding (so the confirm prompt shows the FRESH resolved label,
     *     never the stale grounded one), THEN awaits the out-of-band [confirm] channel BEFORE any
     *     dispatch. A `false` return (the user DENIED, or the channel TIMED OUT — the channel owns the
     *     timeout, fail-closed) ⇒ [ActOutcome.Denied] CONFIRM_DECLINED, no dispatch. A non-dangerous
     *     act NEVER consults the channel. The confirm runs INSIDE the revoke-cancellable region
     *     (step 4's `guardInFlight`), so a kill-switch [CapabilityGuard.revoke] cancels a pending prompt.
     *  4. **fresh re-resolve + dispatch → awaitSettle → re-snapshot**, all routed through the
     *     capability's revocation token ([CapabilityGuard.guardInFlight]) so a kill-switch
     *     [CapabilityGuard.revoke] cancels the act in flight, and a revoke that fires between
     *     authorize and perform lands in `onAlreadyRevoked` so the backend is never touched
     *     (I-act-10 / P20 extended). Success is the re-snapshot postcondition (the screen actually
     *     changed), NOT the backend's dispatch ack (D4).
     *
     * **No seq/hash freshness gate for targeted acts.** The old `currentStateSeq + windowContentHash`
     * assert is GONE for [Act.Targeted] / [Act.SetText]: the freshness signal is the strict
     * TargetBinding match at dispatch time (the backend re-resolves against a FRESH capture). A
     * benign status-bar/SystemUI content change that does not touch the bound target no longer
     * stale a dispatch; a target that re-flowed into a different window/path or got replaced by a
     * same-label node now refuses the dispatch (the binding's strict match fails). When the backend
     * captured a FRESH snapshot at the mismatch, [ActOutcome.StaleState] carries it (when the
     * foreground is still the authorized surface) so the tool layer can re-ground the model on the
     * current screen and steer a re-decide instead of a blind re-observe.
     *
     * **P9 restricted idempotency (set_text only, NEVER submit/pay).** A set_text whose fresh
     * resolved target already carries [Act.SetText.text] as its editable value succeeds WITHOUT
     * dispatching. The compare uses the FRESH resolved target's [UiTarget.editableText] (the
     * ground-truth editable value), NOT the stale grounded value, so a benign reflow that healed
     * the tid is reflected; `editableText` is null for an empty / non-editable / password field, so
     * the compare is exact and a null value (unknown postcondition) fails toward dispatch.
     */
    suspend fun act(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        request: Act,
        confirm: ConfirmChannel,
    ): ActOutcome {
        // 0. GoHost (I-act-6 / P12 extended): no act dispatches while the host app is foreground.
        // Enforced here, BEFORE resolve/authorize, so it covers Act.Global (which skips resolve) and
        // does NOT silently depend on the surface DENY. The model must re-ground, so this is a
        // StaleState (re-observe, never replay); no fresh snapshot is available (the host is foreground).
        if (grounded.screenState == ScreenState.FOREGROUND_IS_HOST) return ActOutcome.StaleState(null)

        // 1. resolve (pure, over the grounded snapshot the tid came from). Targeted + SetText both name
        // a Selector; a stable-key selector (BySemanticKey / ByFormKey) self-heals to the CURRENT tid
        // here (I-act-9 / P14/MR2). The resolved UiTarget is then turned into a strict TargetBinding.
        val target: UiTarget? = when (request) {
            is Act.Global -> null
            is Act.Targeted -> when (val r = resolve(grounded, request.selector)) {
                is Resolve.Found -> r.target
                Resolve.NotFound -> return ActOutcome.StaleState(null)
                Resolve.Ambiguous -> return ActOutcome.Denied(ActDenyReason.AMBIGUOUS)
            }
            is Act.SetText -> when (val r = resolve(grounded, request.selector)) {
                is Resolve.Found -> r.target
                Resolve.NotFound -> return ActOutcome.StaleState(null)
                Resolve.Ambiguous -> return ActOutcome.Denied(ActDenyReason.AMBIGUOUS)
            }
        }

        // 2. Derive the verb/sink from the variant (I2 — never model-supplied). The resolved target
        // backs the PASSWORD (sensitiveNode) and system-window (systemUiTarget) provenance the guard
        // DENYs on (I-act-3 / I8/P18) AND the submit-class classification. A global act has no node
        // target, so neither flag nor the classifier applies.
        val (verb, sink) = when (request) {
            is Act.Targeted -> when (request.kind) {
                NodeActionKind.SCROLL_FORWARD, NodeActionKind.SCROLL_BACKWARD -> Verb.SCROLL to null
                NodeActionKind.CLICK ->
                    Verb.TAP to (if (target != null && SubmitClassifier.isSubmitClass(target)) Sink.SUBMIT else null)
            }
            is Act.Global -> Verb.GLOBAL to Sink.GLOBAL_NAV
            is Act.SetText -> Verb.SET_TEXT to Sink.TYPE_INTO
        }

        // 3. authorize BEFORE the backend (S2). OCap derived from the variant; target is the screen
        // the grounding came from. sensitiveNode guards a (pathological) scroll/tap of a password
        // node and (the headline input-sink safety) a set_text into a credential field. systemUiTarget
        // makes a system/permission window observable but non-actionable.
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

        // 4. Fresh re-resolve + dispatch under the revocation token (revoke cancels in-flight).
        val job = currentCoroutineContext()[Job]
        return guard.guardInFlight(
            cancel = { job?.cancel(CancellationException("automation revoked")) },
            onAlreadyRevoked = { ActOutcome.Denied(ActDenyReason.REVOKED) },
            block = block@{
                when (request) {
                    is Act.Global -> dispatchGlobal(guard, grounded, request)
                    is Act.Targeted -> {
                        val binding = target!!.toTargetBinding(
                            requireVisibleTextMatch = request.kind == NodeActionKind.CLICK,
                        )
                        if (sink?.isDangerous == true) {
                            dispatchSubmitTap(guard, grounded, binding, confirm)
                        } else {
                            dispatchNodeScrollOrTap(guard, grounded, binding, request.kind)
                        }
                    }
                    is Act.SetText -> {
                        val binding = target!!.toTargetBinding(requireVisibleTextMatch = false)
                        dispatchSetText(guard, grounded, binding, request.text, request.submit)
                    }
                }
            },
        )
    }

    /** Resolve a [Selector] against [grounded]; ambiguity is a deny, not a guess (design I-act-9). */
    private fun resolve(grounded: UiSnapshot, selector: Selector): Resolve {
        val matches: List<UiTarget> = when (selector) {
            is Selector.ByTid -> grounded.targets.filter { it.tid == selector.tid }
            is Selector.ByText -> grounded.targets
                .filter { it.text == selector.text && (selector.role == null || it.role == selector.role) }
            is Selector.BySemanticKey -> grounded.targets.filter { it.semanticKey == selector.semanticKey }
            is Selector.ByFormKey -> grounded.targets.filter { it.formKey == selector.formKey }
        }
        return when (matches.size) {
            0 -> Resolve.NotFound
            1 -> Resolve.Found(matches.single())
            else -> Resolve.Ambiguous
        }
    }

    private sealed interface Resolve {
        data class Found(val target: UiTarget) : Resolve
        data object NotFound : Resolve
        data object Ambiguous : Resolve
    }

    /** Dispatch a global nav: perform, settle, re-snapshot. No binding (no node target). */
    private suspend fun dispatchGlobal(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        request: Act.Global,
    ): ActOutcome {
        val result = backend.perform(PerformAction.Global(request.nav))
        return finishAfterPerform(guard, grounded, result)
    }

    /** Non-dangerous scroll / tap: settle, perform (atomic fresh re-resolve + dispatch), settle, re-snapshot. */
    private suspend fun dispatchNodeScrollOrTap(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        binding: me.rerere.automation.observe.TargetBinding,
        kind: NodeActionKind,
    ): ActOutcome {
        // Pre-dispatch settle (spec §6 step 8): quiesce the screen BEFORE the atomic re-resolve so the
        // fresh binding resolves against a stable tree, not a mid-animation one (a transient frame
        // would otherwise mismatch -> a spurious StaleState re-decide).
        backend.awaitSettle()
        val action = PerformAction.Node(
            binding = binding,
            kind = kind,
            allowedPackages = setOf(grounded.foregroundPkg),
            includeHost = guard.includeHost,
        )
        val result = backend.perform(action)
        return finishAfterPerform(guard, grounded, result)
    }

    /**
     * Set_text: first [AutomationBackend.resolveBinding] to get the FRESH target (so the P9 no-op
     * compares the FRESH editable value, never the stale grounded one — spec §6 step 9). If the fresh
     * editable value already equals the requested text, return Acted WITHOUT dispatching (P9). Else
     * dispatch a bound SetText, settle, re-snapshot.
     */
    private suspend fun dispatchSetText(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        binding: me.rerere.automation.observe.TargetBinding,
        text: String,
        submit: Boolean,
    ): ActOutcome {
        // Pre-resolve settle (spec §6 step 9): quiesce BEFORE the fresh resolveBinding so the P9
        // no-op compares the field's settled editable value (not a mid-reflow transient) and a
        // changing set_text dispatches against a stable tree.
        backend.awaitSettle()
        val resolveReq = BindingRequest(
            binding = binding,
            allowedPackages = setOf(grounded.foregroundPkg),
            includeHost = guard.includeHost,
        )
        return when (val r = backend.resolveBinding(resolveReq)) {
            is BindingResolution.Mismatch -> ActOutcome.StaleState(freshSnapForSurface(r.snapshot, grounded))
            is BindingResolution.Unique -> {
                // P9 restricted idempotency (set_text only): the fresh editable VALUE already equals
                // the requested text, so succeed WITHOUT dispatching (clean postcondition holds). The
                // compare is against the FRESH resolved editableText, not the stale grounded one. The
                // no-op must STILL hold the post-act surface invariant: the old field can uniquely match
                // even after the foreground switched, but the fresh snapshot then describes an
                // un-admitted surface — so return Acted only when it is still the authorized surface,
                // else StaleState (re-observe), exactly as the post-dispatch tail does.
                //
                // A submit request OVERRIDES P9: even when the text already matches, the IME action must
                // still fire (the user wants the query run / form submitted), so fall through to dispatch.
                if (r.target.editableText == text && !submit) {
                    freshSnapForSurface(r.snapshot, grounded)
                        ?.let { ActOutcome.Acted(it) }
                        ?: ActOutcome.StaleState(null)
                } else {
                    val action = PerformAction.SetText(
                        binding = binding,
                        text = text,
                        allowedPackages = setOf(grounded.foregroundPkg),
                        includeHost = guard.includeHost,
                        submit = submit,
                    )
                    val result = backend.perform(action)
                    finishAfterPerform(guard, grounded, result)
                }
            }
        }
    }

    /**
     * Submit-class tap (spec §6 step 10): FIRST fresh-resolve the binding (so the confirm prompt
     * shows the FRESH resolved label, never the stale grounded one); THEN fail-closed await the
     * out-of-band [confirm] channel; ONLY on confirm=true re-perform (which atomically re-resolves
     * and dispatches). A false / thrown / cancelled confirm returns CONFIRM_DECLINED with NO dispatch
     * (CancellationException rethrown so a kill-switch revoke during the prompt still tears the act
     * down). After confirm=true the dispatch+settle+re-snapshot run inside the same revoke-cancellable
     * region; the final perform re-resolves the binding atomically.
     */
    private suspend fun dispatchSubmitTap(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        binding: me.rerere.automation.observe.TargetBinding,
        confirm: ConfirmChannel,
    ): ActOutcome {
        val resolveReq = BindingRequest(
            binding = binding,
            allowedPackages = setOf(grounded.foregroundPkg),
            includeHost = guard.includeHost,
        )
        return when (val r = backend.resolveBinding(resolveReq)) {
            is BindingResolution.Mismatch -> ActOutcome.StaleState(freshSnapForSurface(r.snapshot, grounded))
            is BindingResolution.Unique -> {
                // Confirm using the FRESH resolved target's label (spec §6 step 10: the prompt must
                // show the current label, not the stale grounded one).
                val confirmed = try {
                    confirm.confirm(grounded.foregroundPkg, Verb.TAP, r.target.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
                if (!confirmed) {
                    return ActOutcome.Denied(ActDenyReason.CONFIRM_DECLINED)
                }
                // Confirm true: settle/rebind THEN dispatch (spec §6 step 10 order is
                // fresh-bind -> confirm -> settle/rebind -> dispatch). The post-confirm settle quiesces
                // any screen change that happened while the prompt was up so the final atomic re-resolve
                // binds the still-current target; the perform below re-resolves the binding atomically.
                backend.awaitSettle()
                val action = PerformAction.Node(
                    binding = binding,
                    kind = NodeActionKind.CLICK,
                    allowedPackages = setOf(grounded.foregroundPkg),
                    includeHost = guard.includeHost,
                )
                val result = backend.perform(action)
                finishAfterPerform(guard, grounded, result)
            }
        }
    }

    /**
     * Post-perform shared tail (design D4 / spec §6 step 8): on [PerformResult.Dispatched] settle
     * then re-snapshot (act success is the fresh re-grounding, not the dispatch ack); on
     * [PerformResult.BindingMismatch] surface StaleState with the fresh snapshot when the foreground
     * is still the authorized surface (so the tool layer can re-ground the model on the current
     * screen), else null (the surface moved — the model must re-observe and re-authorize);
     * [PerformResult.DispatchFailed] (the framework refused the verb) ⇒ StaleState(null): no fresh
     * snapshot is informative, the model must re-observe and re-decide.
     */
    private suspend fun finishAfterPerform(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        result: PerformResult,
    ): ActOutcome = when (result) {
        is PerformResult.Dispatched -> {
            backend.awaitSettle()
            // D4: act success is the fresh re-grounding, not perform()'s ack. The re-snapshot inherits
            // the capability's host policy so a YOLO act re-grounds on the host (a scoped act keeps
            // includeHost=false and host-excludes exactly as before).
            val resnapshot = observe(setOf(grounded.foregroundPkg), guard.includeHost)
            // Surface re-assert (mirrors UiAutomationTools' ui_observe bind): the post-act re-snapshot
            // must NOT disclose an app the capability never admitted. The act authorized against
            // `grounded.foregroundPkg`; a global nav (HOME/BACK/RECENTS) can surface a DIFFERENT app
            // (the launcher / whatever HOME reveals), which the surface guard would DENY on a fresh
            // observe — so returning its content here would leak past the capability. Conservative
            // policy: if the re-snapshot left the authorized target, return StaleState so the model
            // re-observes (and ui_observe re-authorizes the new surface), never Acted(other-app).
            if (resnapshot.foregroundPkg != grounded.foregroundPkg) {
                ActOutcome.StaleState(null)
            } else {
                ActOutcome.Acted(resnapshot)
            }
        }

        is PerformResult.BindingMismatch ->
            // The bound target did not resolve to exactly one live node (zero/multiple matches); the
            // backend captured a fresh snapshot. Hand it to the tool layer ONLY when the foreground is
            // still the authorized surface — a surface switch invalidates the binding's provenance AND
            // would leak a never-admitted app, so StaleState(null) forces a fresh re-observe.
            ActOutcome.StaleState(freshSnapForSurface(result.snapshot, grounded))

        PerformResult.DispatchFailed ->
            // The framework refused the verb (e.g. ACTION_CLICK returned false). No informative fresh
            // snapshot to carry; the model must re-observe and re-decide.
            ActOutcome.StaleState(null)
    }

    /** Return [fresh] only when it stays on the authorized [grounded] surface; else null. */
    private fun freshSnapForSurface(fresh: UiSnapshot, grounded: UiSnapshot): UiSnapshot? =
        if (fresh.foregroundPkg == grounded.foregroundPkg) acceptMonotonic(fresh) else null

    /** True once the current foreground is the host app (P12) — caller pauses the agent loop. */
    fun isHostForeground(snapshot: UiSnapshot): Boolean =
        snapshot.screenState == ScreenState.FOREGROUND_IS_HOST
}
