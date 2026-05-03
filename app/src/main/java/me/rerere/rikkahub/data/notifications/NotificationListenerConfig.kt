package me.rerere.rikkahub.data.notifications

/**
 * Persisted preferences for the notification listener subsystem.
 *
 * @property whitelist package names whose notifications should be auto-forwarded to the
 *   user's default Telegram chat. Empty by default - nothing is forwarded.
 */
data class NotificationListenerConfig(
    val whitelist: Set<String> = emptySet(),
)
