package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pass 2 of the in-app Browser feature: 17 LLM-callable tool factories that drive the
 * BrowserActivity's WebView through [BrowserControllerHandle.withController].
 *
 * Every tool wraps its dispatch in [withTimeoutOrNull] (30 s per the spec's "every tool
 * MUST have a hard timeout" rule); every state-changing write tool also calls
 * [awaitReadyState] post-action so the next read tool sees the new page.
 *
 * Tool registration is gated by [me.rerere.rikkahub.browser.BrowserPreferences] in
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] — toggling a tool off in
 * Settings → Browser unregisters it entirely, so YOLO can't accidentally run a tool the
 * user has explicitly disabled.
 */

private const val MAX_SCREENSHOT_HEIGHT_PX = 8192
private const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

/**
 * Per-tool timeout budget every browser tool wraps its dispatch in. User-configurable via
 * Settings → Browser (GitHub issue #4) — resolved fresh on each tool call from
 * [BrowserController.perToolTimeoutMs], which [me.rerere.rikkahub.browser.BrowserPreferences]
 * keeps in sync with the persisted value.
 */
private val toolTimeoutMs: Long get() = BrowserController.perToolTimeoutMs

/**
 * Pass 3: appended to every browser tool's description so the LLM knows that in headless
 * (Telegram / cron / sub-agent) mode, the act of taking a state-changing action will
 * automatically push a screenshot+caption to the calling chat. Without this cue, the
 * model would otherwise call `browser_screenshot` after every action — doubling vision
 * tokens and round-trips for no gain.
 */
private const val TELEGRAM_HEADLESS_CUE =
    " In Telegram / headless mode, screenshots stream to the calling chat after each state-changing action — call browser_done when you're confident the task is complete."

/**
 * Decide whether the current invocation should run in headless mode. Resolution order:
 *  1. [ToolInvocationContext.callerConversationId] is the canonical source. Cron jobs,
 *     sub-agents, Telegram bot, external automation all set it.
 *  2. We try to parse it as a [Uuid]. The [HeadlessConversations] registry keys on Uuid
 *     (cron / sub-agent paths use the conversation's primary key).
 *  3. If parsing fails (some external automation paths use string ids), we still treat
 *     the [ToolInvocationContext.isHeadless] flag as authoritative — that flag is set by
 *     every flow that wants headless behaviour.
 *
 * Conservative default: foreground. The visible Activity is never wrong; the worst case
 * of a misclassification is the activity briefly appears on the user's screen.
 */
@OptIn(ExperimentalUuidApi::class)
private fun isHeadlessInvocation(ctx: ToolInvocationContext?): Boolean {
    if (ctx == null) return false
    if (ctx.isHeadless) return true
    val convId = ctx.callerConversationId ?: return false
    val asUuid = runCatching { Uuid.parse(convId) }.getOrNull() ?: return false
    return HeadlessConversations.isHeadless(asUuid)
}

// ---- Common envelope helpers --------------------------------------------------------------

private fun timeoutEnvelope(toolName: String): JsonObject = buildJsonObject {
    put("error", "tool_timeout")
    put("tool", toolName)
    put("recovery", "The browser tool exceeded its $toolTimeoutMs-ms budget. Retry, or simplify the selector.")
}

private fun missingArgEnvelope(name: String, detail: String): JsonObject = buildJsonObject {
    put("error", "missing_$name")
    put("detail", detail)
}

private fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(obj.toString()))

/**
 * JSON-encode a Kotlin string so it can be embedded inside an evaluateJavascript payload
 * as a JS string literal — handles backslashes, quotes, control characters, and Unicode
 * escapes uniformly. Doing this by hand is the canonical XSS-via-LLM-tool footgun; we
 * route through kotlinx.serialization's JsonPrimitive which formats per the JSON spec
 * (also valid JS string syntax).
 */
private fun jsString(s: String): String = JsonPrimitive(s).toString()

// ---- Read tools ---------------------------------------------------------------------------

