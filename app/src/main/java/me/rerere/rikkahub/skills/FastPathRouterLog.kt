package me.rerere.rikkahub.skills

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 16 — in-memory ring buffer of recent fast-path matches. The Settings
 * "Fast-path router" page subscribes to this flow and shows the last [CAPACITY] matches
 * so the user can audit what the router caught vs. fell through.
 *
 * Not persisted — survives the process only, intentionally. The audit ledger is for "did
 * the router behave like I expected this session", not long-term forensic state.
 */
object FastPathRouterLog {

    const val CAPACITY = 50

    data class Entry(
        val whenMs: Long,
        val intent: String,
        val toolName: String,
        val userText: String,           // truncated to 120 chars
        val resultPreview: String,      // truncated to 200 chars
        val skippedLlm: Boolean,        // true on success, false on tool-failure (we then fall back to LLM)
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(entry: Entry) {
        val cur = _entries.value
        val next = (cur + entry).takeLast(CAPACITY)
        _entries.value = next
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
