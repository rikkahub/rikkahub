package me.rerere.rikkahub.browser

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Singleton bridge between the LLM browser tools and the live WebView.
 * Mirrors the [me.rerere.rikkahub.service.RikkaAccessibilityService.instance] pattern: the
 * Activity (or headless session host) publishes itself in on bind and clears on unbind.
 *
 * Pass 1 laid the foundation — the WeakReference, the recent-actions log, and the
 * [BrowserControllerHandle.withController] dispatch helper. Pass 2 added launch-await
 * (`awaitBind`), the 5-min single-task window, and a main-thread bridge.
 *
 * **Pass 3 introduces a [Mode] sealed class** so the controller can serve two parallel
 * use cases without forking the tool dispatcher:
 *  - [Mode.Foreground]: the on-screen [BrowserActivity] hosts the WebView. The user
 *    watches the AI navigate.
 *  - [Mode.Headless]: a [HeadlessBrowserSession] hosts the WebView in the application
 *    process, parented to an unattached layout. Used when the calling conversation is a
 *    Telegram bot / cron / sub-agent — anything `HeadlessConversations.isHeadless(convId)`
 *    returns true for. After every state-changing tool, the controller streams a screenshot
 *    + URL into the calling chat via [BrowserScreenshotStreamer].
 *
 * The legacy [bind]/[unbind] entry points still work — they delegate to the new
 * foreground bind so existing call sites in [BrowserActivity] compile unchanged. The
 * [WeakReference] reaches into [Mode.Foreground.activityRef] now; [Mode.Headless] holds a
 * hard reference (the headless session is the WebView's only owner — letting it GC mid-task
 * would lose the session).
 */
object BrowserController {

    private const val MAX_RECENT_ACTIONS = 20
    /** Pass 2: 5-minute hard cap on a single AI-driven task to bound runaway loops. */
    private const val SINGLE_TASK_WINDOW_MS = 5L * 60L * 1000L
    /** Cache subdir for streamed (headless) screenshots — separate from the `browser-shots`
     *  subdir the explicit browser_screenshot tool writes into so the streamer pipe can be
     *  swept independently if it ever grows unbounded. */
    private const val STREAM_CACHE_SUBDIR = "browser-stream"
    private const val TAG = "BrowserController"

    /**
     * Execution mode for the controller. Exactly one is active at a time; the [Mode.Idle]
     * case lets `isBound()` return false uniformly without a null check.
     */
    sealed class Mode {
        data object Idle : Mode()

        /** A visible [BrowserActivity] hosts the WebView. */
        data class Foreground(val activityRef: WeakReference<WebView>) : Mode()

        /**
         * A headless WebView lives in the application process, parented to an unattached
         * layout owned by [HeadlessBrowserSession]. After every state-changing tool, the
         * controller posts a screenshot + URL caption into the conversation identified by
         * [callerConvId] via [BrowserScreenshotStreamer].
         */
        data class Headless(val callerConvId: String, val webView: WebView) : Mode()
    }

    @Volatile
    private var mode: Mode = Mode.Idle

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

    /** Returns the current execution mode. */
    fun currentMode(): Mode = mode

    // --- Foreground bindings ----------------------------------------------------------

    /**
     * Activity calls this in onCreate. Replaces any prior binding (only one BrowserActivity
     * at a time). Pass 3 also routes around an existing [Mode.Headless] — installing a
     * foreground binding while a headless session is live is undefined; the headless session
     * MUST `unbindHeadless` before the foreground Activity binds.
     */
    fun bindForeground(webView: WebView) {
        mode = Mode.Foreground(WeakReference(webView))
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
    }

    /** Activity calls this in onDestroy. Only clears if the live ref still points at the same WebView. */
    fun unbindForeground(webView: WebView) {
        val current = (mode as? Mode.Foreground)?.activityRef?.get()
        if (current === webView || current == null) {
            mode = Mode.Idle
            // Reset task timer + action log when the visible Activity is torn down. Headless
            // mode has its own teardown via [unbindHeadless]; this branch is foreground-only.
            currentTaskStartedAt = null
            _recentActions.value = emptyList()
            // Swap in a fresh deferred so the NEXT browser_open's awaitBind blocks correctly
            // until the next bind() — without this, a stale "completed" deferred from the
            // prior session would let awaitBind return immediately on a dead WebView.
            bindDeferred = CompletableDeferred()
        }
    }

    /** Pass 1/2 API surface — kept as a thin wrapper over [bindForeground] so call sites compile. */
    fun bind(webView: WebView) = bindForeground(webView)

