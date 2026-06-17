package me.rerere.automation.observe

import kotlinx.serialization.Serializable

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
     * The backend content hash at capture time — the TOCTOU token for the v2 act path (design §5 /
     * #198 §1 step 2). Populated by [me.rerere.automation.act.AutomationCore.observe]; the projector
     * leaves it `""` (a bare projection is not grounded against a live backend). An act re-checks
     * `windowContentHash(stateSeq)` against this value so a dropped `AccessibilityEvent` that leaves
     * `stateSeq` stale-but-equal is still caught. NOT model-facing — :app's snapshot renderer never
     * surfaces it; it is internal plumbing carried on the snapshot so the act can verify the grounding.
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
