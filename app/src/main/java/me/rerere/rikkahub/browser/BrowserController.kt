package me.rerere.rikkahub.browser

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference

/**
 * Singleton bridge between the LLM browser tools and the live BrowserActivity's WebView.
 * Mirrors the [me.rerere.rikkahub.service.RikkaAccessibilityService.instance] pattern: a
 * @Volatile reference the Activity publishes itself into on bind and clears on unbind.
 *
 * Pass 1 lays the foundation — the WeakReference, the recent-actions log, and the
 * [BrowserControllerHandle.withController] dispatch helper. Pass 2 will use these from
 * the 17 browser tool factories. Pass 3 will add headless-mode logic.
 *
 * The reference is always [WeakReference] — taking a hard ref would pin the Activity past
 * its onDestroy and starve the GC. Tools that need the WebView must call [withController]
 * which fails fast (returns null / browser_not_open envelope) if the ref is stale or empty.
 */
object BrowserController {

    private const val MAX_RECENT_ACTIONS = 20

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    /** Set on the first browser_open of a task (Pass 2 will populate). null = no task in flight. */
    @Volatile
    var currentTaskStartedAt: Long? = null

    private val _recentActions = MutableStateFlow<List<String>>(emptyList())

    /** Compose-friendly observable of the last [MAX_RECENT_ACTIONS] AI actions, newest first. */
    fun recentActionsFlow(): StateFlow<List<String>> = _recentActions.asStateFlow()

    /** Activity calls this in onCreate. Replaces any prior binding (only one BrowserActivity at a time). */
    fun bind(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    /** Activity calls this in onDestroy. Only clears if the live ref still points at the same WebView. */
    fun unbind(webView: WebView) {
        val current = webViewRef?.get()
        if (current === webView || current == null) {
            webViewRef = null
            // Reset task timer + action log when the visible Activity is torn down. Pass 3
            // will revisit this for headless mode where unbind doesn't mean "task ended".
            currentTaskStartedAt = null
            _recentActions.value = emptyList()
        }
    }

    /** True iff a WebView is currently bound and not garbage-collected. */
    fun isBound(): Boolean = webViewRef?.get() != null

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentUrl(): String? = webViewRef?.get()?.url

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentTitle(): String? = webViewRef?.get()?.title

    /**
     * Append a one-line description of an AI-driven action to the recent-actions log.
     * Pass 2 will call this from the tool factories (e.g. "Clicked Sign in", "Typed into
     * search box"); Pass 1 leaves it as a public API and the BrowserAiStripe observes it.
     */
    fun appendAction(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val current = _recentActions.value
        val next = (listOf(trimmed) + current).take(MAX_RECENT_ACTIONS)
        _recentActions.value = next
    }

    /**
     * Pass 2 will resolve this to the LLM-visible "stop the loop" tool. Pass 1 wires a
     * no-op stub so the BrowserAddressBar's kebab "Stop AI" item compiles + is harmless.
     */
    fun stopCurrentTask() {
        currentTaskStartedAt = null
        appendAction("AI task stopped by user")
    }

    /**
     * Internal accessor used by [BrowserControllerHandle]. Returns the live WebView or null
     * (the WeakReference has been GC'd, or the Activity hasn't bound yet).
     */
    internal fun activeWebView(): WebView? = webViewRef?.get()

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }
}

/**
 * Handle / dispatch helper for the browser tools (Pass 2 caller). Mirrors
 * [me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle.withService] in
 * shape: tools wrap their entire execute body in [withController], get the WebView if
 * one is bound, and uniformly fall back to the [BrowserController.notOpenEnvelope] error
 * shape if not.
 */
object BrowserControllerHandle {
    /**
     * Runs [block] with the live WebView if the Activity is bound; otherwise returns the
     * standard browser_not_open envelope. Pass 1 lays the helper, Pass 2 will use it from
     * the 17 tool factories.
     */
    suspend fun withController(
        block: suspend (WebView) -> JsonObject
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        return block(wv)
    }
}
