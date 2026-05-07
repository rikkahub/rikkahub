package me.rerere.rikkahub.browser

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.browserDataStore by preferencesDataStore(name = "browser_prefs")

/**
 * DataStore-backed per-tool toggle store for the in-app browser. Mirrors
 * [me.rerere.rikkahub.data.telegram.TelegramBotPreferences] in shape: a single store,
 * suspend reads/writes, and an observable Flow.
 *
 * Pass 1 lays the wiring. Pass 2's tool factories will call [isToolEnabled] before
 * registering each tool — that's how the spec's "master toggle ON + per-tool toggle ON"
 * gate is enforced. Pass 1 doesn't touch tool registration.
 *
 * Defaults come from [BrowserToolDefaults.DEFAULT_ENABLED] so users get a sensible
 * read-only browser on first install: navigate, screenshot, read text — but no clicks
 * or JS until they explicitly opt in.
 */
class BrowserPreferences(private val context: Context) {

    private val store = context.browserDataStore

    private fun keyFor(toolName: String) = booleanPreferencesKey("tool_$toolName")

    /**
     * Reads the current toggle state for [toolName], falling back to
     * [BrowserToolDefaults.DEFAULT_ENABLED] when no value has been written yet.
     */
    suspend fun isToolEnabled(toolName: String): Boolean {
        val prefs = store.data.first()
        return prefs[keyFor(toolName)] ?: BrowserToolDefaults.DEFAULT_ENABLED[toolName] ?: false
    }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        store.edit { it[keyFor(toolName)] = enabled }
    }

    /**
     * Observe the full enabled-map. Emits one entry per tool in [BrowserToolDefaults.ALL_TOOLS],
     * pre-merged with defaults so callers don't have to do the same fallback dance.
     */
    fun observeAll(): Flow<Map<String, Boolean>> = store.data.map { prefs ->
        BrowserToolDefaults.ALL_TOOLS.associateWith { tool ->
            prefs[keyFor(tool)] ?: (BrowserToolDefaults.DEFAULT_ENABLED[tool] ?: false)
        }
    }

    /**
     * One-shot read of the full enabled-map. Useful for Pass 2's tool registration block
     * which runs synchronously during LocalTools.getTools() construction.
     */
    suspend fun snapshot(): Map<String, Boolean> {
        val prefs: Preferences = store.data.first()
        return BrowserToolDefaults.ALL_TOOLS.associateWith { tool ->
            prefs[keyFor(tool)] ?: (BrowserToolDefaults.DEFAULT_ENABLED[tool] ?: false)
        }
    }
}