fun browserOpenTool(context: Context, invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Navigate the in-app browser to a URL. Launches the browser if it isn't open. Returns {success, current_url, title}. Resets the per-task 5-minute timer.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The full URL to navigate to (https://...)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // Heuristic exfil-shape check: if the URL's QUERY (not path — CDN asset hashes
        // would false-positive) carries something that looks like an opaque blob, JWT,
        // API key, or credit-card-shaped digit run, attach a warning so the LLM treats
        // the destination with care. Best-effort, not a security boundary.
        val exfilHits = SensitiveContentDetector.scanUrlQuery(url)
        val rawOut = if (url == null) {
            missingArgEnvelope("url", "url is required and must be a non-empty string")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                // Pass 3 mode picker. If the caller is a Telegram / cron / sub-agent
                // conversation, run in headless mode — no on-screen Activity, screenshots
                // streamed to the calling chat after every state-changing tool. Otherwise
                // fall back to the foreground BrowserActivity (the only tool that can
                // launch it).
                val callerConvId = invocationContext?.callerConversationId
                val headless = isHeadlessInvocation(invocationContext)

                if (headless && callerConvId != null) {
                    // Headless path: get-or-create the per-conv WebView session. The
                    // lifecycle of the WebView piggybacks on the calling FGS — when the
                    // FGS dies, the whole pool dies with it. Subsequent tool calls will
                    // see Mode.Idle and return `browser_session_lost`.
                    val session = HeadlessBrowserSessionPool.getOrCreate(context, callerConvId)
                    val webView = withContext(Dispatchers.Main) { session.start(callerConvId) }
                    BrowserController.bindHeadless(callerConvId, webView)
                    BrowserController.startTaskWindow()
                    BrowserController.appendAction("Open: $url")
                    val result = BrowserControllerHandle.withController {
                        withContext(Dispatchers.Main) { webView.loadUrl(url) }
                        webView.awaitReadyState(8_000L)
                        buildJsonObject {
                            put("success", true)
                            put("current_url", webView.url ?: url)
                            put("title", webView.title.orEmpty())
                        }
                    }
                    // Stream the landing-page screenshot so the user sees we arrived.
                    BrowserController.streamScreenshotIfHeadless("Opened $url")
                    result
                } else {
                    // Foreground path (Pass 1+2 behaviour). browser_open is the ONLY tool
                    // allowed to launch the Activity. Any other tool returning
                    // browser_not_open is the LLM's signal to call this first.
                    val wasAlreadyBound = BrowserController.isBound()
                    if (!wasAlreadyBound) {
                        context.startActivity(
                            me.rerere.rikkahub.browser.BrowserActivity.intent(
                                context,
                                url,
                                conversationId = callerConvId,
                            )
                        )
                        if (!BrowserController.awaitBind(5_000L)) {
                            return@withTimeoutOrNull buildJsonObject {
                                put("error", "browser_launch_failed")
                                put("recovery", "Activity did not bind within 5s; retry browser_open or check that the app has overlay permission.")
                            }
                        }
                    }
                    // (Re)start the 5-minute task window on every browser_open.
                    BrowserController.startTaskWindow()
                    BrowserController.appendAction("Open: $url")
                    BrowserControllerHandle.withController {
                        // When the Activity was just freshly launched, the URL was already
                        // passed as EXTRA_INITIAL_URL and the WebView began loading it
                        // before bind() returned. Skip a redundant second loadUrl — it
                        // would abort the in-flight load and restart from the top.
                        if (wasAlreadyBound) {
                            withContext(Dispatchers.Main) { webView.loadUrl(url) }
                        }
                        webView.awaitReadyState(8_000L)
                        buildJsonObject {
                            put("success", true)
                            put("current_url", webView.url ?: url)
                            put("title", webView.title.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        }
        val out = if (exfilHits.isEmpty() ||
            rawOut["success"]?.jsonPrimitive?.booleanOrNull != true) rawOut
        else buildJsonObject {
            rawOut.forEach { (k, v) -> put(k, v) }
            put(
                "warning",
                "URL query string carries content shaped like sensitive data " +
                    "(${exfilHits.joinToString { it.name.lowercase() }}). " +
                    "Verify with the user that they intended to send this " +
                    "to ${rawOut["current_url"]?.jsonPrimitive?.contentOrNull ?: url} " +
                    "before relying on the result, and do NOT echo the value back."
            )
        }
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL and page title. {url, title}. browser_not_open if the browser isn't open.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                buildJsonObject {
                    put("url", webView.url.orEmpty())
                    put("title", webView.title.orEmpty())
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CURRENT_URL)
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture the visible viewport of the browser as a PNG vision attachment. Use browser_get_text first if you only need the page's text — screenshots cost vision tokens. full_page=true is best-effort and currently captures the viewport only (viewport_only:true in the response).$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("full_page", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, attempt to capture the entire scroll height (currently no-op; viewport-only)")
                })
            },
        )
    },
    execute = { input ->
        val fullPage = input.jsonObject["full_page"]?.jsonPrimitive?.booleanOrNull == true
        val parts = mutableListOf<UIMessagePart>()
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val (path, w, h) = withContext(Dispatchers.Main) {
                    val width = webView.width.coerceAtLeast(1)
                    val height = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val out = File(cacheDir, "screenshot-${System.currentTimeMillis()}.png")
                    // Recycle unconditionally in a finally block so the bitmap is freed
                    // exactly once regardless of whether compress() succeeds or throws.
                    // The prior pattern called recycle() in onFailure AND then again
                    // unconditionally — a double-recycle causes IllegalStateException.
                    try {
                        FileOutputStream(out).use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    Triple(out.absolutePath, width, height)
                }
                BrowserController.appendAction("Screenshot")
                buildJsonObject {
                    put("success", true)
                    put("file_path", path)
                    put("width", w)
                    put("height", h)
                    if (fullPage) put("viewport_only", true)
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        out.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(out.toString()))
        parts
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Returns the main article content via Readability.js by default, falling back to selector-based extraction if Readability fails. Pass extract_mode:'raw' for the unfiltered text. Pass selector (e.g. 'article', 'main', '.content') for explicit scoping — selectors override Readability. max_chars (default 8000) caps the result. Use this BEFORE screenshot if you only need text content. {text, truncated, extract_mode}.$TELEGRAM_HEADLESS_CUE",
    parameters = { getTextSchema(defaultMax = 8000) },
    execute = { input -> textPart(runGetText(input)) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract a simplified outerHTML of a CSS selector (default 'body'). Strips <script>/<style>. Truncates at max_chars (default 4000). Use scoped selectors like 'article' / 'main' rather than 'body' for relevance — body usually includes nav and footer chrome that costs tokens without value. {html, truncated}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorAndMaxCharsSchema(defaultMax = 4000, required = false) },
    execute = { input -> textPart(runReadHelper(input, BrowserToolDefaults.GET_DOM, defaultMax = 4000) { selector, maxChars ->
        """(function(){
            try {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                var clone = el.cloneNode(true);
                clone.querySelectorAll('script,style,noscript').forEach(function(n){n.remove();});
                var html = clone.outerHTML || '';
                var truncated = false;
                if (html.length > $maxChars) { html = html.substring(0, $maxChars); truncated = true; }
                return JSON.stringify({html:html, truncated:truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
        })()"""
    }) },
)

fun browserGetLinksTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_LINKS,
    description = "List up to 100 anchor links inside a CSS selector (default 'body') as {links:[{href, text}], count}. {error:'selector_not_found'} if the root selector doesn't match.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "Root selector to search inside (default 'body')")
            })
        })
    },
    execute = { input ->
        val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }) ?: "body"
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val js = """(function(){
                    try {
                        var root = document.querySelector(${jsString(selector)});
                        if (!root) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                        var anchors = root.querySelectorAll('a[href]');
                        var out = [];
                        for (var i=0; i<anchors.length && out.length<100; i++) {
                            var a = anchors[i];
                            var href = a.href || '';
                            var text = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if (text.length>200) text = text.substring(0,200);
                            out.push({href:href, text:text});
                        }
                        return JSON.stringify({links:out, count:out.length});
                    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                })()"""
                parseJsResult(webView.evaluateJavascriptAsync(js))
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_LINKS)
        textPart(out)
    },
)

