package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context

/**
 * Shared entry point for the "wake the screen before doing something the user can see"
 * pattern. Every interactive tool calls this before its body so headless runs (Telegram
 * bot, cron, sub-agent) don't launch activities or fire gestures against a dark screen.
 *
 * Delegates to [ScreenWaker.wakeIfOff], which already lives in WakeScreenTool.kt and is
 * idempotent (no-op when the screen is already on). Safe to call from any coroutine
 * context; never throws — the catch swallows silently. Intentionally NO `android.util.Log`
 * call here: JVM unit tests with NULL_CONTEXT reach this helper through validation paths
 * and Log.w would throw "method not mocked", masking the assertion the test is actually
 * checking. Logging during failure here would be noise anyway.
 */
fun wakeScreenIfNeeded(context: Context) {
    try {
        if (!ScreenWaker.isInteractive(context)) {
            ScreenWaker.wakeIfOff(context)
        }
    } catch (_: Throwable) {
        // Silent. The screen-wake is best-effort; any failure (no PowerManager, NPE on
        // unsafe-allocated test context, security exception) is recoverable — the
        // calling tool will still attempt its action against a dark screen, which is
        // strictly no worse than skipping the helper entirely.
    }
}
