package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * Side-effect surface for interactive tools to stream a post-action screenshot back
 * to the originating remote chat (Telegram bot / cron / sub-agent). Implementation
 * registered in Koin; default [NoOp] when no streaming surface is wired.
 *
 * Contract:
 *  - Best-effort only: implementations MUST swallow all exceptions with logging.
 *  - Only fires when [invocationContext] is non-null, headless=true, and a Telegram chat
 *    mapping exists for [invocationContext.callerConversationId].
 *  - The tool's own return value is unchanged — this is a pure side-effect.
 */
interface InteractiveToolStreamer {

    /**
     * Capture the current screen (via AccessibilityService) and send it to the originating
     * Telegram chat. No-ops silently when the context isn't headless or no chat mapping
     * exists.
     *
     * @param invocationContext  Who triggered the tool; null is treated as non-headless.
     * @param actionLabel        Human-readable caption for the screenshot (e.g. "Tap (540, 960)").
     */
    suspend fun streamIfHeadless(
        invocationContext: ToolInvocationContext?,
        actionLabel: String,
    )

    /**
     * No-op implementation used by default and in JVM tests. Safe to call from any context.
     */
    object NoOp : InteractiveToolStreamer {
        override suspend fun streamIfHeadless(
            invocationContext: ToolInvocationContext?,
            actionLabel: String,
        ) = Unit
    }
}