fun browserBackTool(): Tool = Tool(
    name = BrowserToolDefaults.BACK,
    description = "Navigate the browser one step back in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate the browser one step forward in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true)) },
)

/** Valid values for browser_wait_for's `state` arg. `attached` preserves the original behavior. */
private val WAIT_FOR_STATES = setOf("attached", "detached", "visible", "hidden")

/**
 * Build the JS predicate browser_wait_for polls. Returns a self-invoking expression
 * that evaluates to the JS boolean `true` once the wait condition is satisfied.
 *
 *  - `state` decides what "satisfied" means for the element matching [selector]:
 *      attached  — at least one element matches (default; original behavior)
 *      detached  — no element matches
 *      visible   — a matching element is in the DOM AND rendered (offsetParent or a
 *                  non-zero client rect — covers position:fixed which has null offsetParent)
 *      hidden    — no matching element is visible (none in DOM, or all rendered hidden)
 *  - When [containsText] is non-null, the matched element ALSO has to contain that text
 *    (case-sensitive substring of innerText/textContent). For detached/hidden states the
 *    text constraint is ignored — "not there" can't also "contain text".
 *
 * Pure string builder so it stays unit-testable without a WebView.
 */
internal fun buildWaitForPredicate(selector: String, state: String, containsText: String?): String {
    val sel = jsString(selector)
    val txt = containsText?.let { jsString(it) }
    val textCheck = if (txt != null) {
        "function(el){var t=(el.innerText||el.textContent||'');return t.indexOf($txt)!==-1;}"
    } else {
        "function(){return true;}"
    }
    val visibleCheck = "function(el){" +
        "if(el.offsetParent!==null)return true;" +
        "var r=el.getClientRects();return r&&r.length>0;" +
        "}"
    return when (state) {
        "detached" -> "(function(){try{return document.querySelector($sel)===null;}catch(e){return false;}})()"
        "hidden" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i]))return false;}" +
            "return true;" +
            "}catch(e){return false;}})()"
        "visible" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i])&&hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
        else -> "(function(){try{" + // "attached" (default)
            "var els=document.querySelectorAll($sel);" +
            "var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
    }
}

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR,
    description = "Pause until a CSS selector reaches a target state. Polls every 200 ms up to timeout_ms (default 10_000). state is one of attached (default — element present in DOM), detached (element gone), visible (present AND rendered), hidden (none rendered). Optional contains_text waits until a matching element contains that text. {found, elapsed_ms}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to wait for")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Max wait in ms (default 10000, capped at the configured per-tool timeout)")
            })
            put("contains_text", buildJsonObject {
                put("type", "string")
                put("description", "Optional — wait until an element matching the selector contains this text (case-sensitive substring). Ignored for state=detached/hidden.")
            })
            put("state", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("attached"); add("detached"); add("visible"); add("hidden") })
                put("description", "Target state to wait for (default 'attached', the original presence-in-DOM behavior)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawState = input.jsonObject["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val out = when {
            selector == null ->
                missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
            rawState != null && rawState !in WAIT_FOR_STATES ->
                missingArgEnvelope("state", "state must be one of [attached, detached, visible, hidden]")
            else -> {
                val state = rawState ?: "attached"
                val containsText = input.jsonObject["contains_text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                // Cap the user-supplied timeout at our per-tool budget so the LLM can't ask for a
                // longer wait and starve every other tool call. The withTimeoutOrNull below would
                // catch this anyway, but capping here gives a clean envelope. The cap tracks the
                // user-configured per-tool timeout (Settings → Browser).
                val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .toLong()
                    .coerceIn(200L, toolTimeoutMs)
                withTimeoutOrNull(toolTimeoutMs) {
                    BrowserControllerHandle.withController {
                        val started = System.currentTimeMillis()
                        val deadline = started + timeoutMs
                        val js = buildWaitForPredicate(selector, state, containsText)
                        var found = false
                        while (System.currentTimeMillis() < deadline) {
                            val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                            if (raw == "true") { found = true; break }
                            delay(200)
                        }
                        buildJsonObject {
                            put("found", found)
                            put("elapsed_ms", System.currentTimeMillis() - started)
                            put("state", state)
                            if (containsText != null) put("contains_text", containsText)
                        }
                    }
                } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR)
            }
        }
        textPart(out)
    },
)

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. Returns the diff between the page before and after the action by default ({added, removed, added_chars, removed_chars, truncated} truncated to 4000 chars total). Pass full:true to skip the diff and get post_click_url only — use when the click navigates to an entirely new page. Waits up to 8 s for readyState=complete.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.scrollIntoView({block:'center', inline:'center'});
                                el.click();
                                return JSON.stringify({clicked:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val raw = webView.evaluateJavascriptAsync(js)
                        val res = parseJsResult(raw)
                        if (res.containsKey("error")) return@withDiff res
                        webView.awaitReadyState(8_000L)
                        BrowserController.appendAction("Click: $selector")
                        buildJsonObject {
                            put("success", true)
                            put("post_click_url", webView.url.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        }
        // Pass 3: post-click screenshot stream in headless mode. Skipped on error envelopes
        // so we don't push a stale screenshot when the click never landed.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector")
        }
        textPart(out)
    },
)

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable matching a CSS selector. Focuses, optionally clears, sets the value + dispatches an 'input' event so SPA frameworks observe the change. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the input") })
            put("text", buildJsonObject { put("type","string"); put("description","Text to type") })
            put("clear", buildJsonObject { put("type","boolean"); put("description","Clear the field first (default true)") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","text"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val clear = input.jsonObject["clear"]?.jsonPrimitive?.booleanOrNull ?: true
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        // Use both 'input' and 'change' events to satisfy frameworks that listen
                        // to either; React's synthetic event layer needs the native value setter
                        // path which we don't replicate here — covers ~90% of real inputs.
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.focus();
                                if (${if (clear) "true" else "false"}) {
                                    if ('value' in el) el.value = '';
                                    else if (el.isContentEditable) el.textContent = '';
                                }
                                if ('value' in el) el.value = (el.value || '') + ${jsString(text)};
                                else if (el.isContentEditable) el.textContent = (el.textContent || '') + ${jsString(text)};
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({typed:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Typed into $selector")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Typed into $selector")
        }
        textPart(out)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page in a direction (up/down/top/bottom). amount is in pixels (default 600, ignored for top/bottom). {success, scroll_y}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("direction", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("up"); add("down"); add("top"); add("bottom") })
            })
            put("amount", buildJsonObject { put("type","integer"); put("description","Scroll distance in px (default 600)") })
        }, required = listOf("direction"))
    },
    execute = { input ->
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        val amount = input.jsonObject["amount"]?.jsonPrimitive?.intOrNull ?: 600
        val out = if (direction == null || direction !in setOf("up", "down", "top", "bottom")) {
            missingArgEnvelope("direction", "direction must be one of [up, down, top, bottom]")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val js = """(function(){
                        try {
                            switch (${jsString(direction)}) {
                                case 'up': window.scrollBy(0, -$amount); break;
                                case 'down': window.scrollBy(0, $amount); break;
                                case 'top': window.scrollTo(0, 0); break;
                                case 'bottom': window.scrollTo(0, document.body.scrollHeight); break;
                            }
                            return JSON.stringify({scroll_y: Math.round(window.scrollY)});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Scroll $direction")
                    buildJsonObject {
                        put("success", true)
                        put("scroll_y", res["scroll_y"]?.jsonPrimitive?.intOrNull ?: 0)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL)
        }
        // Pass 3: browser_scroll is in WRITE_TOOLS and the TELEGRAM_HEADLESS_CUE describes
        // "screenshots stream after each state-changing action." Stream the post-scroll view so
        // the Telegram user can see the new viewport position (scroll is purely viewport movement
        // — the diff helper won't capture it since body.innerText doesn't change).
        if (out["success"]?.toString() == "true") {
            val scrollY = out["scroll_y"]?.jsonPrimitive?.intOrNull
            BrowserController.streamScreenshotIfHeadless(
                if (scrollY != null) "Scrolled to y=$scrollY" else "Scrolled"
            )
        }
        textPart(out)
    },
)

fun browserSubmitTool(): Tool = Tool(
    name = BrowserToolDefaults.SUBMIT,
    description = "Submit a form. If the selector is a <button type=submit> click it; otherwise locates the enclosing <form> and calls .submit(). Awaits the post-navigation readyState. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when submission navigates to a brand-new page). {success, post_submit_url, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector of a submit button or any element inside the target form") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                if (el.tagName === 'BUTTON' && (el.type === 'submit' || el.type === '')) {
                                    el.click();
                                    return JSON.stringify({submitted:true, via:'button_click'});
                                }
                                var form = el.closest('form');
                                if (!form) return JSON.stringify({error:'no_enclosing_form'});
                                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                                else form.submit();
                                return JSON.stringify({submitted:true, via:'form_submit'});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        webView.awaitReadyState(8_000L)
                        BrowserController.appendAction("Submit: $selector")
                        buildJsonObject {
                            put("success", true)
                            put("post_submit_url", webView.url.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Submitted $selector")
        }
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value. Dispatches 'change' so framework listeners fire. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the <select>") })
            put("value", buildJsonObject { put("type","string"); put("description","The option value to set") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","value"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            value == null -> missingArgEnvelope("value", "value is required")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                if (el.tagName !== 'SELECT') return JSON.stringify({error:'not_a_select'});
                                el.value = ${jsString(value)};
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({selected:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Select: $selector=$value")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SELECT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Selected $value in $selector")
        }
        textPart(out)
    },
)

fun browserPressKeyTool(): Tool = Tool(
    name = BrowserToolDefaults.PRESS_KEY,
    description = "Synthesize keydown + keyup events on the active element. Use KeyboardEvent.key values like 'Enter', 'Escape', 'ArrowDown', 'Tab'. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when Enter triggers a navigation). {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("key", buildJsonObject {
                put("type","string")
                put("description","KeyboardEvent.key value (e.g. 'Enter', 'Escape', 'ArrowDown')")
            })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("key"))
    },
    execute = { input ->
        // Cap at 32 chars — KeyboardEvent.key values like "ArrowDown" are short. A multi-KB
        // string here suggests model misuse; clamp before splicing into the JS payload.
        val key = input.jsonObject["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(32)
        val full = parseFullArg(input)
        val out = if (key == null) {
            missingArgEnvelope("key", "key is required (e.g. 'Enter', 'Escape')")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.activeElement || document.body;
                                var down = new KeyboardEvent('keydown', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                var up = new KeyboardEvent('keyup', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                el.dispatchEvent(down);
                                el.dispatchEvent(up);
                                return JSON.stringify({pressed:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Press key: $key")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.PRESS_KEY)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Pressed $key")
        }
        textPart(out)
    },
)

fun browserEvalJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EVAL_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. HARDLINE-checked: shell-shaped strings, document.cookie writes, eval/Function constructors, and string-form setTimeout are all blocked at the tool dispatcher BEFORE the JS executes. Always asks for approval; never eligible for 'Always Allow'.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("code", buildJsonObject {
                put("type","string")
                put("description","JavaScript to evaluate. The string returned by the WebView is the value of the last expression, JSON-encoded.")
            })
        }, required = listOf("code"))
    },
    execute = { input ->
        // HARDLINE has already run via GenerationHandler before we get here. The execute
        // body just dispatches the JS and forwards whatever the WebView returns. Any
        // pattern HARDLINE missed is a bug to fix in HardlineCommandGuard, not here.
        val code = input.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (code == null) {
            missingArgEnvelope("code", "code is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val raw = webView.evaluateJavascriptAsync(code, toolTimeoutMs - 1_000L)
                    BrowserController.appendAction("Run JS")
                    buildJsonObject { put("result", raw ?: "null") }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.EVAL_JS)
        }
        // Pass 3: browser_eval_js is in WRITE_TOOLS — it CAN mutate page state (e.g.
        // click via JS, modify the DOM, submit a form programmatically). Stream a screenshot
        // so the Telegram user sees the page after the script ran, consistent with every
        // other write tool. Only stream on success — error envelopes (timeout, missing_code,
        // browser_not_open) all carry an "error" key so we use that as the gate.
        if (!out.containsKey("error")) {
            BrowserController.streamScreenshotIfHeadless("Ran JS")
        }
        textPart(out)
    },
)

/**
 * Token-cost optimisation pass — composite click+read tool. Cuts the dominant
 * "click → wait → get_text" three-call sequence to one round trip. The trade is
 * a slightly heavier per-call envelope (URL + title + text/diff) which is
 * still cheaper than three separate calls' worth of model thinking-tokens.
 *
 * extract_mode: "diff" (default), "auto", "readability", "raw".
 *   - "diff" → snapshot text before/after, return {diff: ...}.
 *   - "auto"/"readability"/"raw" → snapshot URL+title, click, await readyState,
 *     extract text per get_text rules, return {text, page_title}.
 *
 * max_chars caps either the diff side OR the extracted text. The cap protects
 * against an LLM asking for a megabyte of text on a long-form page.
 */
fun browserClickAndReadTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK_AND_READ,
    description = "One-shot click + read in a single round trip. Click an element, await readyState, then return either the diff (default extract_mode:'diff') or the extracted text (auto/readability/raw — same semantics as browser_get_text). Use this instead of browser_click + browser_get_text when you want to minimise tokens. {success, post_click_url, page_title, [diff], [text]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type","string")
                put("description","CSS selector to click")
            })
            put("extract_mode", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("diff"); add("auto"); add("readability"); add("raw") })
                put("description","diff (default) returns a before/after diff; auto/readability/raw return the extracted page text")
            })
            put("max_chars", buildJsonObject {
                put("type","integer")
                put("description","Caps the returned text length (default 4000)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?.takeIf { it in setOf("diff", "auto", "readability", "raw") } ?: "diff"
        val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 4000)
            .coerceIn(100, 64 * 1024)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val before = if (mode == "diff") captureBodyText() else ""
                    val titleBefore = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                    val clickJs = """(function(){
                        try {
                            var el = document.querySelector(${jsString(selector)});
                            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                            el.scrollIntoView({block:'center', inline:'center'});
                            el.click();
                            return JSON.stringify({clicked:true});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val clickRes = parseJsResult(webView.evaluateJavascriptAsync(clickJs))
                    if (clickRes.containsKey("error")) return@withController clickRes
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Click+read: $selector")
                    val postUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
                    val postTitle = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                    @Suppress("UNUSED_VARIABLE")
                    val unused = titleBefore // documents that we considered showing diff of titles
                    when (mode) {
                        "diff" -> {
                            val after = captureBodyText()
                            buildJsonObject {
                                put("success", true)
                                put("post_click_url", postUrl)
                                put("page_title", postTitle)
                                put("diff", BrowserDiffHelper.computeDiff(before, after))
                            }
                        }
                        else -> {
                            val text = when (mode) {
                                "readability" -> webView.runReadability()
                                "auto" -> webView.runReadability()?.takeIf { it.length >= READABILITY_MIN_CHARS }
                                else -> null
                            }
                            val (resolved, extractMode) = if (!text.isNullOrEmpty()) {
                                text to "readability"
                            } else if (mode == "readability") {
                                // Forced mode + null result → surface the error envelope.
                                return@withController buildJsonObject {
                                    put("success", false)
                                    put("post_click_url", postUrl)
                                    put("page_title", postTitle)
                                    put("error", "readability_failed")
                                }
                            } else {
                                // Fall back to selector-based body innerText.
                                val rawJs = """(function(){
                                    try {
                                        var t = (document.body.innerText || document.body.textContent || '').replace(/\s+/g,' ').trim();
                                        return JSON.stringify({text:t});
                                    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                                })()"""
                                val rawRes = parseJsResult(webView.evaluateJavascriptAsync(rawJs))
                                val rawText = rawRes["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                rawText to (if (mode == "auto") "raw_fallback" else "raw")
                            }
                            val (clipped, truncated) = clipText(resolved, maxChars)
                            buildJsonObject {
                                put("success", true)
                                put("post_click_url", postUrl)
                                put("page_title", postTitle)
                                put("text", clipped)
                                put("truncated", truncated)
                                put("extract_mode", extractMode)
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK_AND_READ)
        }
        // Pass 3 parity: stream a screenshot in headless mode on success, same as
        // plain browser_click. The diff envelope still flows to the LLM via textPart.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector (and read)")
        }
        textPart(out)
    },
)

// ---- Loop control --------------------------------------------------------------------------

fun browserDoneTool(invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.DONE,
    description = "Signal that the AI has finished its current browser task. Clears the per-task 5-minute timer so the next browser_open starts fresh. The browser session ITSELF stays alive — subsequent turns can navigate, click, scroll without re-opening; only `/new` (Telegram) or the in-app reset closes it. result_url is optional; pass the page URL the user should look at if any. {success}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("summary", buildJsonObject {
                put("type","string")
                put("description","One-sentence summary of what was accomplished")
            })
            put("result_url", buildJsonObject {
                put("type","string")
                put("description","Optional URL the user should look at")
            })
        }, required = listOf("summary"))
    },
    execute = { input ->
        val summary = input.jsonObject["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (summary == null) {
            missingArgEnvelope("summary", "summary is required")
        } else {
            BrowserController.appendAction("Done: $summary")
            BrowserController.clearTaskWindow()
            // Pass 3 design originally released the headless WebView here. Live-test feedback
            // (2026-05-08): users want the session to persist across LLM turns so a follow-up
            // "click the next link" doesn't have to re-open from scratch — that broke the
            // page state, cookies-in-flight, and the read screenshots stayed white because
            // we paid the page-load tax twice. browser_done now ONLY clears the per-task
            // 5-minute timer; the session stays alive until `/new` (TelegramBotService.handleResetCommand)
            // or the calling FGS dies. Foreground mode behaves identically — Activity keeps
            // running as before.
            buildJsonObject { put("success", true) }
        }
        textPart(out)
    },
)

