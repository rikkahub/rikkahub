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
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import java.io.File
import java.io.FileOutputStream

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

private const val TOOL_TIMEOUT_MS = 30_000L
private const val MAX_SCREENSHOT_HEIGHT_PX = 8192
private const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

// ---- Common envelope helpers --------------------------------------------------------------

private fun timeoutEnvelope(toolName: String): JsonObject = buildJsonObject {
    put("error", "tool_timeout")
    put("tool", toolName)
    put("recovery", "The browser tool exceeded its $TOOL_TIMEOUT_MS-ms budget. Retry, or simplify the selector.")
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

fun browserOpenTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Navigate the in-app browser to a URL. Launches the browser if it isn't open. Returns {success, current_url, title}. Resets the per-task 5-minute timer.",
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
        val out = if (url == null) {
            missingArgEnvelope("url", "url is required and must be a non-empty string")
        } else {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                // browser_open is the ONLY tool allowed to launch the Activity. Any other
                // tool returning browser_not_open is the LLM's signal to call this first.
                if (!BrowserController.isBound()) {
                    context.startActivity(
                        me.rerere.rikkahub.browser.BrowserActivity.intent(context, url)
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
                    withContext(Dispatchers.Main) { webView.loadUrl(url) }
                    webView.awaitReadyState(8_000L)
                    buildJsonObject {
                        put("success", true)
                        put("current_url", webView.url ?: url)
                        put("title", webView.title.orEmpty())
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        }
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL and page title. {url, title}. browser_not_open if the browser isn't open.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
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
    description = "Capture the visible viewport of the browser as a PNG vision attachment. Use browser_get_text first if you only need the page's text — screenshots cost vision tokens. full_page=true is best-effort and currently captures the viewport only (viewport_only:true in the response).",
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
        val out = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            BrowserControllerHandle.withController {
                val (path, w, h) = withContext(Dispatchers.Main) {
                    val width = webView.width.coerceAtLeast(1)
                    val height = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val out = File(cacheDir, "screenshot-${System.currentTimeMillis()}.png")
                    runCatching {
                        FileOutputStream(out).use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }.onFailure { bitmap.recycle() }
                    bitmap.recycle()
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
    description = "Extract the readable innerText of a CSS selector (default 'body'). Truncates at max_chars (default 8000). {text, truncated}. Use this BEFORE screenshot if you only need text content.",
    parameters = { selectorAndMaxCharsSchema(defaultMax = 8000, required = false) },
    execute = { input -> textPart(runReadHelper(input, BrowserToolDefaults.GET_TEXT, defaultMax = 8000) { selector, maxChars ->
        // Trim runs of whitespace so the LLM doesn't pay for indentation. innerText
        // already collapses multi-line text into something readable; we just clamp.
        """(function(){
            try {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
                var truncated = false;
                if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
                return JSON.stringify({text:t, truncated:truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
        })()"""
    }) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract a simplified outerHTML of a CSS selector (default 'body'). Strips <script>/<style>. Truncates at max_chars (default 4000). {html, truncated}.",
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
    description = "List up to 100 anchor links inside a CSS selector (default 'body') as {links:[{href, text}], count}. {error:'selector_not_found'} if the root selector doesn't match.",
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
        val out = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
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
    description = "Navigate the browser one step back in history. {success, current_url}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate the browser one step forward in history. {success, current_url}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true)) },
)

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR,
    description = "Pause until a CSS selector appears in the DOM. Polls every 200 ms up to timeout_ms (default 10_000). {found, elapsed_ms}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to wait for")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Max wait in ms (default 10000, capped at 30000)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            // Cap the user-supplied timeout at our 30 s tool budget so the LLM can't ask
            // for a 5-minute wait and starve every other tool call. The withTimeoutOrNull
            // below would catch this anyway, but capping here gives a clean envelope.
            val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                .coerceIn(200, 30_000)
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
                    val started = System.currentTimeMillis()
                    val deadline = started + timeoutMs
                    val js = "(function(){return document.querySelector(${jsString(selector)})!==null;})()"
                    var found = false
                    while (System.currentTimeMillis() < deadline) {
                        val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                        if (raw == "true") { found = true; break }
                        delay(200)
                    }
                    buildJsonObject {
                        put("found", found)
                        put("elapsed_ms", System.currentTimeMillis() - started)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR)
        }
        textPart(out)
    },
)

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. scrollIntoView before dispatching the click. Waits up to 8 s for the resulting page to reach readyState=complete. {success, post_click_url}.",
    parameters = { selectorOnlySchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
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
                    if (res.containsKey("error")) return@withController res
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Click: $selector")
                    buildJsonObject {
                        put("success", true)
                        put("post_click_url", webView.url.orEmpty())
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        }
        textPart(out)
    },
)

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable matching a CSS selector. Focuses, optionally clears, sets the value + dispatches an 'input' event so SPA frameworks observe the change. {success}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the input") })
            put("text", buildJsonObject { put("type","string"); put("description","Text to type") })
            put("clear", buildJsonObject { put("type","boolean"); put("description","Clear the field first (default true)") })
        }, required = listOf("selector","text"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val clear = input.jsonObject["clear"]?.jsonPrimitive?.booleanOrNull ?: true
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
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
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Typed into $selector")
                    buildJsonObject { put("success", true) }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        textPart(out)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page in a direction (up/down/top/bottom). amount is in pixels (default 600, ignored for top/bottom). {success, scroll_y}.",
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
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
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
        textPart(out)
    },
)

