package me.rerere.automation.backend

import me.rerere.automation.observe.TargetBinding
import me.rerere.automation.observe.UiSnapshot
import me.rerere.automation.observe.UNKNOWN_WINDOW_ID

/**
 * The seam between the pure :automation core and a concrete UI source (the real Android
 * AccessibilityService in :app, or [FakeBackend] for PBT). The backend returns *domain* results
 * only — it never imports the AI `Tool`/`UIMessagePart` types (design I3/I10). Mapping a snapshot
 * to a chat message part happens exclusively in :app/UiAutomationTools.
 *
 * Concurrency (design §6, GenerationHandler.kt:353): a tool's `execute` runs under
 * `flowOn(Dispatchers.IO)`. A real backend therefore MUST marshal to its service/main thread
 * internally and serialize concurrent calls; [snapshotRawTree] is `suspend` precisely so an
 * implementation can hop threads without blocking the caller.
 */
interface AutomationBackend {
    /**
     * Capture the current window forest as a pure [RawTree] (multi-window: application + system,
     * design §4). The returned tree is a value snapshot — it does not hold live AccessibilityNode
     * references, so the core can project it off the service thread.
     */
    suspend fun snapshotRawTree(): RawTree

    /**
     * Structural/content hash of the window identified by [stateSeq]. Retained for the read-path
     * grounding stamp; the eyes-open act path no longer gates on it (a strict [TargetBinding]
     * re-resolve replaces the old seq+hash TOCTOU close). Kept on the seam so the observe path and
     * existing capture plumbing are untouched.
     */
    fun windowContentHash(stateSeq: Long): String

    /**
     * The backend's current monotonic sequence. Retained for the observe path's monotonic guard and
     * the grounding stamp; the eyes-open act path no longer asserts it before dispatch (the binding
     * match is the freshness signal now).
     */
    fun currentStateSeq(): Long

    /**
     * Re-resolve a decision-time [BindingRequest] against a FRESH live capture and return the unique
     * matching target, or a mismatch (zero/multiple matches) carrying the fresh snapshot for a re-decide.
     * The core calls this for the acts that need a fresh target BEFORE dispatch: `set_text` (the P9
     * no-op compares the fresh editable value, never the stale grounded one) and a submit-class tap
     * (the confirm prompt shows the fresh resolved label). A non-dangerous scroll/tap skips this and
     * goes straight to [perform], which re-resolves and dispatches atomically.
     *
     * Authorize-before-backend (S2): the core ADMITs the capability BEFORE calling this, and routes it
     * through [me.rerere.automation.cap.CapabilityGuard.guardInFlight] so a revoke cancels it.
     */
    suspend fun resolveBinding(request: BindingRequest): BindingResolution

    /**
     * Dispatch a write action against the live UI. For a [PerformAction.Node] / [PerformAction.SetText]
     * the backend re-resolves [PerformAction.Node.binding] / [PerformAction.SetText.binding] against a
     * FRESH capture ATOMICALLY with the dispatch (under the same capture/dispatch lock) and performs
     * the verb on the UNIQUE matching node — zero/multiple matches ⇒ [PerformResult.BindingMismatch]
     * with the fresh snapshot and NO mutation. A [PerformAction.Global] maps to `performGlobalAction`.
     *
     * The [PerformResult] is a best-effort *dispatch ack*, NOT a success signal: ACTION_CLICK/SET_TEXT
     * can silently no-op on WebView/custom-IME nodes and still report either outcome (design D4). Act
     * success is established by the core's post-act re-snapshot (the screen actually changed), never
     * by trusting this return. The core calls this ONLY after `resolve → authorize`, and routes it
     * through the capability's revocation token so a kill-switch cancels it in flight (I-act-10/P20).
     */
    suspend fun perform(action: PerformAction): PerformResult

    /**
     * Block until the UI settles after an act, or a hard cap elapses (design §1 step 6 / D3 / P13) —
     * a quiet-window debounce over content-change events, NEVER a fixed sleep. The pure decision
     * contract is [me.rerere.automation.act.SettlePolicy]; the real backend implements the online
     * form over its event stream, [FakeBackend] returns immediately (deterministic PBT).
     */
    suspend fun awaitSettle()
}

/**
 * A request to re-resolve a decision-time [TargetBinding] against a fresh capture. [allowedPackages]
 * / [includeHost] mirror the projection policy so the re-resolve walks exactly the windows the
 * grounding observe would have projected.
 */