// ---- Internal helpers ---------------------------------------------------------------------

private fun selectorOnlySchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    },
    required = listOf("selector"),
)

/**
 * selector + optional full flag — used by the state-changing tools (click / submit)
 * that gain the diff-after-action toggle. selector remains required; `full` defaults
 * to false (i.e. return diff). The naming matches the spec verbatim.
 */
private fun selectorWithFullSchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
        put("full", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, return the action envelope without the page-text diff (default false)")
        })
    },
    required = listOf("selector"),
)

private fun selectorAndMaxCharsSchema(defaultMax: Int, required: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector (default 'body')")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
    },
    required = if (required) listOf("selector") else null,
)

/**
 * Schema for browser_get_text. Adds the [extract_mode] enum (auto / readability /
 * raw) to the standard selector + max_chars surface. Defaults are documented inline
 * so the LLM doesn't need to hunt through the spec to know the fallback behaviour.
 */
private fun getTextSchema(defaultMax: Int): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "Optional CSS selector — when set, overrides Readability and reads the selector's innerText directly")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
        put("extract_mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("auto"); add("readability"); add("raw") })
            put("description", "auto (default) tries Readability then falls back; readability forces it; raw uses selector-based innerText")
        })
    },
)


/**
 * Snapshot the page's `document.body.innerText` for the diff-after-action path.
 * Whitespace is collapsed so that incidental layout reflows (e.g. an extra newline
 * inserted by a CSS animation that just landed) don't show up as "added" lines in
 * the diff. Returns the empty string on any JS failure — the diff helper will then
 * treat an empty before-snapshot as "everything is new", which is the conservative
 * call when we can't tell what was there.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.captureBodyText(): String {
    val raw = webView.evaluateJavascriptAsync(
        "(function(){try{return JSON.stringify(document.body.innerText||'');}catch(e){return JSON.stringify('');}})()",
        4_000L,
    ) ?: return ""
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonPrimitive.contentOrNull.orEmpty()
    }.getOrElse { "" }
}

/**
 * Token-cost optimisation pass — wrap a state-changing tool's action with a
 * before/after text snapshot so the LLM gets a diff envelope instead of a full
 * page re-read. Returns:
 *  - The action's own envelope unchanged when [full] is true (legacy path).
 *  - The action's envelope merged with `{ "diff": {...} }` when [full] is false.
 *  - An error envelope if the action returned one — diff is skipped on error so we
 *    don't push a stale snapshot when nothing changed because the action failed.
 *
 * The action is responsible for awaiting readyState if it triggers navigation; we
 * snapshot AFTER the action returns to ensure we read the post-action page.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.withDiff(
    full: Boolean,
    action: suspend BrowserControllerHandle.WithControllerScope.() -> JsonObject,
): JsonObject {
    if (full) return action()
    val before = captureBodyText()
    val result = action()
    if (result.containsKey("error")) return result
    val after = captureBodyText()
    return buildJsonObject {
        result.forEach { (k, v) -> put(k, v) }
        put("diff", BrowserDiffHelper.computeDiff(before, after))
    }
}

/**
 * Read the optional `full` arg uniformly across the state-changing tools. Default
 * false → diff path; true → preserve legacy envelope (post_*_url only). The arg
 * name matches the spec verbatim so the LLM has one concept to learn.
 */
