package me.rerere.automation.backend

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
     * Structural/content hash of the window identified by [stateSeq], for the TOCTOU close on the
     * v2 *act* path (design §5, gate finding #7). A `stateSeq` compare alone is insufficient
     * because AccessibilityEvents are best-effort/coalesced — a dropped WINDOW_STATE_CHANGED leaves
     * stateSeq stale-but-equal. The v2 act-assert checks BOTH expectedSeq and this hash before any
     * write lands.
     */
    fun windowContentHash(stateSeq: Long): String

    /**
     * The backend's current monotonic sequence WITHOUT capturing the tree (#198 slice 8). The act
     * assert reads this plus [windowContentHash] to verify the grounding is still fresh before a
     * dispatch — a cheap metadata check, never a content capture (so it does not violate the
     * guard-before-capture rule S2; the protected operations are [snapshotRawTree] and [perform]).
     */
    fun currentStateSeq(): Long

    /**
     * Dispatch a write action against the live UI (#198 slice 8, design §1 step 5 / D1). The core
     * calls this ONLY after `resolve → assert(seq+hash) → authorize` all pass, and routes it through
     * the capability's revocation token so a kill-switch cancels it in flight (I-act-10/P20).
     *
     * The boolean return is a best-effort *dispatch ack*, NOT a success signal: `performAction` can
     * silently no-op on WebView/custom-IME nodes and still return either value (design D4). Act
     * success is established by the core's post-act re-snapshot (the screen actually changed), never
     * by trusting this return. Implementations marshal to their service thread like [snapshotRawTree].
     */
    suspend fun perform(action: PerformAction): Boolean

    /**
     * Block until the UI settles after an act, or a hard cap elapses (design §1 step 6 / D3 / P13) —
     * a quiet-window debounce over content-change events, NEVER a fixed sleep. The pure decision
     * contract is [me.rerere.automation.act.SettlePolicy]; the real backend implements the online
     * form over its event stream, [FakeBackend] returns immediately (deterministic PBT).
     */
    suspend fun awaitSettle()
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
) {
    val hasId: Boolean get() = !resourceId.isNullOrEmpty()
    val hasText: Boolean get() = !text.isNullOrEmpty() || !contentDescription.isNullOrEmpty()
}
