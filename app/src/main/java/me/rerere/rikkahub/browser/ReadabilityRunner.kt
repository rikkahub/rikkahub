package me.rerere.rikkahub.browser

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Token-cost optimisation pass — Readability.js bridge for browser_get_text.
 *
 * ## Attribution
 *
 * This file injects Mozilla's [Readability.js](https://github.com/mozilla/readability)
 * library (`assets/browser/readability.js`) into the page to extract main-article
 * content. Readability.js is licensed Apache-2.0 © Mozilla; the bundled copy is
 * pinned to commit `08be6b4bdb204dd333c9b7a0cfbc0e730b257252` (mozilla/readability,
 * main branch, 2025-11-15). License header is preserved at the top of the asset.
 *
 * ## Why no JVM test
 *
 * Readability runs against a real DOM. The unit-test JVM has no WebView, no
 * `document`, no Element APIs — every meaningful behaviour of the runner depends
 * on a live WebView instance which Robolectric also can't synthesise without
 * pulling in the entire system WebView. Smoke-testing happens on-device during the
 * manual walk-through; the args-validation paths that DO run on JVM are exercised
 * by the existing [BrowserToolsTest] (browser_get_text envelope, missing-arg
 * envelope shape).
 *
 * ## How injection works
 *
 * On every call we re-inject the library. Tracking a per-page sentinel via
 * `window.__rikkahubReadabilityLoaded` would be marginally cheaper, but page
 * navigation resets the JS context anyway — re-injecting a 90 KB string into the
 * already-JIT-compiled context is microseconds, and "always inject" avoids the
 * footgun where the sentinel survives but the function doesn't (e.g. CSP'd page,
 * SPA route swap that nukes globals).
 */
object ReadabilityRunner {

    private const val ASSET_PATH = "browser/readability.js"
    private const val PINNED_SHA = "08be6b4bdb204dd333c9b7a0cfbc0e730b257252"

    /** Cached library text — read once from assets, reused for every injection. */
    @Volatile
    private var cachedLibrary: String? = null

    /**
     * Inject Readability.js (if not already loaded in this WebView instance) and run
     * it against a clone of the live document. Returns the extracted plain text or
     * `null` if Readability decided the page wasn't article-shaped.
     *
     * Hard timeout via [withTimeoutOrNull] — Readability rarely hangs but a
     * pathological DOM could OOM the script engine; we bound the wait so the
     * calling tool never exceeds its own 30-s tool budget.
     */
    suspend fun WebView.runReadability(timeoutMs: Long = 4_000L): String? {
        val context = this.context.applicationContext ?: return null
        val library = loadLibrary(context) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            // Inject the library, then run Readability against a cloned document.
            // Cloning is critical — Readability's `parse()` mutates the document it
            // operates on. Running against the live DOM would scrub the page from
            // under any subsequent browser_get_dom / browser_click call.
            val payload = """(function(){
                try {
                    if (typeof Readability === 'undefined') { $library }
                    var doc = document.cloneNode(true);
                    var article = new Readability(doc).parse();
                    if (!article) return JSON.stringify(null);
                    return JSON.stringify({
                        textContent: (article.textContent || '').replace(/\s+/g,' ').trim(),
                        title: article.title || '',
                        byline: article.byline || '',
                        siteName: article.siteName || '',
                        length: (article.textContent || '').length
                    });
                } catch(e) { return JSON.stringify({error: String(e)}); }
            })()"""
            val raw = this@runReadability.evaluateJavascriptAsync(payload, timeoutMs)
            parseTextContent(raw)
        }
    }

    /**
     * Test hook: which SHA the runner is pinned to. Surfaced for logging / Doctor.
     * No public API around it — the constant exists so a future asset bump bumps
     * exactly one place.
     */
    fun pinnedSha(): String = PINNED_SHA

    /**
     * Read the bundled library from `assets/browser/readability.js`. Cached on the
     * first call; any IO failure logs and returns null so the calling tool can
     * fall back to selector-based extraction without throwing.
     */
    private fun loadLibrary(context: Context): String? {
        cachedLibrary?.let { return it }
        return runCatching {
            context.assets.open(ASSET_PATH).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
        }.onSuccess { cachedLibrary = it }
            .onFailure { android.util.Log.w("ReadabilityRunner", "asset load failed", it) }
            .getOrNull()
    }

    /**
     * Parse the JSON-encoded result the WebView returned. evaluateJavascript wraps
     * a JS string return in JSON quotes, so [raw] is a doubly-encoded string
     * (`"\"{...}\""`) that we unwrap once into the inner JSON object, then read the
     * `textContent` field. Returns null on `JSON.stringify(null)` (Readability
     * couldn't parse the page) and on any decode error.
     */
    private fun parseTextContent(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "\"null\"") return null
        return runCatching {
            val outer = Json.parseToJsonElement(raw)
            val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty()
                        else outer.toString()
            val element = Json.parseToJsonElement(inner)
            // The JS payload returned `null` directly (no article) — surface as null.
            if (element is JsonPrimitive && element.contentOrNull == null) return@runCatching null
            val obj = element.jsonObject
            // Error envelope from inside the JS — surface as null so the caller can
            // fall back to selector-based extraction. Logging the detail would be
            // noisy on every non-article page; skip.
            if (obj.containsKey("error")) return@runCatching null
            obj["textContent"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
