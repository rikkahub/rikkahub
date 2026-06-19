package me.rerere.automation.backend

import kotlinx.coroutines.CompletableDeferred
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiSnapshot

/**
 * Deterministic, in-memory [AutomationBackend] for unit/PBT (design §8, the FakeBackend that
 * every P1–P25 / S1 / S2 / MBT property runs over). No threads, no Android — the tree, stateSeq,
 * and foreground package are all settable, so a property generator drives exactly the topology it
 * wants and asserts on the projection/decision with no flakiness.
 *
 * The eyes-open hybrid tap design: [resolveBinding] / [perform] re-project the current [rawTree]
 * with the same [SnapshotProjector] the grounding observe used and match the decision-time
 * [TargetBinding] STRICTLY (exactly one match ⇒ resolve/dispatch; zero/multiple ⇒ [BindingResolution.Mismatch] /
 * [PerformResult.BindingMismatch] with the fresh snapshot, no mutation). This mirrors the real
 * [me.rerere.automation.backend.AutomationBackend] contract exactly — a binding that re-resolves
 * to one node here re-resolves to one node there too — so the property suite proves the same
 * "exactly one strict match" invariant the live dispatch relies on.
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

    private val projector = SnapshotProjector()

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
     * wait deterministically until the act is parked in [perform], then revoke / mutate the tree,
     * instead of racing on a fixed `yield()` count (the latter is scheduler-dependent and flaked the
     * P20 act test in CI — the coroutine on Dispatchers.Default had not yet reached the park when the
     * fixed yields elapsed).
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
        // capture lock). Retained for the observe grounding stamp; the eyes-open act path no longer
        // asserts it (a strict TargetBinding re-resolve replaces the seq+hash TOCTOU close).
        val t = rawTree
        return t.copy(contentHash = windowContentHash(t.stateSeq))
    }

    override fun windowContentHash(stateSeq: Long): String =
        contentHashes[stateSeq] ?: stateSeq.toString()

    override fun currentStateSeq(): Long = rawTree.stateSeq

    override suspend fun resolveBinding(request: BindingRequest): BindingResolution {
        val snapshot = projectFresh(request.allowedPackages, request.includeHost)
        val matches = snapshot.targets.filter { request.binding.matches(it) }
        return when (matches.size) {
            1 -> BindingResolution.Unique(snapshot, matches.single())
            else -> BindingResolution.Mismatch(snapshot)
        }
    }

    override suspend fun perform(action: PerformAction): PerformResult {
        performEntered?.complete(Unit) // signal "parked in perform" before the gate (deterministic in-flight tests)
        gate?.await()
        return when (action) {
            is PerformAction.Global -> {
                performed.add(action)
                // A real act changes the screen ⇒ the backend's sequence advances. Modelling that here
                // keeps tids turn-scoped (the post-act re-snapshot sees a fresh seq) without a test
                // having to inject the transition by hand.
                rawTree = rawTree.copy(stateSeq = rawTree.stateSeq + 1)
                lastPerformedWindow = null
                PerformResult.Dispatched
            }

            is PerformAction.Node -> dispatchBound(
                binding = action.binding,
                allowedPackages = action.allowedPackages,
                includeHost = action.includeHost,
                record = { performed.add(action) },
            )

            is PerformAction.SetText -> dispatchBound(
                binding = action.binding,
                allowedPackages = action.allowedPackages,
                includeHost = action.includeHost,
                record = { performed.add(action) },
            )
        }
    }

    /**
     * Strict fresh re-resolve + dispatch: project the current [rawTree] with the SAME policy the
     * grounding observe used, find targets whose [TargetBinding] strictly matches, and dispatch ONLY
     * when exactly one matches. Zero/multiple matches ⇒ [PerformResult.BindingMismatch] with the fresh
     * snapshot and NO mutation (the model must re-observe, never replay) — the load-bearing invariant
     * the live dispatch also enforces. A unique match advances the seq (a real act changes the screen)
     * so the post-act re-snapshot sees a fresh seq.
     */
    private fun dispatchBound(
        binding: me.rerere.automation.observe.TargetBinding,
        allowedPackages: Set<String>,
        includeHost: Boolean,
        record: () -> Unit,
    ): PerformResult {
        val snapshot = projectFresh(allowedPackages, includeHost)
        val matches = snapshot.targets.filter { binding.matches(it) }
        if (matches.size != 1) {
            lastPerformedWindow = null
            return PerformResult.BindingMismatch(snapshot)
        }
        record()
        lastPerformedWindow = matches.single().sourcePackage
        rawTree = rawTree.copy(stateSeq = rawTree.stateSeq + 1)
        return PerformResult.Dispatched
    }

    /**
     * Project the current [rawTree] into a fresh [UiSnapshot] under the given disclosure policy,
     * stamping [windowContentHash] exactly as [snapshotRawTree] and the real Android backend do — so a
     * mismatch / P9 fresh snapshot is byte-identical in shape to a grounding capture (parity).
     */
    private fun projectFresh(allowedPackages: Set<String>, includeHost: Boolean): UiSnapshot =
        projector.project(rawTree, allowedPackages, includeHost)
            .copy(windowContentHash = windowContentHash(rawTree.stateSeq))

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
