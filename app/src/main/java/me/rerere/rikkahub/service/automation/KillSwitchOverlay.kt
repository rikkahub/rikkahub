package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R

/**
 * The floating STOP kill-switch (design §7), reachable from ANY foreground app while automation is
 * active. Implemented as a `TYPE_ACCESSIBILITY_OVERLAY` attached by [AccessibilityRuntime] — that
 * window type is granted together with the accessibility service, so it needs NO
 * `SYSTEM_ALERT_WINDOW` runtime permission (keeps the permission surface minimal).
 *
 * Tapping STOP fires [onStop], which `ChatService` wires to revoke every active capability guard and
 * cancel in-flight work via [AutomationKillSwitch]. The overlay is shown only while a lease is live
 * and removed otherwise, so it never lingers.
 *
 * Dual-mode (#198 slice 11): besides the always-present STOP button, it can raise a TRANSIENT confirm
 * affordance ([requestConfirm]) for a dangerous (submit-class) act — a second overlay view with
 * Confirm/Deny buttons that suspends until the user taps one (or a timeout elapses, fail-closed). The
 * STOP button's window is never touched by the confirm flow; the two are independent views on the same
 * `TYPE_ACCESSIBILITY_OVERLAY` window type so both stay reachable from ANY foreground app.
 */
class KillSwitchOverlay(
    private val service: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private val windowManager = service.getSystemService<WindowManager>()
    private var view: View? = null

    /**
     * Attach the floating STOP button. Returns `true` iff the overlay is actually displayed
     * afterward (already up, or newly added). A `false` return is load-bearing: the caller fails
     * closed and revokes the automation lease so `ui_observe` is never exposed without a reachable
     * kill-switch (design I9/§7). An `addView` failure (e.g. `BadTokenException`) is logged rather
     * than silently swallowed — a missing kill-switch is a safety event, not a benign no-op.
     */
    fun show(): Boolean {
        if (view != null) return true
        val wm = windowManager ?: return false
        val button = Button(service).apply {
            text = service.getString(R.string.automation_kill_switch_stop)
            setBackgroundColor("#B00020".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 96
        }
        return runCatching { wm.addView(button, params) }
            .onSuccess { view = button }
            .onFailure { Log.e(TAG, "STOP overlay addView failed; automation must fail closed", it) }
            .isSuccess
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeView(current) }
    }

    /**
     * Raise a transient Confirm/Deny affordance for a DANGEROUS (submit-class) act (#198 slice 11) and
     * suspend until the user decides — returning `true` on Confirm, `false` on Deny OR on timeout
     * (fail-closed). The confirm view is a SEPARATE overlay view from the STOP button (same window
     * type), so STOP stays reachable throughout and the confirm flow never disturbs it.
     *
     * Contract this honors:
     *  - The whole wait is bounded by [CONFIRM_TIMEOUT_MS]; a timeout returns `false` so a dangerous
     *    act is never silently confirmed by inaction (the channel owns the timeout — the core never
     *    times out itself).
     *  - The confirm view is added on the service main thread (WindowManager is main-thread-only) and
     *    REMOVED on EVERY terminal path — confirm, deny, timeout, AND cancellation (a kill-switch revoke
     *    cancelling the act's job) — via the `finally`, so it can never leak. Removal hops back to main.
     *  - Cancellable: `withTimeoutOrNull` + the suspending `decision.await()` propagate cancellation, so
     *    a revoke that cancels the owning job tears the prompt down (the `finally` removes the view).
     *
     * A `false` return on the inability to ADD the view (WindowManager null / addView threw) is
     * load-bearing: a dangerous act whose confirm surface cannot be shown must fail closed (deny).
     */
    suspend fun requestConfirm(app: String, verb: String, label: String?): Boolean {
        val wm = windowManager ?: return false
        val decision = CompletableDeferred<Boolean>()
        // The whole add → await → remove lives inside ONE main-thread frame so there is NO
        // cancellation-observable gap between "view added" and "finally that removes it": once the view
        // is attached, the `finally` below ALWAYS runs (the await is the only suspension point, and the
        // finally is NonCancellable). buildConfirmView/addView/removeView are all main-thread-only.
        return withContext(Dispatchers.Main) {
            val container = buildConfirmView(app, verb, label, decision)
            val added = runCatching { wm.addView(container, confirmParams()) }
                .onFailure { Log.e(TAG, "confirm overlay addView failed; failing closed (deny)", it) }
                .isSuccess
            if (!added) return@withContext false // could not show the prompt ⇒ fail closed (deny)
            try {
                // Park until the user taps, bounded by the hard timeout; null (timeout) ⇒ fail-closed.
                withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { decision.await() } ?: false
            } finally {
                // Remove on EVERY terminal path (confirm/deny/timeout/cancellation). NonCancellable so a
                // cancellation that triggered this finally still completes the removal (no leaked view).
                withContext(NonCancellable) { runCatching { wm.removeView(container) } }
            }
        }
    }

    /** The Confirm/Deny prompt: a label line + two buttons, each completing [decision]. */
    private fun buildConfirmView(
        app: String,
        verb: String,
        label: String?,
        decision: CompletableDeferred<Boolean>,
    ): View {
        val prompt = service.getString(R.string.automation_confirm_prompt, app, verb, label ?: "")
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1F1F1F".toColorInt())
            setPadding(24, 16, 24, 16)
            addView(
                TextView(service).apply {
                    text = prompt
                    setTextColor(Color.WHITE)
                },
            )
            addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        Button(service).apply {
                            text = service.getString(R.string.automation_confirm_deny)
                            setOnClickListener { decision.complete(false) }
                        },
                    )
                    addView(
                        Button(service).apply {
                            text = service.getString(R.string.automation_confirm_allow)
                            setBackgroundColor("#B00020".toColorInt())
                            setTextColor(Color.WHITE)
                            setOnClickListener { decision.complete(true) }
                        },
                    )
                },
            )
        }
    }

    private fun confirmParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        // NOT focusable would block the buttons from receiving taps; allow touch but not steal IME focus.
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.CENTER
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private companion object {
        const val TAG = "KillSwitchOverlay"

        /**
         * Hard cap on the dangerous-sink confirm wait (#198 slice 11). A timeout returns false
         * (fail-closed) so a dangerous act is never confirmed by user inaction. The channel owns this
         * timeout — the pure :automation core never times out itself.
         */
        const val CONFIRM_TIMEOUT_MS = 30_000L
    }
}
