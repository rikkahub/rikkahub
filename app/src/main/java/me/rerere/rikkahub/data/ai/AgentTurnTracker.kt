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

    /**
     * Per-package timestamp recording the last time the agent itself interacted with a
     * package (launch_app, open_url, termux_run_command). The notification listener checks
     * this before forwarding to Telegram so a user driving the agent does not get spammed
     * with status pings from the very work the agent just kicked off (Termux's "1 session"
     * counter is the canonical offender).
     */
    private val touchedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun reset() {
        navigatedAway = false
        destination = null
        didAutomate = false
        // Don't clear touchedPackages — the suppression window outlives the turn so the
        // last few flaps after the turn ends also get filtered.
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

    /** Marks [packageName] (and any pkg in [extras]) as freshly touched by the agent. */
    fun touchPackage(packageName: String, vararg extras: String) {
        val now = System.currentTimeMillis()
        touchedPackages[packageName] = now
        extras.forEach { touchedPackages[it] = now }
    }

    /**
     * True if the agent itself touched [packageName] within [withinMs]. Used by the
     * notification listener to drop forward-to-Telegram for packages the user did not
     * interact with — the agent did, and the resulting status notifications are not new
     * information for the user.
     */
    fun isFreshlyTouched(packageName: String, withinMs: Long = 60_000L): Boolean {
        val ts = touchedPackages[packageName] ?: return false
        return System.currentTimeMillis() - ts < withinMs
    }
}
