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
     * write lands. v1 is read-only, so this is contract-only here; the hook ships from day one so
     * the real backend implements it before any write verb exists.
     */
    fun windowContentHash(stateSeq: Long): String
}

/**
 * Pure model of an accessibility window forest at one instant. No Android types, no live node
 * handles — a backend converts its native tree into this so the core stays JVM-unit-testable.
 *
 * @param stateSeq the backend's monotonic sequence at capture time.
 * @param foregroundPkg the package owning the foreground window.
 * @param windows one [RawWindow] per visible window (app + system dialogs).
 */
data class RawTree(
    val stateSeq: Long,
    val foregroundPkg: String,
    val windows: List<RawWindow>,
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
