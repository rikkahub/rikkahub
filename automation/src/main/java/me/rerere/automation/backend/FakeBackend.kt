package me.rerere.automation.backend

import kotlinx.coroutines.CompletableDeferred

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

    /** When non-null, [snapshotRawTree] awaits this gate before returning (in-flight-cancel tests). */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun snapshotRawTree(): RawTree {
        gate?.await()
        snapshotCount++
        return rawTree
    }

    override fun windowContentHash(stateSeq: Long): String =
        contentHashes[stateSeq] ?: stateSeq.toString()

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
