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

    /**
     * Hard cap on a single AI-driven task to bound runaway loops. User-configurable via
     * Settings → Browser (GitHub issue #4) — [BrowserPreferences] writes the persisted value
     * here at app start and on every edit. Defaults to 5 min until the first read settles.
     * Always holds a value clamped into [BrowserToolDefaults]'s supported range.
     */
    @Volatile
    var singleTaskTimeoutMs: Long = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS

    /**
     * Per-tool timeout — the `withTimeoutOrNull` budget every browser tool wraps its dispatch
     * in. User-configurable via Settings → Browser (GitHub issue #4); kept in sync by
     * [BrowserPreferences]. Defaults to 30 s until the first read settles. Always clamped.
     */
    @Volatile
    var perToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS
    /** Cache subdir for streamed (headless) screenshots — separate from the `browser-shots`
     *  subdir the explicit browser_screenshot tool writes into so the streamer pipe can be
     *  swept independently if it ever grows unbounded. */
    private const val STREAM_CACHE_SUBDIR = "browser-stream"

    /**
     * Skip a stream send if URL matches the previous send within this window. 8 s was the
     * first cut but live testing with slow models (minimax-m2.7 at 0.7 tok/s) showed a
     * single LLM turn easily spans 30+ s while bouncing back to the same URL multiple
     * times. 30 s catches all the practical "model retrying the same page" cases without
     * suppressing legitimate revisits later in the conversation (different turn = mostly
     * a new URL anyway).
     */
    private const val STREAM_DEDUPE_WINDOW_MS = 30_000L

    /**
     * After `awaitReadyState` returns (`document.readyState === "complete"`), the WebView
     * has parsed HTML and finished resource loads but still hasn't painted its first frame.
     * `webView.draw(canvas)` at that exact moment captures the empty/white initial backing
     * — the bug the user reported on cold `browser_open`. 250 ms wasn't enough on slower
     * pages (kali.org/docs/ rendered the first 2 streams white). 600 ms handles the long
     * tail; further actions on the same page hit the de-dupe window so this delay only
     * fires once per real navigation.
     */
    private const val PAINT_SETTLE_MS = 600L
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
     * Serialises every read-modify-write of [mode] and [streamDedupe]. The bind/unbind
     * entry points and the per-conv de-dupe map mutate shared state from multiple coroutines
     * (Telegram polling loop, cron worker, sub-agent), so a plain `@Volatile` on [mode] is
     * not enough to make "check the current binding, then replace it" atomic. Without it, two
     * concurrent headless conversations can both pass [bindHeadless]'s clobber check and the
     * second silently overwrites the first — a later screenshot then routes to the wrong chat.
     */
    private val bindLock = Any()

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

    /**
     * De-dupe state for [streamScreenshotIfHeadless], keyed by [Mode.Headless.callerConvId].
     * When the LLM bounces between the same/very-similar URL (e.g. minimax-m2.7 occasionally
     * calls browser_open 5x in a row trying to find a page) every state-changing tool fires
     * the streamer, flooding the user's Telegram chat with near-identical PNGs. Skip the send
     * when the URL is the same as that conversation's last stream AND the last stream was
     * within [STREAM_DEDUPE_WINDOW_MS]. A click that didn't change the URL is also caught by
     * this rule (URL stays equal).
     *
     * **Why keyed per conv.** A single global last-URL/last-time pair let two concurrent
     * headless conversations clobber each other's de-dupe memory: conv A streams page X, conv
     * B then streams the same X within the window and gets wrongly suppressed (or vice-versa,
     * A's stale mark suppresses B's legitimate first frame). Keying on the caller conv id
     * isolates the windows. Entries are dropped on [unbindHeadless] so a finished conversation
     * doesn't retain memory. Guarded by [bindLock] for the same reason [mode] is.
     */
    private data class StreamMark(val url: String?, val atMs: Long)

    private val streamDedupe = mutableMapOf<String, StreamMark>()

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
        // Trim stale screenshots from any prior session (including ones killed by
        // process-stop). Doing this on bind catches both the clean-end and crash-end
        // cases — by the time the new session writes its first capture, there are at
        // most `keepLast` files in each cache subdir.
        runCatching { BrowserCacheSweeper.sweep(webView.context.applicationContext) }
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
     *
     * Returns false WITHOUT mutating state if a DIFFERENT conversation already holds a live
     * headless (or foreground) binding — the controller's [mode] is a single global slot, so
     * letting a second concurrent conversation overwrite it would route the first's streamed
     * screenshots into the wrong chat. The caller (browser_open) surfaces a clean
     * [bindBusyEnvelope] in that case. Re-binding the SAME conv id is always allowed (the
     * normal per-task reuse where browser_open fires again on a session that's already bound).
     */
    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        synchronized(bindLock) {
            when (val current = mode) {
                is Mode.Headless ->
                    // A DIFFERENT conversation may take over the single controller slot only
                    // while the current owner's task is genuinely in flight — i.e. browser_open
                    // armed the task window and browser_done hasn't cleared it (and it hasn't
                    // expired). During that window a second conversation would clobber the
                    // owner's screenshot routing, so reject it (bindBusyEnvelope). Once the owner
                    // finishes (window cleared), its window expires (forgetful model), or its
                    // idle session is swept, the slot is free to hand off — without this the
                    // binding would pin to the finished conversation until its /new and block
                    // every other conversation forever. Same-conv re-bind always refreshes the ref.
                    if (current.callerConvId != callerConvId &&
                        currentTaskStartedAt != null && isWithinTaskWindow()
                    ) return false
                is Mode.Foreground ->
                    // The visible Activity is using the controller; don't steal it from under
                    // the user. (bindForeground itself routes around an existing headless bind
                    // per its own contract.) Reject only if the foreground WebView is still live.
                    if (current.activityRef.get() != null) return false
                Mode.Idle -> Unit
            }
            mode = Mode.Headless(callerConvId, webView)
            // Fresh session for this conv — drop its de-dupe memory so the first stream of a
            // new task isn't suppressed by a URL match against a previous task on the same id.
            streamDedupe.remove(callerConvId)
        }
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
        // Trim stale screenshots — same reasoning as bindForeground. Headless sessions
        // produce streamer PNGs in `browser-stream/` after every state-changing tool, so
        // a long bot conversation can put real pressure on cacheDir without this.
        runCatching { BrowserCacheSweeper.sweep(webView.context.applicationContext) }
        return true
    }

    /**
     * Non-mutating peek: would [bindHeadless] for [callerConvId] currently succeed?
     * Mirrors [bindHeadless]'s reject rule EXACTLY (a different live headless owner whose
     * task is genuinely in flight, or a live foreground binding) so browser_open can avoid
     * allocating a ~30 MB WebView session it would only have to discard on rejection.
     *
     * This is advisory: [bindHeadless] stays authoritative and re-checks under [bindLock],
     * so a race between the peek and the bind can only cost the (now closed) allocation, not
     * a wrong binding. Reads [mode] / [currentTaskStartedAt] under the lock for a coherent
     * snapshot, matching how the real bind decides.
     */
    fun canBindHeadless(callerConvId: String): Boolean {
        synchronized(bindLock) {
            return when (val current = mode) {
                is Mode.Headless ->
                    !(current.callerConvId != callerConvId &&
                        currentTaskStartedAt != null && isWithinTaskWindow())
                is Mode.Foreground -> current.activityRef.get() == null
                Mode.Idle -> true
            }
        }
    }

    /**
     * Tear down the headless binding for [callerConvId]. Idempotent: if the current mode
     * isn't headless or doesn't match the conv id, this is a no-op (someone else already
     * tore it down or we're racing a foreground bind).
     */
    fun unbindHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
            // Drop the conversation's de-dupe memory regardless of which mode is live, so a
            // finished conv can't leave a stale URL mark that suppresses a future reuse.
            streamDedupe.remove(callerConvId)
        }
    }

    /**
     * Reset [mode] to [Mode.Idle] iff it is currently [Mode.Headless] for [callerConvId].
     * Called by [HeadlessBrowserSessionPool]'s idle sweep when it evicts (and destroys) a
     * session: without this the controller's [mode] keeps pointing at the now-destroyed
     * WebView, so the next tool call would dispatch onto a dead view (evaluateJavascript
     * throws, screenshots stream white) instead of cleanly returning `browser_session_lost`.
     *
     * Mirrors [unbindHeadless]'s teardown (task timer, action log, fresh bind deferred, de-dupe
     * memory) but ONLY when this conv still owns the slot — a different live owner or a
     * foreground binding is left untouched. Guarded by [bindLock] so it composes safely with
     * concurrent bind/unbind; the pool calls it while holding its OWN (separate) pool lock, and
     * this method never reaches back into the pool, so the two locks never nest in conflicting
     * order.
     */
    fun clearModeIfHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
            streamDedupe.remove(callerConvId)
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
     * Start (or refresh) the single-task window. browser_open calls this on every successful
     * navigation; once a task starts, every browser_* call after the window expires gets
     * [taskTimeoutEnvelope] until browser_done fires (which clears the timer). The window
     * length is [singleTaskTimeoutMs] — user-configurable in Settings → Browser.
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
     * configured single-task budget ([singleTaskTimeoutMs]). Tools call this BEFORE doing any
     * work so a runaway loop costs at most one envelope per call after the cap.
     */
    fun isWithinTaskWindow(): Boolean {
        val started = currentTaskStartedAt ?: return true
        return System.currentTimeMillis() - started < singleTaskTimeoutMs
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
     * Returned when a headless browser_open lands while a DIFFERENT conversation already
     * holds the (single, global) controller binding. The controller can drive one WebView at
     * a time; binding a second concurrently would route the first conversation's streamed
     * screenshots into the wrong chat, so the second is rejected here instead.
     */
    fun bindBusyEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_busy")
        put("recovery", "Another conversation is currently driving the browser. Wait for it to finish (it calls browser_done), then retry browser_open.")
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
        // Capture on the main thread (WebView APIs all require it). Read webView.url here
        // too — accessing it off the main thread tripped StrictMode and threw, which the
        // outer runCatching silently swallowed; the user saw "no screenshot in chat" with
        // no obvious error.
        data class Capture(val path: String, val url: String?)
        // Read the current URL on the main thread first. If it matches the last streamed
        // URL within STREAM_DEDUPE_WINDOW_MS, skip everything — bitmap allocation, file
        // write, and Telegram upload. Catches three real-world cases that flood the user's
        // chat with redundant PNGs:
        //   1. Click that didn't navigate (URL unchanged → diff helper marks it unchanged
        //      but the streamer still fires for the action label).
        //   2. browser_open + immediately some other write tool on the same page.
        //   3. Confused model that calls browser_open 5x in a row trying to find a page.
        val currentUrl = runCatching {
            withContext(Dispatchers.Main) { webView.url }
        }.onFailure {
            // A throw reading webView.url (StrictMode off-main-thread, destroyed WebView)
            // would otherwise vanish — the de-dupe check then treats the URL as null and
            // streams anyway. Log so the cause is recoverable from logcat.
            android.util.Log.w(TAG, "streamScreenshotIfHeadless: reading webView.url failed", it)
        }.getOrNull()
        val now = System.currentTimeMillis()
        // Per-conv de-dupe: only this conversation's own prior stream can suppress this one,
        // so a concurrent conversation streaming the same URL can't wrongly gate it.
        val lastMark = synchronized(bindLock) { streamDedupe[m.callerConvId] }
        if (
            currentUrl != null &&
            currentUrl == lastMark?.url &&
            (now - lastMark.atMs) < STREAM_DEDUPE_WINDOW_MS
        ) {
            android.util.Log.d(TAG, "streamScreenshotIfHeadless: skipping duplicate URL $currentUrl within ${STREAM_DEDUPE_WINDOW_MS}ms")
            return
        }
        // Paint settle: awaitReadyState ensures HTML+resources, NOT first paint. Without
        // this delay, the very first browser_open screenshot streams an empty white frame
        // because draw(canvas) ran before the renderer flushed.
        kotlinx.coroutines.delay(PAINT_SETTLE_MS)
        val capture = runCatching {
            withContext(Dispatchers.Main) {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                val cacheDir = File(context.cacheDir, STREAM_CACHE_SUBDIR).apply { mkdirs() }
                val out = File(cacheDir, "stream-${System.currentTimeMillis()}.png")
                // Recycle in a finally block so a FileOutputStream failure doesn't leak
                // the ~8 MB native backing. Without this, any IO error mid-capture leaves
                // the bitmap alive until the next GC (the outer runCatching swallows
                // the exception before the bitmap variable goes out of scope).
                try {
                    FileOutputStream(out).use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                } finally {
                    bitmap.recycle()
                }
                Capture(out.absolutePath, currentUrl)
            }
        }.onFailure { android.util.Log.w(TAG, "streamScreenshotIfHeadless: capture failed", it) }
            .getOrNull() ?: return
        // Record what we just streamed AFTER the bitmap path succeeds so a transient
        // capture failure doesn't lock out a subsequent attempt for the dedupe window.
        synchronized(bindLock) { streamDedupe[m.callerConvId] = StreamMark(capture.url, now) }

        val streamer: BrowserScreenshotStreamer? = runCatching {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<BrowserScreenshotStreamer>()
        }.getOrNull()
        runCatching {
            (streamer ?: BrowserScreenshotStreamer.NoOp)
                .send(m.callerConvId, capture.path, actionLabel, capture.url)
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
     *
     * The block runs on [Dispatchers.Main]. WebView APIs are main-thread-only and will
     * throw `WebViewMethodCalledOnWrongThreadViolation` from any other dispatcher, so
     * baking the bridge in here means every tool author gets safe direct access to
     * `webView.url`, `webView.title`, `webView.canGoBack()`, etc. without re-wrapping.
     * For network or heavy CPU work that must run off-main, suspend out of [block] via
     * `withContext(Dispatchers.IO)` explicitly. The async JS helpers
     * ([evaluateJavascriptAsync], [awaitReadyState]) post via the WebView's looper and
     * suspend on a `CompletableDeferred`, so they stay non-blocking even from main.
     */
    suspend fun withController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return withContext(Dispatchers.Main) {
            WithControllerScope(BrowserController, wv).block()
        }
    }
}

