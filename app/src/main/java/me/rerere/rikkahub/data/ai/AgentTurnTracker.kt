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

    fun reset() {
        navigatedAway = false
        destination = null
    }

    fun recordNavigatedAway(packageName: String?) {
        navigatedAway = true
        if (!packageName.isNullOrBlank()) destination = packageName
    }

    fun didNavigateAway(): Boolean = navigatedAway
    fun lastDestination(): String? = destination
}