private fun parseFullArg(input: kotlinx.serialization.json.JsonElement): Boolean =
    input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull == true

/**
 * Parse the raw string evaluateJavascript returned. Our JS helpers always return a
 * JSON.stringify(...) result, so the raw value is itself a JSON-encoded string (i.e.
 * a literal "..." with internal escapes). Unwrap once to get the real JSON object.
 *
 * Falls back to {error:'js_no_result'} on null and {error:'js_parse_failed'} on a
 * value that can't be parsed — both surface to the LLM cleanly without throwing.
 */
private fun parseJsResult(raw: String?): JsonObject {
    if (raw == null) return buildJsonObject { put("error", "js_no_result") }
    return runCatching {
        // evaluateJavascript wraps a JS string return value in JSON-quoted form, so
        // raw is "\"{...}\"" — parse the outer quoted string into a Kotlin string,
        // then parse that string as JSON.
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonObject
    }.getOrElse { buildJsonObject { put("error", "js_parse_failed"); put("raw", raw) } }
}

/**
 * Shared body for browser_get_text / browser_get_dom: same selector+max_chars schema,
 * different JS body. The [jsBuilder] returns the JS payload; the helper handles the
 * round-trip + envelope + timeout.
 */
private suspend fun runReadHelper(
    input: kotlinx.serialization.json.JsonElement,
    toolName: String,
    defaultMax: Int,
    jsBuilder: (selector: String, maxChars: Int) -> String,
): JsonObject {
    val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }) ?: "body"
    // Clamp max_chars at 100 (anything smaller is unhelpful) and 64 KB (so a runaway
    // model can't tell us to grab a megabyte of HTML).
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: defaultMax)
        .coerceIn(100, 64 * 1024)
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
        }
    } ?: timeoutEnvelope(toolName)
}

