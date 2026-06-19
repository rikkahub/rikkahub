package me.rerere.rikkahub.service.automation

import me.rerere.automation.act.AutomationCore
import me.rerere.automation.act.ConfirmChannel
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.rikkahub.BuildConfig

/**
 * Koin-injectable indirection over the system-instantiated [AccessibilityRuntime].
 *
 * An `AccessibilityService` is constructed by the Android framework, never by Koin, so the design's
 * `single { AccessibilityRuntime }` intent cannot be taken literally (a Koin-built instance would be
 * a dead object the system never connects). The faithful minimal equivalent is this registry: a
 * Koin `single` that exposes the *live* [AccessibilityRuntime.instance] as the pure
 * [AutomationBackend] to `ChatService` / the `ui_observe` factory, plus the foreground package and
 * the kill-switch cancel hook.
 *
 * Returns null whenever the service is not connected (the user has not enabled it in system
 * accessibility settings), so the per-generation factory fails closed to an empty tool surface.
 */
class AutomationRuntimeRegistry {

    /** The live backend, or null when the accessibility service is not connected. */
    fun backend(): AutomationBackend? = AccessibilityRuntime.instance

    /**
     * A fresh observation core over the live backend, or null when unavailable. The projector is given
     * the REAL host application id ([BuildConfig.APPLICATION_ID] — which carries the `.debug` suffix on
     * debug builds) so the host-exclusion (P2/P12) actually matches the running app and the agent never
     * observes/acts on its own UI.
     */
    fun core(): AutomationCore? =
        backend()?.let { AutomationCore(it, SnapshotProjector(hostPackage = BuildConfig.APPLICATION_ID)) }

    /** Foreground package of the device, read before observing (design S2). Null when disconnected. */
    fun foregroundPackage(): String? = AccessibilityRuntime.instance?.foregroundPackage

    /**
     * The live, overlay-backed out-of-band confirmation channel for a dangerous (submit-class) act
     * (#198 slice 11), or null when the accessibility service is not connected — same fail-closed
     * pattern as [core] / [foregroundPackage]. `ChatService` threads this into the tool factory; a null
     * (disconnected) channel means a dangerous sink can never be confirmed (the caller substitutes a
     * fail-closed AlwaysDeny).
     */
    fun confirmChannel(): ConfirmChannel? = AccessibilityRuntime.instance?.confirmChannel()

    /**
     * Show the floating STOP overlay on the live service (design §7) and report whether it is
     * actually reachable. Returns `false` when the service is not connected OR the overlay could not
     * attach — the caller fails closed (revokes the lease) so `ui_observe` is never exposed without
     * a kill-switch. [onStop] trips the kill-switch (revoke active guard(s) + cancel their
     * generations). No in-flight capture is cancelled here: a capture is a child of its own
     * generation job and dies with it (I9), so a per-session stop never touches another session.
     */
    fun showKillSwitch(onStop: () -> Unit): Boolean =
        AccessibilityRuntime.instance?.showOverlay(onStop) ?: false

    /** Hide the floating STOP overlay on the live service. No-op when not connected. */
    fun hideKillSwitch() {
        AccessibilityRuntime.instance?.hideOverlay()
    }
}