/**
 * Run [code] on the WebView's required main thread and return the JSON-encoded result
 * string the page produced (or "null" on any error / timeout). `evaluateJavascript`
 * itself is documented as main-thread only and routes its result callback onto the UI
 * thread; the [withContext] gets us there and the [CompletableDeferred] bridges the
 * callback back into a coroutine.
 *
 * **Why no `webView.post { ... }` wrapper.** The earlier version posted into the
 * WebView's run-queue. For an unattached WebView (the headless `HeadlessBrowserSession`
 * parent LinearLayout never reaches a Window), `View.post` queues the runnable until
 * attach — which never happens, so `evaluateJavascript` was never called and the
 * deferred timed out at 8 s on every call. Calling `evaluateJavascript` directly from
 * the main-thread context fixes both attached and unattached cases.
 *
 * The result is the raw string evaluateJavascript returns: a valid JSON value (number,
 * "string", true, null, [...], {...}). Callers parse it themselves, since JSON shape
 * varies per tool.
 */
suspend fun WebView.evaluateJavascriptAsync(code: String, timeoutMs: Long = 8_000L): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        try {
            evaluateJavascript(code) { result -> deferred.complete(result) }
        } catch (e: Exception) {
            // evaluateJavascript can throw if the WebView has been destroyed underneath
            // us (Activity finished, headless session stopped). Log so the cause is
            // visible — the caller still gets a clean null and falls back. Narrowed from
            // Throwable so JVM Errors (OOM etc.) still propagate.
            android.util.Log.w("BrowserController", "evaluateJavascriptAsync: evaluateJavascript threw", e)
            deferred.complete(null)
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
        // evaluateJavascript wraps string returns in JSON quotes — `"complete"` comes
        // back as the 10-char literal `"\"complete\""`. Match the exact form so a page
        // that overrides document.readyState to a string merely containing "complete"
        // (e.g. "incomplete", or some adversarial value) doesn't trip the early-exit.
        if (raw != null && raw.trim() == "\"complete\"") return true
        kotlinx.coroutines.delay(200)
    }
    return false
}
