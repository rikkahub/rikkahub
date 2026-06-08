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
}
