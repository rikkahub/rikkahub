package me.rerere.rikkahub.browser

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * [BrowserControllerHandle.withController] dispatch helper. Pass 2 adds the launch-await
 * (`awaitBind`), 5-min single-task window, in-flight task cancellation hook, and a
 * main-thread bridge (`WithControllerScope`) so tool factories can call into evaluateJavascript
 * cleanly. Pass 3 adds headless-mode logic on top.
 *
 * The reference is always [WeakReference] — taking a hard ref would pin the Activity past
 * its onDestroy and starve the GC. Tools that need the WebView must call [withController]
 * which fails fast (returns null / browser_not_open envelope) if the ref is stale or empty.
 */
object BrowserController {

    private const val MAX_RECENT_ACTIONS = 20
    /** Pass 2: 5-minute hard cap on a single AI-driven task to bound runaway loops. */
    private const val SINGLE_TASK_WINDOW_MS = 5L * 60L * 1000L

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    /**
     * Pass 2 publishes a fresh deferred each time the binding is cleared, so a tool that
     * fires `browser_open` can `awaitBind` after starting the Activity. The Volatile lets
     * the awaiting coroutine see the new instance the moment unbind() swaps it in.
     */
    @Volatile
    private var bindDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    /** Set on the first browser_open of a task. null = no task in flight. */
    @Volatile
    var currentTaskStartedAt: Long? = null

    /**
     * Pass 2: the in-flight tool dispatch coroutine, stored so the user-facing "Stop AI"
     * UI button can cancel a run mid-action (the visible Activity calls [stopCurrentTask]
     * which cancels this Job). Tool factories register their dispatch into here on entry
     * and clear on completion.
     */
    @Volatile
    var pendingTaskJob: Job? = null

    private val _recentActions = MutableStateFlow<List<String>>(emptyList())

    /** Compose-friendly observable of the last [MAX_RECENT_ACTIONS] AI actions, newest first. */
    fun recentActionsFlow(): StateFlow<List<String>> = _recentActions.asStateFlow()

    /** Activity calls this in onCreate. Replaces any prior binding (only one BrowserActivity at a time). */
    fun bind(webView: WebView) {
        webViewRef = WeakReference(webView)
        // Wake any awaitBind() callers that fired browser_open and are waiting for the
        // Activity to publish its WebView. complete() is idempotent on an already-completed
        // deferred — no race with a second browser_open before unbind.
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
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
            // Swap in a fresh deferred so the NEXT browser_open's awaitBind blocks correctly
            // until the next bind() — without this, a stale "completed" deferred from the
            // prior session would let awaitBind return immediately on a dead WebView.
            bindDeferred = CompletableDeferred()
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
     * The BrowserAiStripe observes the resulting flow and renders the trail.
     */
    fun appendAction(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val current = _recentActions.value
        val next = (listOf(trimmed) + current).take(MAX_RECENT_ACTIONS)
        _recentActions.value = next
    }

    /**
     * Cancel the in-flight tool dispatch (if any) and clear the single-task timer. Wired
     * to the Activity's "Stop AI" kebab item; the cancelled coroutine surfaces as a normal
     * CancellationException inside the tool's withTimeoutOrNull and the LLM gets a clean
     * envelope instead of a stack trace.
     */
    fun stopCurrentTask() {
        pendingTaskJob?.cancel()
        pendingTaskJob = null
        currentTaskStartedAt = null
        appendAction("AI task stopped by user")
    }

    /**
     * Start (or refresh) the 5-min single-task window. browser_open calls this on every
     * successful navigation; once a task starts, every browser_* call after the window
     * expires gets [taskTimeoutEnvelope] until browser_done fires (which clears the timer).
     */
    fun startTaskWindow() {
        currentTaskStartedAt = System.currentTimeMillis()
    }

    /** browser_done clears the task window (and stops the in-flight job log). */
    fun clearTaskWindow() {
        currentTaskStartedAt = null
    }

    /**
     * Returns true if no task is in flight OR the in-flight task hasn't yet exhausted its
     * 5-minute budget. Tools call this BEFORE doing any work so a runaway loop costs at
     * most one envelope per call after the cap.
     */
    fun isWithinTaskWindow(): Boolean {
        val started = currentTaskStartedAt ?: return true
        return System.currentTimeMillis() - started < SINGLE_TASK_WINDOW_MS
    }

    /**
     * Suspend until [bind] is called or [timeoutMs] elapses. browser_open uses this after
     * firing the BrowserActivity launch intent — the Activity's onCreate publishes its
     * WebView, the deferred completes, and the tool can then call loadUrl. 5 s is the
     * spec-mandated cap; on a slow device the user's click on Settings → Open Browser
     * also takes about that long.
     */
    suspend fun awaitBind(timeoutMs: Long = 5_000L): Boolean {
        if (isBound()) return true
        return withTimeoutOrNull(timeoutMs) { bindDeferred.await(); true } ?: false
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

    /** Returned when the 5-min single-task window has elapsed without a browser_done call. */
    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "Call browser_done with a summary; the per-task 5-minute cap has been reached.")
    }
}

