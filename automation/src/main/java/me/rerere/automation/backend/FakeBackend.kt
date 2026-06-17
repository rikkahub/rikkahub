package me.rerere.automation.backend

import kotlinx.coroutines.CompletableDeferred
import me.rerere.automation.observe.SnapshotProjector

/**
 * Deterministic, in-memory [AutomationBackend] for unit/PBT (design §8, the FakeBackend that
 * every P1–P25 / S1 / S2 / MBT property runs over). No threads, no Android — the tree, stateSeq,
 * foreground package and content hashes are all settable, so a property generator drives exactly
 * the topology it wants and asserts on the projection/decision with no flakiness.
 *
 * It also supports asserting in-flight cancellation (design I9 / P20): [snapshotRawTree] can be
 * made to suspend until [releaseGate] is called, so a test can launch an observe, revoke the
 * capability while the call is parked, and assert the coroutine is cancelled rather than completing.
 */
class FakeBackend(
    rawTree: RawTree = RawTree(stateSeq = 0L, foregroundPkg = "com.example.app", windows = emptyList()),
) : AutomationBackend {
    @Volatile
    var rawTree: RawTree = rawTree

    /** Per-stateSeq content hash; defaults to the stateSeq string when not explicitly set. */
    private val contentHashes = HashMap<Long, String>()

    /** Counts backend hits — lets a property assert "exactly one capture per observe", etc. */
    @Volatile
    var snapshotCount: Int = 0
        private set

    /**
     * When non-null, [snapshotRawTree] AND [perform] await this gate before proceeding (in-flight
     * cancel tests). An act parks at its [perform] step, so a test can revoke mid-act and assert the
     * coroutine is cancelled rather than completing (I-act-10 / P20 extended).
     */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Completes the instant [perform] is ENTERED — BEFORE it parks on [gate]. Lets an in-flight test
     * wait deterministically until the act is parked in [perform], then revoke / advance the seq /
     * switch the foreground, instead of racing on a fixed `yield()` count (the latter is scheduler-
     * dependent and flaked the P20 act test in CI — the coroutine on Dispatchers.Default had not yet
     * reached the park when the fixed yields elapsed).
     */
    @Volatile
    var performEntered: CompletableDeferred<Unit>? = null

    /**
     * Completes the instant [snapshotRawTree] is ENTERED — BEFORE it parks on [gate]. The observe-path
     * analogue of [performEntered]: lets an in-flight observe test wait deterministically until the
     * capture is parked, then revoke, instead of racing on a fixed `yield()` count (which flaked the
     * `ui_observe` revoke test in CI for the same scheduler-dependent reason).
     */
    @Volatile
    var snapshotEntered: CompletableDeferred<Unit>? = null

    /** Every [perform] call, in order — lets a property assert "perform happened / never happened". */
    val performed = ArrayList<PerformAction>()

    /** Counts [awaitSettle] calls — a property can assert settle runs exactly once per act. */
    @Volatile
    var settleCount: Int = 0
        private set

    /** Last window that matched during the projected-node walk in {@link perform}. */
    @Volatile
    var lastPerformedWindow: String? = null
        private set

    override suspend fun snapshotRawTree(): RawTree {
        snapshotEntered?.complete(Unit) // signal "parked in snapshotRawTree" before the gate (deterministic in-flight tests)
        gate?.await()
        snapshotCount++
        // Stamp the TOCTOU token atomically with the tree (the real backend computes it inside its
        // capture lock). It mirrors windowContentHash so the act-assert's live re-read compares
        // like-for-like: a later setContentHash for this seq (a dropped-event case) then diverges from
        // this captured value and the assert catches it (the assert-both property).
        val t = rawTree
        return t.copy(contentHash = windowContentHash(t.stateSeq))
    }

    override fun windowContentHash(stateSeq: Long): String =
        contentHashes[stateSeq] ?: stateSeq.toString()

    override fun currentStateSeq(): Long = rawTree.stateSeq

    override suspend fun perform(action: PerformAction): Boolean {
        performEntered?.complete(Unit) // signal "parked in perform" before the gate (deterministic in-flight tests)
        gate?.await()
        // Mirror the real backend's dispatch-time freshness re-check (AccessibilityRuntime.perform):
        // a node action carries the stateSeq the core asserted; if the live seq advanced AFTER that
        // assert but BEFORE this dispatch (a WINDOW_STATE/CONTENT event in the gap — the gate lets a
        // test inject exactly that race), the grounding moved under us, so do NOT dispatch — return
        // false (best-effort no-op ack; the core's re-snapshot is ground truth, D4 / I-act-1 / MR3).
        // Both node-targeted variants (scroll Node + slice-9 SetText) carry the asserted stateSeq; an
        // event in the assert→dispatch gap makes the carried seq stale, so refuse rather than write the
        // wrong tree (I-act-1 / MR3 / D4). Global nav has no node target and is exempt.
        val carriedStaleSeq = when (action) {
            is PerformAction.Node -> action.stateSeq != rawTree.stateSeq
            is PerformAction.SetText -> action.stateSeq != rawTree.stateSeq
            is PerformAction.Global -> false
        }
        if (carriedStaleSeq) {
            lastPerformedWindow = null
            return false
        }

        val dispatched = when (action) {
            is PerformAction.Global -> {
                performed.add(action)
                true
            }
            is PerformAction.Node -> {
                performOnProjectedNode(action.tid, action.allowedPackages) { true }.also { dispatched ->
                    if (dispatched) {
                        performed.add(action)
                    }
                }
            }
            is PerformAction.SetText -> {
                performOnProjectedNode(action.tid, action.allowedPackages) { true }.also { dispatched ->
                    if (dispatched) {
                        performed.add(action)
                    }
                }
            }
        }
        if (!dispatched) {
            lastPerformedWindow = null
            return false
        }

        // A real act changes the screen ⇒ the backend's sequence advances. Modelling that here keeps
        // tids turn-scoped (the post-act re-snapshot sees a fresh seq, so the old grounding is stale
        // for the NEXT act) without a test having to inject the transition by hand.
        rawTree = rawTree.copy(stateSeq = rawTree.stateSeq + 1)
        return true
    }

    private fun performOnProjectedNode(
        tid: Int,
        allowedPackages: Set<String>,
        perform: (RawNode) -> Boolean,
    ): Boolean {
        val cursor = intArrayOf(0)
        for (window in rawTree.windows) {
            if (!isWindowAllowed(window.pkg, window.systemWindow, allowedPackages)) continue
            if (window.secure || window.root == null) continue
            if (walkAndPerform(window.root, tid, cursor, perform) == true) {
                lastPerformedWindow = window.pkg
                return true
            }
        }
        return false
    }

    private fun walkAndPerform(
        node: RawNode,
        tid: Int,
        cursor: IntArray,
        perform: (RawNode) -> Boolean,
    ): Boolean? {
        if (isProjectedTarget(node)) {
            if (cursor[0] == tid) {
                cursor[0]++
                return perform(node)
            }
            cursor[0]++
        }
        for (child in node.children) {
            walkAndPerform(child, tid, cursor, perform)?.let { return it }
        }
        return null
    }

    private fun isProjectedTarget(node: RawNode): Boolean =
        (node.visible && node.hasArea) || node.hasId || node.hasText

    private fun isWindowAllowed(pkg: String, systemWindow: Boolean, allowedPackages: Set<String>): Boolean =
        SnapshotProjector.isWindowEligible(pkg, systemWindow, allowedPackages)

    override suspend fun awaitSettle() {
        settleCount++
    }

    // --- test-only mutators (deterministic substrate control) ---

    /** Advance the sequence (simulate a WINDOW_STATE/CONTENT event). */
    fun injectTransition(newForegroundPkg: String? = null, newWindows: List<RawWindow>? = null) {
        rawTree = rawTree.copy(
            stateSeq = rawTree.stateSeq + 1,
            foregroundPkg = newForegroundPkg ?: rawTree.foregroundPkg,
            windows = newWindows ?: rawTree.windows,
        )
    }

    fun setForeground(pkg: String) {
        rawTree = rawTree.copy(foregroundPkg = pkg)
    }

    fun setContentHash(stateSeq: Long, hash: String) {
        contentHashes[stateSeq] = hash
    }

    /** Arm the suspension gate so the next [snapshotRawTree] parks until [releaseGate]. */
    fun armGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gate = it }

    fun releaseGate() {
        gate?.complete(Unit)
        gate = null
    }
}
