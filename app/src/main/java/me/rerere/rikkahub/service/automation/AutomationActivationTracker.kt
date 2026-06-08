package me.rerere.rikkahub.service.automation

import kotlin.uuid.Uuid

/**
 * Refcounts the set of conversations that currently have a live UI-automation lease, and owns the
 * single process-global side-effects keyed off "is ANY automation session active": the floating
 * STOP overlay (#187 design §7) and (via the supplied hooks) nothing else.
 *
 * Why this exists (the root cause it fixes): the overlay is one shared
 * `TYPE_ACCESSIBILITY_OVERLAY` over a multi-session system (`sessions` is a `ConcurrentHashMap`, and
 * the same automation-enabled assistant can drive several conversations at once). Driving that one
 * overlay from a per-completion boolean meant conversation A finishing called `hide()` while
 * conversation B was still authorized — the any-app kill-switch vanished for B (a real safety
 * regression). Tracking the active set and toggling the overlay only on the 0→1 / 1→0 edges keeps
 * the invariant: **the STOP overlay is shown iff at least one automation session is active.**
 *
 * Keyed by `conversationId` (not a bare counter) so re-entrancy is idempotent: a conversation that
 * somehow activates twice still counts once, and its release is exact. Thread-safe — [activate] runs
 * on the generation coroutine while [deactivate] can race a kill-switch on another thread.
 *
 * The overlay show/hide is injected ([showOverlay] / [hideOverlay]) so this class stays pure-JVM and
 * unit-testable with no Android types. [showOverlay] returns whether the overlay actually displayed,
 * so the caller can **fail closed** (revoke the lease) when no STOP is reachable — `ui_observe` must
 * never be exposed without a reachable kill-switch (design I9/§7).
 */
class AutomationActivationTracker(
    private val showOverlay: () -> Boolean,
    private val hideOverlay: () -> Unit,
) {
    private val active = LinkedHashSet<Uuid>()

    /**
     * Mark [conversationId] as an active automation session. On the 0→1 edge this shows the STOP
     * overlay. Returns `true` iff a STOP kill-switch is reachable for this session (either it was
     * already up for another session, or it just displayed). A `false` return means the overlay
     * could not be shown and the caller MUST fail closed — revoke the lease so `ui_observe` is not
     * exposed without a kill-switch.
     */
    @Synchronized
    fun activate(conversationId: Uuid): Boolean {
        val wasEmpty = active.isEmpty()
        active.add(conversationId)
        if (!wasEmpty) return true // overlay already up for another live session
        val shown = showOverlay()
        if (!shown) active.remove(conversationId) // 0→1 failed: stay empty, report fail-closed
        return shown
    }

    /**
     * Release [conversationId]'s automation session. On the 1→0 edge (the LAST active session ends)
     * this hides the overlay; while other sessions remain active the overlay stays up. Idempotent:
     * releasing a session that is not active is a no-op.
     */
    @Synchronized
    fun deactivate(conversationId: Uuid) {
        if (!active.remove(conversationId)) return
        if (active.isEmpty()) hideOverlay()
    }

    /** Active automation conversation count — for assertions/telemetry. */
    @get:Synchronized
    val activeCount: Int get() = active.size
}
