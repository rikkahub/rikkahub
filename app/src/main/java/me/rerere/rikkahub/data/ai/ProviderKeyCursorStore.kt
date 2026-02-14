package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.util.KeyCursorStore

class ProviderKeyCursorStore(context: Context) : KeyCursorStore {
    private val preferences = context.getSharedPreferences("provider_key_cursor", Context.MODE_PRIVATE)

    override fun get(scopeKey: String): Int {
        return preferences.getInt(scopeKey, 0)
    }

    override fun put(scopeKey: String, nextIndex: Int) {
        preferences.edit().putInt(scopeKey, nextIndex).commit()
    }
}