/**
 * Token-cost optimisation pass — browser_get_text body. Resolves [extract_mode] +
 * [selector] precedence:
 *  - Explicit `selector` arg → skip Readability and use selector-based innerText
 *    (the user knows what they want; we trust the model).
 *  - `extract_mode = "raw"` → selector-based innerText against `body` (current
 *    pre-pass behaviour).
 *  - `extract_mode = "readability"` → force Readability; surface
 *    `{error:"readability_failed"}` if it returns null.
 *  - `extract_mode = "auto"` (default) → try Readability; fall back to
 *    selector-based innerText if it returns null OR less than 200 chars (a
 *    too-short article is often a junk extraction — better to fall back).
 */
private const val READABILITY_MIN_CHARS = 200

private suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000)
        .coerceIn(100, 64 * 1024)
    val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"

    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            // Selector arg trumps everything — the model is being explicit, honour it.
            if (explicitSelector != null) {
                return@withController runRawText(explicitSelector, maxChars, mode = "raw_selector")
            }
            when (mode) {
                "raw" -> runRawText("body", maxChars, mode = "raw")
                "readability" -> {
                    val text = webView.runReadability()
                    if (text.isNullOrEmpty()) {
                        buildJsonObject {
                            put("error", "readability_failed")
                            put("recovery", "Try extract_mode:'auto' or pass a specific selector")
                        }
                    } else {
                        buildJsonObject {
                            val (clipped, truncated) = clipText(text, maxChars)
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    }
                }
                else -> {
                    // auto: Readability first, then selector fallback
                    val text = webView.runReadability()
                    if (!text.isNullOrEmpty() && text.length >= READABILITY_MIN_CHARS) {
                        val (clipped, truncated) = clipText(text, maxChars)
                        buildJsonObject {
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    } else {
                        runRawText("body", maxChars, mode = "raw_fallback")
                    }
                }
            }
        }
    } ?: timeoutEnvelope(BrowserToolDefaults.GET_TEXT)
}

