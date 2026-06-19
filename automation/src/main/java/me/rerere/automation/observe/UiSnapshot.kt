package me.rerere.automation.observe

import kotlinx.serialization.Serializable

/**
 * Sentinel for a window id the backend could not supply. Real Android window ids are non-negative
 * ([android.view.accessibility.AccessibilityWindowInfo.getId]), so [Int.MIN_VALUE] is unambiguous.
 *
 * When a target's [UiTarget.windowId] is [UNKNOWN_WINDOW_ID], strict [TargetBinding] matching can
 * only succeed against another unknown-window target — the common (unknown, unknown) case — which
 * preserves the fail-closed default rather than fabricating a window identity.
 */
const val UNKNOWN_WINDOW_ID: Int = Int.MIN_VALUE

/**
 * Pure, backend-agnostic observation domain types (#187 v1, read-only).
 *
 * These are the *projected* model the LLM is allowed to see — the compact "action table"
 * [SnapshotProjector] derives from a backend's raw accessibility tree. The model NEVER sees
 * the raw tree and NEVER sees raw coordinates: it can only reference a [UiTarget.tid] that is
 * valid for the *current* [UiSnapshot.stateSeq]. That tid-only, coordinate-free surface is the
 * anti-hallucination + anti-injection primitive from the design (§4): an action must name a
 * live snapshot id, re-validated every step, so a stale remembered position cannot be replayed.
 *
 * Module purity (design I10): this file imports only kotlinx.serialization. No android.* — the
 * Android AccessibilityService lives only in :app/AccessibilityRuntime, which maps its live tree
 * into [RawTree] for this module to project.
 */
@Serializable
data class UiSnapshot(
    /** Strictly monotonic per backend (design I6/P11). A [tid] is only valid for this value. */
    val stateSeq: Long,
    /** Foreground app package. [FOREGROUND_IS_HOST]-equivalent when this equals the host pkg. */
    val foregroundPkg: String,
    val screenState: ScreenState,
    val targets: List<UiTarget>,
    /**
     * The backend content hash at capture time. Populated by [me.rerere.automation.act.AutomationCore.observe]
     * (the projector leaves it `""` — a bare projection is not grounded against a live backend). LEGACY:
     * the old v2 act path re-checked this whole-snapshot hash as a TOCTOU gate; the eyes-open redesign
     * REMOVED that gate (a targeted act now carries a decision-time [TargetBinding] that the backend
     * fresh-resolves and dispatches atomically, so benign background churn no longer stales an act). The
     * field is retained as the capture-instant token a backend may still surface and as the adversarial
     * input the "a hash change no longer stales an act" regression asserts. NOT model-facing — :app's
     * snapshot renderer never surfaces it.
     */
    val windowContentHash: String = "",
)

/**
 * Coarse screen classification. Drives whether the model should act, wait, or stop. Kept small
 * on purpose — a richer enum invites special-case branches in the projector (Linus: data first).
 */
@Serializable
enum class ScreenState {
    /** Normal, interactable content. */
    READY,

    /** A modal/alert/permission dialog is on top (multi-window awareness, design §4). */
    DIALOG,

    /** Content is still settling (progress/loading). */
    LOADING,

    /** FLAG_SECURE or an empty tree: nothing may be projected (design I1). */
    SECURE_WINDOW,

    /** Foreground is the host app (me.rerere.rikkahub): the agent must pause/re-ground (P12). */
    FOREGROUND_IS_HOST,
}

/**
 * One actionable element. [tid] is turn-scoped: unique within a single [UiSnapshot] and only
 * meaningful for that snapshot's [UiSnapshot.stateSeq]. There are deliberately NO coordinates,
 * resource ids, or bounds exposed to the model — only the opaque tid plus semantic descriptors.
 */
