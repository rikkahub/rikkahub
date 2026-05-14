package me.rerere.rikkahub.ui.pages.setting.browser

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.io.File

class SettingBrowserViewModel(
    private val prefs: BrowserPreferences,
) : ViewModel() {

    /** Per-tool enabled map, keyed by [me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS]. */
    val toolStates: StateFlow<Map<String, Boolean>> = prefs.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    /** Per-tool timeout, in milliseconds. Always clamped into the supported range. */
    val perToolTimeoutMs: StateFlow<Long> = prefs.perToolTimeoutFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS,
    )

    /** Single-task timeout, in milliseconds. Always clamped into the supported range. */
    val singleTaskTimeoutMs: StateFlow<Long> = prefs.singleTaskTimeoutFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS,
    )

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setToolEnabled(toolName, enabled) }
    }

    /** Persist a per-tool timeout given in seconds. Clamping happens in [BrowserPreferences]. */
    fun setPerToolTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setPerToolTimeoutMs(seconds * 1_000L) }
    }

    /** Persist a single-task timeout given in minutes. Clamping happens in [BrowserPreferences]. */
    fun setSingleTaskTimeoutMinutes(minutes: Long) {
        viewModelScope.launch { prefs.setSingleTaskTimeoutMs(minutes * 60_000L) }
    }

    /**
     * Wipes the WebView profile dir + cookies. Tool-toggle state is intentionally NOT
     * cleared — those are user config, not browsing data.
     *
     * Done on Dispatchers.IO; the cookie API on the main thread is safe but the dir
     * recursion isn't. [onDone] fires on the main thread once both have completed.
     */
    fun clearBrowsingData(context: Context, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val profileDir = File(context.filesDir, "browser-profile")
                    if (profileDir.exists()) {
                        profileDir.deleteRecursively()
                    }
                    profileDir.mkdirs()
                }
            }
            // CookieManager removeAllCookies dispatches its callback on the main thread; we
            // don't need the callback's value, just need to issue the call.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            onDone()
        }
    }
}