    /** Pass 1/2 API surface — kept as a thin wrapper over [unbindForeground]. */
    fun unbind(webView: WebView) = unbindForeground(webView)

    // --- Headless bindings ------------------------------------------------------------

    /**
     * Pass 3: bind a headless WebView for the conversation identified by [callerConvId].
     * The WebView is held by hard reference because the [HeadlessBrowserSession] is its
     * only owner — losing it to GC mid-task would silently lose the session.
     *
     * Sets the Mode to [Mode.Headless] and completes the bind deferred, mirroring the
     * foreground path so [awaitBind] can be reused if needed.
     */
    fun bindHeadless(callerConvId: String, webView: WebView) {
        mode = Mode.Headless(callerConvId, webView)
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
    }

    /**
     * Tear down the headless binding for [callerConvId]. Idempotent: if the current mode
     * isn't headless or doesn't match the conv id, this is a no-op (someone else already
     * tore it down or we're racing a foreground bind).
     */
    fun unbindHeadless(callerConvId: String) {
        val m = mode
        if (m is Mode.Headless && m.callerConvId == callerConvId) {
            mode = Mode.Idle
            currentTaskStartedAt = null
            _recentActions.value = emptyList()
            bindDeferred = CompletableDeferred()
        }
    }

    // --- Status reads -----------------------------------------------------------------

    /** True iff a WebView is currently bound (foreground or headless) and not GC'd. */
    fun isBound(): Boolean = activeWebView() != null

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentUrl(): String? = activeWebView()?.url

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentTitle(): String? = activeWebView()?.title

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
     * Suspend until a bind happens or [timeoutMs] elapses. browser_open uses this after
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
     * (the WeakReference has been GC'd, no Activity, or no headless session).
     */
    internal fun activeWebView(): WebView? = when (val m = mode) {
        is Mode.Foreground -> m.activityRef.get()
        is Mode.Headless -> m.webView
        Mode.Idle -> null
    }

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }

    /** Returned when the 5-min single-task window has elapsed without a browser_done call. */
    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "Call browser_done with a summary; the per-task 5-minute cap has been reached.")
    }

    /**
     * Returned when a headless session was torn down mid-task (the calling FGS died) and a
     * subsequent tool call lands on an Idle controller. Distinct from `browser_not_open`
     * so the LLM can tell the user "your remote session ended" rather than retry forever.
     */
    fun sessionLostEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_session_lost")
        put("recovery", "The headless browser session ended (the calling foreground service was killed). Ask the user to retry.")
    }

    /**
     * Pass 3 auto-stream hook: every state-changing tool calls this AFTER its action
     * completes (and after [awaitReadyState]) so the remote user gets a screenshot.
     * No-op when the controller isn't in [Mode.Headless] — foreground users watch the
     * Activity directly and don't need a streamed copy.
     *
     * Failures are swallowed at the streamer level (a missing chat mapping, a Telegram
     * outage, etc.) so a screenshot send error never bubbles up to fail the tool itself.
     * The LLM has already produced its envelope by the time we get here.
     *
     * Wiring: the streamer is resolved lazily through Koin so the controller doesn't take
     * a constructor dep on it (would create a cycle through TelegramBotService → Koin →
     * LocalTools → BrowserController). [BrowserScreenshotStreamer.NoOp] is the safe
     * fallback if no implementation is registered (e.g. from a JVM unit test).
     */
    suspend fun streamScreenshotIfHeadless(actionLabel: String) {
        val m = mode
        if (m !is Mode.Headless) return
        val webView = m.webView
        val context = webView.context.applicationContext ?: return
        // Capture on the main thread (WebView APIs all require it). Failures here mean the
        // session is mid-teardown — log a single line and bail rather than re-trying.
        val capture = runCatching {
            withContext(Dispatchers.Main) {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                val cacheDir = File(context.cacheDir, STREAM_CACHE_SUBDIR).apply { mkdirs() }
                val out = File(cacheDir, "stream-${System.currentTimeMillis()}.png")
                FileOutputStream(out).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                bitmap.recycle()
                out.absolutePath
            }
        }.onFailure { android.util.Log.w(TAG, "streamScreenshotIfHeadless: capture failed", it) }
            .getOrNull() ?: return

        val streamer: BrowserScreenshotStreamer? = runCatching {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<BrowserScreenshotStreamer>()
        }.getOrNull()
        runCatching {
            (streamer ?: BrowserScreenshotStreamer.NoOp)
                .send(m.callerConvId, capture, actionLabel, webView.url)
        }.onFailure { android.util.Log.w(TAG, "streamScreenshotIfHeadless: streamer.send failed", it) }
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