@Serializable
data class UiTarget(
    val tid: Int,
    val role: String,
    /** Visible label/text. For password fields this is masked to bullets by the projector (P1). */
    val text: String? = null,
    val flags: Set<UiFlag> = emptySet(),
    /** Stable semantic key when the backend can supply one (e.g. content-description/view-tag). */
    val semanticKey: String? = null,
    /** Form-field key for inputs (used by the v2 self-heal path; carried but unused for reads). */
    val formKey: String? = null,
    /**
     * The editable field's CURRENT VALUE — the literal `node.text` for an editable node, with NO
     * contentDescription fallback and NO password unmasking (it is `null` for a non-editable or
     * password node). This is the ground-truth source for the act path's P9 restricted-idempotency
     * no-op (#198 slice 9): [text] is a DISPLAY projection (`node.text ?: node.contentDescription`),
     * so an EMPTY field (`node.text == null`) whose `contentDescription` is a label/hint (e.g.
     * "Email") projects `text = "Email"` — comparing P9 against that would match `set_text("Email")`
     * and skip the dispatch, leaving the field empty while the model believes the write landed. The
     * postcondition the no-op checks must be the editable VALUE, not the masked/hinted display string
     * (design §3: P9 applies "with clean postconditions only"). NOT model-facing — the renderer never
     * surfaces it; like [formKey]/[windowContentHash] it is internal plumbing carried on the target.
     */
    val editableText: String? = null,
    /**
     * True when this target belongs to a system/permission window (systemui/packageinstaller). System
     * UI is observable but NEVER an act target (design I-act-3 / I8/P18): the act path maps this to
     * [me.rerere.automation.cap.AuthRequest.systemUiTarget] so the guard DENYs a write on it — without
     * this provenance the guard's system-UI branch would be dead code (a grant dialog's "Allow" button
     * is in the snapshot, so the invariant must travel WITH the target, not be re-derived from coords).
     */
    val systemWindow: Boolean = false,
    /**
     * The node's raw view-resource id (`node.resourceId`) for ALL nodes, e.g. `com.app:id/pay_button`.
     * Internal-only (NOT model-facing — the renderer never surfaces a raw resource id): it is the third
     * input to the submit-class classifier (#198 slice 11) so an ICON-ONLY commit button with no
     * visible text and no contentDescription — but an id like `…:id/pay_button` — is still classified
     * submit-class and gated behind confirmation. Without it, a textless/CD-less pay button would derive
     * a null sink and tap WITHOUT confirm (the codex-found false-negative). Distinct from [formKey],
     * which is the same id but exposed (as `form=`) ONLY for editable inputs.
     */
    val viewId: String? = null,
    /** Internal provenance: the window package this target came from. */
    val sourcePackage: String = "",
    /**
     * The owning window's backend id (e.g. [android.view.accessibility.AccessibilityWindowInfo.getId]).
     * Internal-only (NOT model-facing — the renderer never surfaces it): the first axis of strict
     * [TargetBinding] matching so a fresh re-resolve dispatches only to the SAME window the grounding
     * named, never a same-shaped node that re-flowed into a different window. [UNKNOWN_WINDOW_ID] when
     * the backend could not supply it; the binding still fails closed (a known-id binding cannot match
     * an unknown-id live target and vice versa).
     */
    val windowId: Int = UNKNOWN_WINDOW_ID,
    /**
     * The node's zero-based raw child-index path from its window root (`[]` for the root, `[0]` for the
     * first child, `[0,1]` for the second child of the first child). Internal-only (NOT model-facing):
     * the structural-position axis of strict [TargetBinding] matching. Raw child indices (NOT projected
     * tid) so the path is stable across re-projections that renumber tids (a benign reflow shifts tids
     * but a keyed/bound target still resolves to the same structural node). Computed in raw child order
     * BEFORE the projection rule filters, so a non-projected container between the root and a target
     * still contributes a path slot — the live dispatch walk re-creates the same indices.
     */
    val structuralPath: List<Int> = emptyList(),
    /**
     * A SHA-256 digest of the target's structural shape (window id + package + system flag + path +
     * node class/id/desc/flags/text-length + immediate children's shape — see
     * [SnapshotProjector.computeStructuralFingerprint]). Internal-only (NOT model-facing): the
     * content axis of strict [TargetBinding] matching so a fresh re-resolve refuses a node that re-flowed
     * into the same path but with a different shape (the same-label-replacement false positive the
     * name-only re-resolve rejects). Deliberately excludes raw text BYTES (only length) so a value the
     * model never sees cannot leak through the binding, and so a same-shape text edit does not by itself
     * stale a bound dispatch.
     */
    val structuralFingerprint: String = "",
)

/**
 * The eyes-open decision-time binding for a targeted act (the hybrid tap design). Built from a
 * grounded [UiTarget] at act-decision time; the backend re-resolves it against a FRESH capture and
 * dispatches only when exactly one live node strictly matches. This replaces the old blind
 * `(stateSeq, tid)` dispatch token: a binding carries the target's structural identity (window id +
 * package + system flag + role + flags + stable keys + view id + structural path + fingerprint) so a
 * same-shaped node that re-flowed into a different window/path, or a same-label replacement, is
 * refused — while benign status-bar/SystemUI churn (which never matches any app binding) no longer
 * stale a targeted dispatch.
 *
 * Match semantics (spec §4 — STRICT):
 *  - Every targeted action matches on [windowId], [sourcePackage], [systemWindow], [role], [flags],
 *    [semanticKey], [formKey], [viewId], [structuralPath], and [structuralFingerprint].
 *  - For a tap ([requireVisibleTextMatch] == true) the [visibleText] must ALSO match exactly,
 *    including `null` (so two same-shape buttons differing only by label do not collide, and an
 *    icon-only target cannot be tapped by a binding that expected a label).
 *  - For `set_text` ([requireVisibleTextMatch] == false) the visible text is NOT part of the match
 *    (the field's value is about to change), and the requested text / [UiTarget.editableText] are
 *    NEVER carried here — a binding is a structural identity, never a payload.
 *
 * NOT model-facing by construction: none of its fields are rendered, and it is built in the pure
 * :automation core from a grounded target + a single boolean, never from model-supplied args.
 */