/**
 * Handle / dispatch helper for the browser tools. Mirrors
 * [me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle.withService] in
 * shape: tools wrap their entire execute body in [withController], get the WebView if
 * one is bound, and uniformly fall back to the [BrowserController.notOpenEnvelope] error
 * shape if not.
 *
 * Pass 2 also exposes [WithControllerScope] so the per-tool helpers in BrowserTools can
 * round-trip JS via `webView.evaluateJavascript` on the main thread without each tool
 * re-implementing the bridge.
 */
object BrowserControllerHandle {

    /**
     * Scope passed into [withController]'s block. Carries the controller (for
     * appendAction / startTaskWindow) and the live WebView. Helpers that need the main
     * thread should use [me.rerere.rikkahub.browser.evaluateJavascriptAsync] which posts
     * onto the WebView's looper directly.
     */
    data class WithControllerScope(
        val controller: BrowserController,
        val webView: WebView,
    )

    /**
     * Runs [block] with a [WithControllerScope] if a WebView is bound; otherwise returns
     * the standard browser_not_open envelope. The 5-minute single-task cap is enforced up
     * front — browser_open re-arms it via [BrowserController.startTaskWindow] and
     * browser_done clears it via [BrowserController.clearTaskWindow] (both routed through
     * tool factories, so they remain reachable inside the cap).
     */
    suspend fun withController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return WithControllerScope(BrowserController, wv).block()
    }
}

/**
 * Run [code] in the WebView's main looper and return the JSON-encoded result string the
 * page produced (or "null" on any error / timeout). All WebView APIs must run on the
 * main thread — this helper hides the post() / CompletableDeferred plumbing every tool
 * would otherwise re-implement.
 *
 * The result is the raw string evaluateJavascript returns: a valid JSON value (number,
 * "string", true, null, [...], {...}). Callers parse it themselves, since JSON shape
 * varies per tool.
 */
suspend fun WebView.evaluateJavascriptAsync(code: String, timeoutMs: Long = 8_000L): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        post {
            try {
                evaluateJavascript(code) { result -> deferred.complete(result) }
            } catch (t: Throwable) {
                deferred.complete(null)
            }
        }
    }
    return withTimeoutOrNull(timeoutMs) { deferred.await() }
}

/**
 * Wait for `document.readyState === "complete"` for up to [timeoutMs] ms. Used after
 * state-changing tools (click, type, submit) so the next read tool sees the post-action
 * page rather than a half-rendered intermediate state. Polls every 200 ms, exits early
 * on first complete reading.
 */
suspend fun WebView.awaitReadyState(timeoutMs: Long = 8_000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val raw = evaluateJavascriptAsync("(function(){return document.readyState;})()", 1_500L)
        // evaluateJavascript wraps strings in JSON quotes — "complete" comes back as the
        // 10-char "\"complete\"". Compare on the unquoted contains to avoid string-quote
        // games across WebView versions.
        if (raw != null && raw.contains("complete")) return true
        kotlinx.coroutines.delay(200)
    }
    return false
}
