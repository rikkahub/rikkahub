package me.rerere.rikkahub.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.File

/**
 * Pass 3: hosts a WebView offscreen, in the application process, for headless AI-driven
 * browsing (Telegram bot / cron / sub-agent flows). The session is owned by a
 * [HeadlessBrowserSessionPool] keyed on the calling conversation id.
 *
 * **Why no system Display.** The spec considered `DisplayManager.createVirtualDisplay`
 * but that path requires a `MediaProjection` token from the user — we don't have one and
 * we don't want one. `WindowManager.addView` requires `SYSTEM_ALERT_WINDOW`, which we
 * also don't have. The chosen approach: parent the WebView to an unattached
 * [LinearLayout], drive `measure()` + `layout()` manually with a 1080x1920 size hint,
 * and let `evaluateJavascript` + `WebView.draw(canvas)` do their work without ever
 * being on screen. AndroidX WebView is happy as long as it has a valid context and a
 * laid-out parent — it doesn't check window-visibility before running JS or rendering
 * to a Canvas.
 *
 * **Visibility shim.** Some sites (notably YouTube, some maps, autoplay video) gate
 * behaviour on the Page Visibility API and refuse to run when `document.hidden === true`.
 * We override `document.visibilityState` and `document.hidden` to "visible" / `false` on
 * every `onPageStarted` so headless sessions don't fall off a cliff for the long tail of
 * sites that do this check. This is a documented spec behaviour (§Headless rendering
 * caveats) and is the load-bearing reason the headless mode is viable for v1.
 *
 * **Profile dir.** Same `${filesDir}/browser-profile/` cookies + localStorage as the
 * foreground Activity. WebView's storage paths are process-singletons, so a headless
 * session shares logged-in cookies with what the user manually browsed earlier.
 *
 * **Session lifetime.** The owner ([HeadlessBrowserSessionPool]) keeps one session per
 * conv id alive across multiple tool calls. The session is torn down when:
 *  - `browser_done` fires (clears the task window; the pool sees no further activity).
 *  - The calling FGS dies (process kill: the whole pool dies with it; on next launch
 *    the AI sees `browser_session_lost` because no Mode.Headless is bound).
 *  - The pool's idle sweep evicts it (no tool call for longer than the per-task budget,
 *    `BrowserController.singleTaskTimeoutMs`, default 5 min) on the next getOrCreate — the
 *    caller hasn't run a tool in a while, the LLM has likely moved on. This is the backstop
 *    for a caller FGS that died before browser_done/release fired.
 */
class HeadlessBrowserSession(private val context: Context) {

    private var webView: WebView? = null
    private var host: LinearLayout? = null

    /**
     * Lazily create the WebView on first call; subsequent calls return the same instance
     * so a multi-tool task reuses cookies + history. The width/height are spec-mandated
     * (1080x1920 — phone-portrait) so screenshots feed the LLM at a reasonable size and
     * sites that use `window.innerWidth` / media-queries don't fall back to mobile-mini.
     *
     * `@Synchronized` is defense in depth: callers in production go through
     * [HeadlessBrowserSessionPool.getOrCreate] which already serialises access on a
     * per-pool lock. The annotation guards the direct-call path (a future refactor or a
     * unit test) so two threads can never both pass the null-check and leak a WebView.
     */
    @Synchronized
    fun start(callerConvId: String): WebView {
        val existing = webView
        if (existing != null) return existing

        // Best-effort profile dir creation. WebView falls back to its default location if
        // creation fails — we never want to crash the headless host on first call.
        val profileDir = File(context.filesDir, "browser-profile")
        if (!profileDir.exists()) profileDir.mkdirs()

        // CookieManager is process-global; setAcceptCookie is idempotent — calling here
        // covers the case where the headless session is the FIRST WebView the process has
        // ever created (foreground BrowserActivity hasn't run, so it hasn't done this yet).
        CookieManager.getInstance().setAcceptCookie(true)

        val parent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Manual measure + layout: WebView only lays itself out when its ViewParent
            // does, and an unattached LinearLayout never gets a layout pass from the OS.
            // Drive it ourselves with a fixed size so WebView.draw(canvas) can render.
            layoutParams = LinearLayout.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        }