@Serializable
data class TargetBinding(
    val windowId: Int,
    val sourcePackage: String,
    val systemWindow: Boolean,
    val role: String,
    val flags: Set<UiFlag>,
    /** When true, [visibleText] is part of the strict match (taps only). */
    val requireVisibleTextMatch: Boolean,
    /** The grounded target's visible [UiTarget.text]; matched only when [requireVisibleTextMatch]. */
    val visibleText: String?,
    val semanticKey: String?,
    val formKey: String?,
    val viewId: String?,
    val structuralPath: List<Int>,
    val structuralFingerprint: String,
) {
    /**
     * Strict structural-identity match against a fresh [target] (spec §4). See the class kdoc for the
     * per-field rules; the load-bearing rule is that EVERY identity field is checked (no advisory
     * subset) and the visible-text axis is gated on [requireVisibleTextMatch]. Total & pure.
     */
    fun matches(target: UiTarget): Boolean {
        if (windowId != target.windowId) return false
        if (sourcePackage != target.sourcePackage) return false
        if (systemWindow != target.systemWindow) return false
        if (role != target.role) return false
        if (flags != target.flags) return false
        if (semanticKey != target.semanticKey) return false
        if (formKey != target.formKey) return false
        if (viewId != target.viewId) return false
        if (structuralPath != target.structuralPath) return false
        if (structuralFingerprint != target.structuralFingerprint) return false
        // Taps require an exact visible-text match (incl. null); set_text ignores the axis entirely.
        if (requireVisibleTextMatch && visibleText != target.text) return false
        return true
    }
}

/**
 * Build a decision-time [TargetBinding] from this grounded target. [requireVisibleTextMatch] is true
 * for a CLICK (a tap names a specific labeled element) and false for scroll / `set_text` (a scroll
 * target's label is incidental; a set_text target's value is about to change). The editable value
 * and any requested text are NEVER carried — a binding is structural identity only.
 */
fun UiTarget.toTargetBinding(requireVisibleTextMatch: Boolean): TargetBinding = TargetBinding(
    windowId = windowId,
    sourcePackage = sourcePackage,
    systemWindow = systemWindow,
    role = role,
    flags = flags,
    requireVisibleTextMatch = requireVisibleTextMatch,
    // Carried ONLY for a tap (requireVisibleTextMatch): a tap binds to a specific labeled element, so
    // its visible label is part of the identity. A scroll / set_text binding is structural identity
    // only — carrying [text] here would leak the field's current editable value (and for set_text that
    // value is about to change), so the axis is null and `matches` ignores it (spec §4 / §10).
    visibleText = if (requireVisibleTextMatch) text else null,
    semanticKey = semanticKey,
    formKey = formKey,
    viewId = viewId,
    structuralPath = structuralPath,
    structuralFingerprint = structuralFingerprint,
)

@Serializable
enum class UiFlag {
    CLICK,
    EDIT,
    SCROLL,
    CHECKED,
    /** The node is a password/obscured input. Its text is masked in projection (design I1/P1). */
    PASSWORD,
}

/**
 * How a future write verb (v2) names a target. The read path (v1) does not consume a [Selector]
 * — it is present so the act-path contract (resolve → assert → act) is stable from day one and
 * the v2 PRs are purely additive. Coordinate-free by construction.
 */
@Serializable
sealed class Selector {
    @Serializable
    data class ByTid(val tid: Int) : Selector()

    @Serializable
    data class ByText(val text: String, val role: String? = null) : Selector()

    @Serializable
    data class BySemanticKey(val semanticKey: String) : Selector()

    /**
     * Address an input field by its [UiTarget.formKey] (#198 slice 9). The projector sets `formKey`
     * ONLY for editable nodes (from the node's stable resourceId), so this is the input-field axis of
     * the selection grammar. Like [BySemanticKey] it is a STABLE-key selector: the act path re-resolves
     * it against the CURRENT grounded snapshot's tid (self-heal, I-act-9 / P14/MR2) — it is never a
     * positional bypass, because the seq+hash freshness assert still runs after the resolve (so a
     * benign reflow heals to the new tid, but a stale grounding is still rejected before any dispatch).
     */
    @Serializable
    data class ByFormKey(val formKey: String) : Selector()
}
