package me.rerere.rikkahub.skills.js

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "JsSkillRunner"

/**
 * Phase 18 — runs a skill's JavaScript inside a hidden WebView and returns the result to
 * the LLM. Mirrors Google AI Edge Gallery's contract exactly so any skill written for
 * Gallery's format (with `window['ai_edge_gallery_get_result'](data, secret)`) works
 * verbatim in this app.
 *
 * Lifecycle: create one WebView per invocation, load `file://<skillDir>/<scriptName>`,
 * wait for `onPageFinished`, evaluate the trigger script, await the result via the
 * `AiEdgeGallery.onResultReady(json)` JS-bridge callback, then destroy the WebView.
 *
 * Per-call timeout: 60s wall-clock — matches Gallery's safety net at AgentChatScreen:246.
 *
 * Thread model: WebView is touchable only on the main thread. The runner launches all
 * WebView ops on `Dispatchers.Main` and suspends the calling coroutine on a
 * CompletableDeferred until the JS bridge fires.
 *
 * Result JSON shape (matches Gallery):
 * ```
 * {
 *   "result": "string text the LLM should see",
 *   "image": { "base64": "..." },                      // optional
 *   "webview": { "iframe": true, "url": "...", "aspectRatio": 1.333 }, // optional
 *   "error": "string, if anything failed"              // optional
 * }
 * ```
 */
class JsSkillRunner(private val context: Context) {

    sealed class Result {
        data class Ok(val parsed: ParsedResult) : Result()
        data class Err(val code: String, val detail: String) : Result()
    }

    /**
     * Decoded shape of the JS skill's return value. Any combination of [text], [imageBase64],
     * and [webviewUrl] may be present — Gallery's contract permits a skill to return both an
     * image AND a text summary. The runner exposes whatever the JS produced; the caller
     * decides how to fold it into the chat surface.
     */
    data class ParsedResult(
        val text: String? = null,
        val imageBase64: String? = null,
        val webviewUrl: String? = null,
        val webviewIframe: Boolean = true,
        val webviewAspectRatio: Float = 4f / 3f,
        val error: String? = null,
    )

