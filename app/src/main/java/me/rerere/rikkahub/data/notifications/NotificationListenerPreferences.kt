package me.rerere.rikkahub.data.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationListenerDataStore by preferencesDataStore(name = "notification_listener")

/**
 * Mirrors TelegramBotPreferences. Single string key under the hood, stores the whitelist as
 * a comma-separated list of package names. Keep the storage shape simple - the only consumer
 * is the listener service plus the settings page.
 */
class NotificationListenerPreferences(private val context: Context) {
    private val store = context.notificationListenerDataStore

    private val K_WHITELIST = stringPreferencesKey("whitelist")

    val flow = store.data.map { p ->
        NotificationListenerConfig(
            whitelist = parseWhitelist(p[K_WHITELIST].orEmpty()),
        )
    }

    suspend fun current(): NotificationListenerConfig = flow.first()

    suspend fun update(fn: (NotificationListenerConfig) -> NotificationListenerConfig) {
        store.edit { p ->
            val cur = NotificationListenerConfig(
                whitelist = parseWhitelist(p[K_WHITELIST].orEmpty()),
            )
            val next = fn(cur)
            p[K_WHITELIST] = next.whitelist.sorted().joinToString(",")
        }
    }

    private fun parseWhitelist(s: String): Set<String> =
        s.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "com.android.systemui" && it != context.packageName }
            .toSet()
}