        val wv = WebView(context).apply {
            // Shared with foreground — every render-related setting (mixedContentMode,
            // hardware layer, autoplay, UA strip, file:// access) lives in
            // configureWebViewForRikka. Before this helper existed, headless mode lacked
            // the white-page render fixes from `1ac54c4b` / `3ac3b4b4` / `a1db859c` and
            // silently streamed all-white PNGs to the user's Telegram chat on the long
            // tail of mainstream sites. See BrowserWebViewConfig.kt for the history.
            configureWebViewForRikka(this)
            // Headless mode renders via WebView.draw(canvas), which CANNOT capture
            // hardware-accelerated layers — those go straight from the WebView's HW layer to
            // the GPU compositor without ever touching a software canvas. configureWebViewForRikka
            // sets LAYER_TYPE_HARDWARE for the foreground Compose-AndroidView interop case.
            // Override here: the headless WebView is offscreen, has no Compose host, and must
            // paint into a software bitmap. Without this override the streamed PNG is all
            // white. Foreground mode keeps HARDWARE because it still renders to a real screen.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            // Visibility shim — re-injected on every page start. WebViewClient.onPageStarted
            // fires before page JS runs, which is the only window where overriding the
            // descriptor changes future reads.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Same file:// navigation gate as the foreground BrowserView: headless
                    // sessions are model-driven (Telegram/cron), so a page- or JS-initiated
                    // hop into file:// would expose app-private files to browser_get_text.
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    return toFile && view?.url?.startsWith("file:", ignoreCase = true) != true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(VISIBILITY_SHIM_JS, null)
                }
            }
            // Per-WebView third-party cookie enable — must be called after the WebView
            // exists. The CookieManager singleton above only governs first-party.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        parent.addView(
            wv,
            LinearLayout.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        )
        // Drive measure + layout manually since the parent will never be attached to a
        // window. Without this, WebView.width / WebView.height stay 0 and any draw(canvas)
        // call produces a 1x1 transparent bitmap.
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

        host = parent
        webView = wv
        return wv
    }

    /**
     * Tear down the WebView and detach from its host. Idempotent. Called by the pool
     * eviction timer or on `browser_done`. Releases the JS engine and ~30 MB of resident
     * memory; not optional.
     */
    fun stop() {
        val wv = webView
        val h = host
        // Null the fields synchronously so the pool sees the session as stopped immediately,
        // even though the actual WebView teardown is marshalled to the main thread below.
        webView = null
        host = null
        if (wv == null) return
        val teardown = Runnable {
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                h?.removeView(wv)
                wv.destroy()
            }.onFailure {
                // Teardown is best-effort — a throw here (e.g. WebView already destroyed on a
                // racing path) must not corrupt the pool's bookkeeping. Log so a genuine leak
                // is visible rather than silently swallowed.
                android.util.Log.w("HeadlessBrowserSession", "stop: WebView teardown threw", it)
            }
        }
        // WebView methods must run on the thread that created the view. start() builds the
        // WebView under Dispatchers.Main, but the pool's idle sweep calls stop() from
        // getOrCreate, which runs OFF the main thread — calling destroy() there throws the
        // "all WebView methods must be called on the same thread" violation (swallowed by the
        // runCatching), leaving the ~30 MB WebView un-destroyed and defeating the eviction.
        // Marshal the teardown onto the main looper so the memory is actually released.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardown.run()
        } else {
            Handler(Looper.getMainLooper()).post(teardown)
        }
    }

    /** Cheap accessor for the live WebView, or null if [stop] has run. */
    fun activeWebView(): WebView? = webView

    companion object {
        // 1080x1920 is the canonical phone-portrait viewport per the spec. Most modern
        // pages adapt to this size and the screenshots stream at a reasonable resolution.
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920

        /**
         * The visibility shim. Reads as `document.visibilityState === "visible"` no matter
         * what — the WebView is offscreen but the page can't tell. The defineProperty
         * configurable:true leaves room for future site code to re-override (which is
         * fine — the goal is the initial paint behaviour, not adversarial enforcement).
         */
        private const val VISIBILITY_SHIM_JS = """
            (function(){
                try {
                    Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true});
                    Object.defineProperty(document, 'hidden', {value: false, configurable: true});
                } catch (e) { /* page may have already locked these — best-effort */ }
            })();
        """
    }
}