    /**
     * Run [scriptFile] with [data] (and optional [secret] for skills that need an API key).
     * Returns either a [Result.Ok] with parsed return values or [Result.Err] with a stable
     * code the caller can surface to the LLM.
     *
     * Caller must ensure [scriptFile] is inside the skill's directory — JsSkillRunner does
     * NOT path-validate. The `run_js` tool factory does that via SkillManager.
     */
    suspend fun runScript(
        scriptFile: File,
        data: String,
        secret: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (!scriptFile.exists() || !scriptFile.isFile) {
            return Result.Err("script_not_found", "no JS skill script at ${scriptFile.absolutePath}")
        }
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            var webView: WebView? = null
            try {
                val wv = WebView(context.applicationContext).apply {
                    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
                    settings.javaScriptEnabled = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = true                       // file:// load of skill dir
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true           // file:// can fetch siblings
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true      // so cross-origin XHR works for Wikipedia etc
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.domStorageEnabled = true           // localStorage for skills that store state
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // Skills run in a hidden 1×1 sandboxed WebView. ALWAYS_ALLOW lets skills
                    // that load CDN libraries (e.g. qr-code → qrcodejs) or reach external APIs
                    // (query-wikipedia → fetch) work without CORS/mixed-content errors while
                    // the page is served from file://. This WebView never navigates freely, so
                    // the security trade-off is narrow and deliberate.
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
                wv.addJavascriptInterface(BridgeImpl(deferred), JS_INTERFACE_NAME)
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d(TAG, "[JS console] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "page finished, evaluating trigger script: $url")
                        // The trigger waits up to 10s for the page's
                        // `ai_edge_gallery_get_result` to be defined, then invokes it and
                        // hands the result back via the JS bridge. JSONObject.quote escapes
                        // the data + secret strings safely.
                        val safeData = JSONObject.quote(data)
                        val safeSecret = JSONObject.quote(secret)
                        val script = """
                            (async function() {
                                var startTs = Date.now();
                                while (true) {
                                    if (typeof ai_edge_gallery_get_result === 'function') break;
                                    await new Promise(r => setTimeout(r, 100));
                                    if (Date.now() - startTs > 10000) {
                                        $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "ai_edge_gallery_get_result not defined within 10s"}));
                                        return;
                                    }
                                }
                                try {
                                    var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                                    $JS_INTERFACE_NAME.onResultReady(typeof result === 'string' ? result : JSON.stringify(result));
                                } catch (e) {
                                    $JS_INTERFACE_NAME.onResultReady(JSON.stringify({error: "JS threw: " + (e && e.message ? e.message : String(e))}));
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
                webView = wv
                val fileUrl = scriptFile.toUri().toString()  // file://...
                Log.d(TAG, "loading: $fileUrl (data=${data.take(80)}, secret=${if (secret.isNotEmpty()) "<set>" else "<empty>"})")
                wv.loadUrl(fileUrl)

                // Suspend the calling coroutine until the bridge fires or timeout. We let the
                // 10s within-script wait above handle "page loaded but JS missing"; the outer
                // timeout is a hard wall on infinite loops or unresponsive scripts.
                val resultJson = withTimeout(timeoutMs) { deferred.await() }
                Result.Ok(parseResultJson(resultJson))
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "JS skill execution timed out after ${timeoutMs}ms")
                Result.Err("script_timeout",
                    "JS skill execution exceeded ${timeoutMs}ms — check for infinite loops or unresponsive network calls")
            } catch (t: Throwable) {
                Log.w(TAG, "JS skill execution failed", t)
                Result.Err("script_failed", "${t::class.simpleName}: ${t.message.orEmpty()}")
            } finally {
                runCatching { webView?.destroy() }
                    .onFailure { Log.w(TAG, "WebView.destroy failed", it) }
            }
        }
    }

    private fun parseResultJson(json: String): ParsedResult {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return ParsedResult(error = "JS skill returned non-JSON: $json")
        val errorRaw = obj["error"]?.jsonPrimitive?.contentOrNull
        val text = obj["result"]?.jsonPrimitive?.contentOrNull
        val imageBase64 = (obj["image"] as? JsonObject)?.get("base64")?.jsonPrimitive?.contentOrNull
        val webview = obj["webview"] as? JsonObject
        val webviewUrl = webview?.get("url")?.jsonPrimitive?.contentOrNull
        val webviewIframe = (webview?.get("iframe") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true
        val aspectRatio = webview?.get("aspectRatio")?.let {
            runCatching { it.toString().toFloat() }.getOrNull()
        } ?: (4f / 3f)
        return ParsedResult(
            text = text,
            imageBase64 = imageBase64,
            webviewUrl = webviewUrl,
            webviewIframe = webviewIframe,
            webviewAspectRatio = aspectRatio,
            error = errorRaw,
        )
    }

    /**
     * JS bridge object. The page calls `AiEdgeGallery.onResultReady(json)` to deliver the
     * skill result. We complete the deferred from the Android-bound thread (NOT main) since
     * @JavascriptInterface methods run on a binder thread; CompletableDeferred is safe.
     */
    private class BridgeImpl(private val target: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onResultReady(json: String) {
            Log.d(TAG, "JS bridge fired: ${json.take(120)}")
            target.complete(json)
        }
    }

    companion object {
        private const val JS_INTERFACE_NAME = "AiEdgeGallery"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 5 * 60_000L
        const val MAX_DATA_LENGTH = 64 * 1024  // 64KB cap on the data payload
    }
}

/**
 * Persist the decoded image to app cache, return file:// URL the chat renderer can show.
 * Matches the existing UIMessagePart.Image(url) shape so the chat surface needs no changes.
 */
fun decodeBase64ImageToCacheFile(context: Context, base64: String): File? = runCatching {
    val cleanBase64 = base64.substringAfter(",")  // strip data:image/...;base64, prefix if any
    val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
    val outDir = File(context.cacheDir, "skill_results").also { it.mkdirs() }
    val out = File(outDir, "${UUID.randomUUID()}.png")
    out.writeBytes(bytes)
    out
}.getOrNull()
