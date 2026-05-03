package me.rerere.rikkahub.data.notifications

/**
 * Snapshot of a Notification at the moment it was posted. The listener service stores
 * these in a 100-entry ring buffer so the LLM can query history even after the source
 * notification was dismissed by its owning app.
 */
data class NotificationEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val title: String,
    val text: String,
    val subText: String,
    val postTimeMs: Long,
    val actionTitles: List<String>,
)