data class BindingRequest(
    val binding: TargetBinding,
    val allowedPackages: Set<String>,
    val includeHost: Boolean = false,
)

/**
 * The outcome of [AutomationBackend.resolveBinding]. [Unique] carries the fresh snapshot AND the one
 * strictly-matching target (so the core can read its fresh editable value / label without a second
 * capture); [Mismatch] carries the fresh snapshot so the tool layer can re-ground the model on the
 * current screen and steer a re-decide.
 */
sealed interface BindingResolution {
    data class Unique(val snapshot: UiSnapshot, val target: me.rerere.automation.observe.UiTarget) : BindingResolution
    data class Mismatch(val snapshot: UiSnapshot) : BindingResolution
}

/**
 * The outcome of [AutomationBackend.perform]. [Dispatched] is a best-effort ack only (D4 — act success
 * is the core's post-act re-snapshot); [BindingMismatch] means the bound target did not resolve to
 * exactly one live node (zero/multiple) and nothing was mutated (the core surfaces it as
 * [me.rerere.automation.act.ActOutcome.StaleState] with the fresh snapshot when the foreground is
 * still the authorized surface); [DispatchFailed] means the verb was rejected by the framework
 * (e.g. performAction returned false) — the core surfaces it as
 * [me.rerere.automation.act.ActOutcome.StaleState] with NO snapshot (no fresh re-snapshot is
 * informative when the verb itself failed), so the model must re-observe and re-decide.
 */
sealed interface PerformResult {
    data object Dispatched : PerformResult
    data class BindingMismatch(val snapshot: UiSnapshot) : PerformResult
    data object DispatchFailed : PerformResult
}

/**
 * Pure model of an accessibility window forest at one instant. No Android types, no live node
 * handles — a backend converts its native tree into this so the core stays JVM-unit-testable.
 *
 * @param stateSeq the backend's monotonic sequence at capture time.
 * @param foregroundPkg the package owning the foreground window.
 * @param windows one [RawWindow] per visible window (app + system dialogs).
 * @param contentHash the TOCTOU token computed from the SAME capture instant as [windows] (the
 *   active window's structural fold). [me.rerere.automation.act.AutomationCore.observe] stamps it
 *   onto the snapshot instead of a SECOND live [AutomationBackend.windowContentHash] read, so the
 *   grounding's nodes and its token describe one instant (gate finding: the token must not be built
 *   non-atomically). A bare/non-grounding capture leaves it `""`; the active-window definition must
 *   match [AutomationBackend.windowContentHash] so the act-assert's live re-read compares like-for-like.
 */
data class RawTree(
    val stateSeq: Long,
    val foregroundPkg: String,
    val windows: List<RawWindow>,
    val contentHash: String = "",
)

/** One window in the forest. [secure] means FLAG_SECURE — its contents must not be projected. */
data class RawWindow(
    val pkg: String,
    val secure: Boolean = false,
    /** True for system/permission UI (systemui, packageinstaller). Observable but not actionable. */
    val systemWindow: Boolean = false,
    val root: RawNode?,
    /**
     * The backend's window id (e.g. [android.view.accessibility.AccessibilityWindowInfo.getId]). Carries
     * into [UiTarget.windowId] so a strict [TargetBinding] can name the SAME window across a re-resolve.
     * [UNKNOWN_WINDOW_ID] when the backend could not supply it.
     */
    val windowId: Int = UNKNOWN_WINDOW_ID,
)

/**
 * One accessibility node. The fields here are exactly what the projection rule needs (design §4:
 * include iff `(visible && hasArea) || hasId || hasText`); raw coordinates are intentionally NOT
 * carried beyond the boolean [hasArea], so they can never leak into a [me.rerere.automation.observe.UiTarget].
 */
data class RawNode(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val visible: Boolean = true,
    /** Whether the node has a non-empty on-screen bounding box. */
    val hasArea: Boolean = true,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val children: List<RawNode> = emptyList(),
    /**
     * The owning window's backend id, so the structural fingerprint and [TargetBinding] can name the
     * same window. [UNKNOWN_WINDOW_ID] when not supplied; the projector also receives the window-level
     * id and prefers it, falling back to this per-node id only when the window id was unavailable.
     */
    val windowId: Int = UNKNOWN_WINDOW_ID,
) {
    val hasId: Boolean get() = !resourceId.isNullOrEmpty()
    val hasText: Boolean get() = !text.isNullOrEmpty() || !contentDescription.isNullOrEmpty()
}