/**
 * Process-singleton pool keyed on the calling conversation id. One session per conv so a
 * multi-tool task reuses the same WebView (and its cookies) for the whole task; different
 * conversations get separate WebViews so their state can't leak across.
 *
 * Eviction: callers are expected to call [release] on `browser_done`. As a defence against
 * forgotten teardowns, [release] is idempotent and the pool size is bounded by how many
 * concurrent headless conversations the FGS host actually keeps running — Telegram bot
 * has at most one (the polling loop is single-threaded), cron jobs run sequentially in
 * their worker, and sub-agents are also serialised. So in practice the pool holds 0–1
 * sessions at a time.
 *
 * Backstop: a caller whose FGS is killed never runs [release], so its session would leak for
 * the process lifetime. Every [getOrCreate] therefore sweeps and closes sessions idle longer
 * than [idleTtlMs] first, so an abandoned WebView can't outlive one task window.
 */
object HeadlessBrowserSessionPool {

    /**
     * A pooled session plus the wall-clock millis it was last handed out by [getOrCreate].
     * The timestamp drives idle eviction: a conversation whose FGS died (so [release] never
     * fired) leaves an orphaned ~30 MB WebView behind, and without a sweep those accumulate
     * for the process lifetime. See [idleTtlMs].
     */
    private class Entry(val session: HeadlessBrowserSession, var lastUsedAtMs: Long)

    private val sessions = mutableMapOf<String, Entry>()
    private val lock = Any()

    /**
     * Idle TTL: a session untouched for longer than this is swept on the next [getOrCreate].
     * Tracks [BrowserController.singleTaskTimeoutMs] — the per-task budget the user already
     * configures in Settings → Browser. A session that hasn't run a tool for at least a full
     * task window is one the LLM has almost certainly moved on from; reusing the same value
     * keeps one knob instead of inventing a second idle constant. Read each sweep so a live
     * settings edit takes effect without restarting the pool.
     */
    private val idleTtlMs: Long get() = BrowserController.singleTaskTimeoutMs

    /**
     * Look up an existing session for [callerConvId] or construct a new one. Reusing on
     * lookup gives us cookie persistence within a multi-tool task without a separate
     * "warmup" call.
     *
     * Every call first sweeps sessions idle longer than [idleTtlMs] (excluding the one being
     * requested, whose timestamp is refreshed). This bounds the pool against the leak where a
     * caller's FGS dies before `browser_done`/[release] runs, orphaning its WebView forever.
     */
    fun getOrCreate(context: Context, callerConvId: String): HeadlessBrowserSession {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            sweepIdleLocked(now, keep = callerConvId)
            sessions[callerConvId]?.let { it.lastUsedAtMs = now; return it.session }
            val s = HeadlessBrowserSession(context.applicationContext ?: context)
            sessions[callerConvId] = Entry(s, now)
            return s
        }
    }

    /**
     * Close + remove every session whose last use is older than [idleTtlMs], except [keep]
     * (the conv currently being served — it's about to be refreshed). Caller holds [lock].
     */
    private fun sweepIdleLocked(now: Long, keep: String?) {
        val ttl = idleTtlMs
        val stale = sessions.entries.filter { (id, entry) ->
            id != keep && (now - entry.lastUsedAtMs) > ttl
        }
        for ((id, entry) in stale) {
            // stop() is best-effort/idempotent; remove the mapping regardless so a throwing
            // teardown can't pin a dead entry in the pool forever.
            runCatching { entry.session.stop() }
            sessions.remove(id)
            // The controller's single mode slot may still be Mode.Headless for this conv,
            // pointing at the WebView stop() just destroyed. Reset it to Idle so the next tool
            // call returns browser_session_lost instead of dispatching onto a dead view.
            // clearModeIfHeadless only acts when this conv still owns the slot and never calls
            // back into the pool, so holding `lock` here is safe (no lock-order inversion).
            runCatching { BrowserController.clearModeIfHeadless(id) }
        }
    }

    /**
     * Release the session for [callerConvId]. Tears down the WebView and removes the
     * mapping; subsequent [getOrCreate] returns a fresh session. Idempotent.
     */
    fun release(callerConvId: String) {
        val e = synchronized(lock) { sessions.remove(callerConvId) } ?: return
        e.session.stop()
    }

    /** Test seam: clear all sessions. Not used in production. */
    internal fun clearAll() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.session.stop() } }
            sessions.clear()
        }
    }

    /** Test seam: count of live sessions. Not used in production. */
    internal fun activeCount(): Int = synchronized(lock) { sessions.size }
}
