package me.rerere.automation.act

import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * The state-grounded observation loop (#187 design §5). v1 is **read-only**: it exposes only
 * [observe], which captures the backend's raw tree, projects it, and returns an authoritative,
 * freshly-grounded [UiSnapshot]. The full act path —
 *   `resolve(selector) → assert(expectedSeq + windowContentHash) → act → awaitSettle → re-snapshot`
 * — is documented here as the v2 seam but is intentionally NOT implemented (no write verb ships in
 * v1; see #198). Adding it is purely additive: a new `act(...)` method beside [observe].
 *
 * Invariants this enforces (properties P10/P11/P12):
 *  - **P11** the observed `stateSeq` is monotonic and never decreases across observes. The core is
 *    the source of truth for the observed sequence: it tracks the last value and rejects a backend
 *    that regresses (a regressing a11y backend is a bug, not a state to silently accept). It does
 *    NOT fabricate forward progress — a stale-but-equal seq stays equal (the TOCTOU close for the
 *    v2 act path is the content-hash in [AutomationBackend.windowContentHash], not a faked bump).
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
    suspend fun observe(): UiSnapshot {
        val raw = backend.snapshotRawTree()
        val snapshot = projector.project(raw)

        // P11: enforce non-decreasing observed sequence. A backend that hands back a lower seq than
        // we've already seen is malfunctioning; fail loud rather than let the model act on a tree
        // that appears to have travelled backwards in time.
        val previous = lastObservedSeq.get()
        check(snapshot.stateSeq >= previous) {
            "backend stateSeq regressed: got ${snapshot.stateSeq}, last observed $previous"
        }
        lastObservedSeq.set(snapshot.stateSeq)

        return snapshot
    }

    /** True once the current foreground is the host app (P12) — caller pauses the agent loop. */
    fun isHostForeground(snapshot: UiSnapshot): Boolean =
        snapshot.screenState == ScreenState.FOREGROUND_IS_HOST
}