private suspend fun BrowserControllerHandle.WithControllerScope.runRawText(
    selector: String,
    maxChars: Int,
    mode: String,
): JsonObject {
    val js = """(function(){
        try {
            var el = document.querySelector(${jsString(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
            var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
    return if (res.containsKey("error")) res else buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("extract_mode", mode)
    }
}

private fun clipText(text: String, maxChars: Int): Pair<String, Boolean> =
    if (text.length <= maxChars) text to false
    else text.substring(0, maxChars) to true

private suspend fun runHistoryNav(toolName: String, forward: Boolean): JsonObject {
    val out = withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            val ok = withContext(Dispatchers.Main) {
                if (forward) {
                    if (webView.canGoForward()) { webView.goForward(); true } else false
                } else {
                    if (webView.canGoBack()) { webView.goBack(); true } else false
                }
            }
            if (ok) webView.awaitReadyState(8_000L)
            BrowserController.appendAction(if (forward) "Forward" else "Back")
            buildJsonObject {
                put("success", ok)
                put("current_url", webView.url.orEmpty())
            }
        }
    } ?: timeoutEnvelope(toolName)
    // Pass 3: stream the post-nav screenshot to the calling chat in headless mode. Only
    // fires when the controller is actually in Mode.Headless; foreground-mode is a no-op.
    // Don't stream when we already returned an error envelope.
    if (out["success"]?.toString() == "true") {
        BrowserController.streamScreenshotIfHeadless(if (forward) "Forward" else "Back")
    }
    return out
}

/**
 * Factory dispatch for a given browser tool name. Used by
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] which iterates the per-tool
 * preference map and only constructs the ones currently enabled.
 *
 * Pass 3: [invocationContext] flows through to the two tool factories that consult it:
 *  - [browserOpenTool]: picks foreground-Activity vs headless-WebView mode by reading
 *    [HeadlessConversations.isHeadless] on the caller's conversation id.
 *  - [browserDoneTool]: in headless mode, also releases the per-conv WebView session.
 *
 * Other tool factories don't need the context — the auto-stream side of headless mode is
 * handled inside [BrowserController.streamScreenshotIfHeadless] which reads the live mode
 * directly without per-tool plumbing.
 */
fun createBrowserTool(
    toolName: String,
    context: Context,
    invocationContext: ToolInvocationContext? = null,
): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context, invocationContext)
    BrowserToolDefaults.CURRENT_URL -> browserCurrentUrlTool()
    BrowserToolDefaults.SCREENSHOT -> browserScreenshotTool(context)
    BrowserToolDefaults.GET_TEXT -> browserGetTextTool()
    BrowserToolDefaults.GET_DOM -> browserGetDomTool()
    BrowserToolDefaults.GET_LINKS -> browserGetLinksTool()
    BrowserToolDefaults.BACK -> browserBackTool()
    BrowserToolDefaults.FORWARD -> browserForwardTool()
    BrowserToolDefaults.WAIT_FOR -> browserWaitForTool()
    BrowserToolDefaults.CLICK -> browserClickTool()
    BrowserToolDefaults.TYPE -> browserTypeTool()
    BrowserToolDefaults.SCROLL -> browserScrollTool()
    BrowserToolDefaults.SUBMIT -> browserSubmitTool()
    BrowserToolDefaults.SELECT -> browserSelectTool()
    BrowserToolDefaults.PRESS_KEY -> browserPressKeyTool()
    BrowserToolDefaults.EVAL_JS -> browserEvalJsTool()
    BrowserToolDefaults.CLICK_AND_READ -> browserClickAndReadTool()
    BrowserToolDefaults.DONE -> browserDoneTool(invocationContext)
    else -> null
}
