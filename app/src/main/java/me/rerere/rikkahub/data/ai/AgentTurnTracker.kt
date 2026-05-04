package me.rerere.rikkahub.data.ai

/**
 * Per-turn record of "did the agent take the user out of RikkaHub?" — used by the
 * post-turn auto-return logic to decide whether to bring RikkaHub back to the
 * foreground after the agent finishes. Reset at the start of every generation turn.
 *
 * Only navigation actions performed by the agent itself populate this. If the user
 * physically switched apps mid-turn, the post-turn handler compares the current
 * foreground against [lastDestination] and skips the auto-return for safety.
 */
object AgentTurnTracker {
    @Volatile private var navigatedAway: Boolean = false
    @Volatile private var destination: String? = null
    @Volatile private var didAutomate: Boolean = false

    fun reset() {
        navigatedAway = false
        destination = null
        didAutomate = false
    }

    fun recordNavigatedAway(packageName: String?) {
        navigatedAway = true
        if (!packageName.isNullOrBlank()) destination = packageName
    }

    /**
     * Called by automation tools (tap, click_node, set_text, swipe, scroll, global_action)
     * when they perform an action against another app. Distinguishes "the agent opened X
     * and used it" (auto-return makes sense) from "the agent opened X for the user to use"
     * (auto-return would yank the user out of where they want to be).
     */
    fun recordAutomationAction() {
        didAutomate = true
    }

    fun didNavigateAway(): Boolean = navigatedAway
    fun didAutomate(): Boolean = didAutomate
    fun lastDestination(): String? = destination
}
