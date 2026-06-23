package me.rerere.rikkahub.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.service.automation.AutomationKillSwitch

/**
 * Binds ChatService's two process-global, platform-coupled hooks (#360 P1a):
 *  - the app foreground/background state (driven by [ProcessLifecycleOwner]); and
 *  - the automation kill-switch handler.
 *
 * Extracted as a port so ChatService no longer references [ProcessLifecycleOwner] directly in its
 * `init`/`cleanup` (an Android-only API). [bind] takes plain CALLBACKS and returns a handle to undo the
 * registration; crucially it does NOT inspect ChatService's session map — the kill-switch callback
 * (which traverses sessions) stays in ChatService — so this seam is independent of the (later) session
 * registry extraction. A test supplies an in-memory fake binding; production binds the Android one.
 */
interface ChatServiceProcessBinding {
    /**
     * Register [onForegroundChanged] (true on app foreground, false on background) and [onKillSwitchTrip]
     * (the floating STOP overlay was tapped). Returns an [AutoCloseable] that unregisters both; calling
     * [AutoCloseable.close] more than once is a no-op.
     */
    fun bind(onForegroundChanged: (Boolean) -> Unit, onKillSwitchTrip: () -> Unit): AutoCloseable
}

/**
 * Production [ChatServiceProcessBinding]: wires [ProcessLifecycleOwner] for foreground state and
 * [AutomationKillSwitch.register] for the kill-switch. The lifecycle observer is added/removed on the
 * caller's thread (ChatService is constructed on the main thread by Koin, matching the pre-#360 inline
 * registration).
 */
class AndroidChatServiceProcessBinding(
    private val killSwitch: AutomationKillSwitch,
) : ChatServiceProcessBinding {
    override fun bind(
        onForegroundChanged: (Boolean) -> Unit,
        onKillSwitchTrip: () -> Unit,
    ): AutoCloseable {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> onForegroundChanged(false)
                else -> {}
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        val killSwitchHandle = killSwitch.register(onKillSwitchTrip)

        var closed = false
        return AutoCloseable {
            if (closed) return@AutoCloseable
            closed = true
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            killSwitch.unregister(killSwitchHandle)
        }
    }
}
