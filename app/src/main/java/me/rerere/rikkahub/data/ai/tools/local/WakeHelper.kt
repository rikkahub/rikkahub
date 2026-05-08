package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log

private const val TAG = "WakeHelper"

/**
 * Shared entry point for the "wake the screen before doing something the user can see"
 * pattern. Every interactive tool calls this before its body so headless runs (Telegram
 * bot, cron, sub-agent) don't launch activities or fire gestures against a dark screen.
 *
 * Delegates to [ScreenWaker.wakeIfOff], which already lives in WakeScreenTool.kt and is
 * idempotent (no-op when the screen is already on). Safe to call from any coroutine
 * context; never throws.
 */
fun wakeScreenIfNeeded(context: Context) {
    try {
        val wasOff = !ScreenWaker.isInteractive(context)
        if (wasOff) {
            val woke = ScreenWaker.wakeIfOff(context)
            Log.d(TAG, "wakeScreenIfNeeded: screen was off, woke=$woke")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "wakeScreenIfNeeded: failed silently", t)
    }
}
