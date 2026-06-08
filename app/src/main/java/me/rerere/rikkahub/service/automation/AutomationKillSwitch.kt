package me.rerere.rikkahub.service.automation

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide kill-switch dispatch for the #187 v1 automation runtime (design §7).
 *
 * Decouples the two reachable STOP surfaces from the holder of the actual authority:
 *  - the floating [KillSwitchOverlay] STOP (a `TYPE_ACCESSIBILITY_OVERLAY`, reachable from any app);
 *  - the in-app generation Stop (the existing `ChatService.stopGeneration` path).
 *
 * Both ultimately revoke every active capability guard. `ChatService` registers the revoke action
 * here on start; the overlay (which lives in the system-instantiated [AccessibilityRuntime], not in
 * Koin) fires [trip] without needing a handle to `ChatService`. The registered action must call
 * `CapabilityGuard.revoke()` (deny future authorize) AND cancel in-flight work (design I9).
 *
 * Koin `single`. Idempotent and thread-safe: [trip] may fire from the overlay/UI thread while a
 * generation runs on `Dispatchers.IO`.
 */
class AutomationKillSwitch {

    private val handlers = CopyOnWriteArrayList<() -> Unit>()

    /** Register a revoke action (ChatService). Returns a handle to [unregister] on teardown. */
    fun register(onTrip: () -> Unit): Any {
        handlers.add(onTrip)
        return onTrip
    }

    fun unregister(handle: Any) {
        @Suppress("UNCHECKED_CAST")
        (handle as? (() -> Unit))?.let { handlers.remove(it) }
    }

    /** Fire every registered revoke action. Safe to call when nothing is active (no-op). */
    fun trip() {
        handlers.forEach { it() }
    }
}