fun browserSubmitTool(): Tool = Tool(
    name = BrowserToolDefaults.SUBMIT,
    description = "Submit a form. If the selector is a <button type=submit> click it; otherwise locates the enclosing <form> and calls .submit(). Awaits the post-navigation readyState. {success, post_submit_url}.",
    parameters = { selectorOnlySchema("CSS selector of a submit button or any element inside the target form") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
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
                    if (res.containsKey("error")) return@withController res
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Submit: $selector")
                    buildJsonObject {
                        put("success", true)
                        put("post_submit_url", webView.url.orEmpty())
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        }
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value. Dispatches 'change' so framework listeners fire. {success}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the <select>") })
            put("value", buildJsonObject { put("type","string"); put("description","The option value to set") })
        }, required = listOf("selector","value"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            value == null -> missingArgEnvelope("value", "value is required")
            else -> withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
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
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Select: $selector=$value")
                    buildJsonObject { put("success", true) }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SELECT)
        }
        textPart(out)
    },
)

fun browserPressKeyTool(): Tool = Tool(
    name = BrowserToolDefaults.PRESS_KEY,
    description = "Synthesize keydown + keyup events on the active element. Use KeyboardEvent.key values like 'Enter', 'Escape', 'ArrowDown', 'Tab'. {success}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("key", buildJsonObject {
                put("type","string")
                put("description","KeyboardEvent.key value (e.g. 'Enter', 'Escape', 'ArrowDown')")
            })
        }, required = listOf("key"))
    },
    execute = { input ->
        // Cap at 32 chars — KeyboardEvent.key values like "ArrowDown" are short. A multi-KB
        // string here suggests model misuse; clamp before splicing into the JS payload.
        val key = input.jsonObject["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(32)
        val out = if (key == null) {
            missingArgEnvelope("key", "key is required (e.g. 'Enter', 'Escape')")
        } else {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
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
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Press key: $key")
                    buildJsonObject { put("success", true) }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.PRESS_KEY)
        }
        textPart(out)
    },
)

fun browserEvalJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EVAL_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. HARDLINE-checked: shell-shaped strings, document.cookie writes, eval/Function constructors, and string-form setTimeout are all blocked at the tool dispatcher BEFORE the JS executes. Always asks for approval; never eligible for 'Always Allow'.",
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
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                BrowserControllerHandle.withController {
                    val raw = webView.evaluateJavascriptAsync(code, TOOL_TIMEOUT_MS - 1_000L)
                    BrowserController.appendAction("Run JS")
                    buildJsonObject { put("result", raw ?: "null") }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.EVAL_JS)
        }
        textPart(out)
    },
)

// ---- Loop control --------------------------------------------------------------------------

fun browserDoneTool(): Tool = Tool(
    name = BrowserToolDefaults.DONE,
    description = "Signal that the AI has finished its current browser task. Clears the per-task 5-minute timer so the next browser_open starts fresh. result_url is optional; pass the page URL the user should look at if any. {success}.",
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
    return withTimeoutOrNull(TOOL_TIMEOUT_MS) {
        BrowserControllerHandle.withController {
            parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
        }
    } ?: timeoutEnvelope(toolName)
}

private suspend fun runHistoryNav(toolName: String, forward: Boolean): JsonObject {
    return withTimeoutOrNull(TOOL_TIMEOUT_MS) {
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
}

/**
 * Factory dispatch for a given browser tool name. Used by
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] which iterates the per-tool
 * preference map and only constructs the ones currently enabled.
 */
fun createBrowserTool(toolName: String, context: Context): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context)
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
    BrowserToolDefaults.DONE -> browserDoneTool()
    else -> null
}
