package me.rerere.rikkahub.skills.js

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
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
        skillRootDir: File? = null,
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
                // Root the asset loader at the SKILL ROOT (not the script's immediate parent) so a
                // script in a subdirectory (e.g. scripts/index.html) can still fetch sibling
                // resources elsewhere in the skill (../assets/x, ../lib/y) — matching the skill
                // format. Falls back to the script's parent when the caller didn't supply a root.
                val rootDir = (skillRootDir ?: scriptFile.parentFile)
                    ?: return@withContext Result.Err("script_not_found", "skill script has no parent directory")
                // Path of the script relative to the skill root → the rest of the served URL, so
                // the WebView loads the same file regardless of how deep the script sits. Guard
                // against a script that doesn't resolve under the root (falls back to its name).
                val relPath = runCatching { scriptFile.relativeTo(rootDir).invariantSeparatorsPath }
                    .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }
                    ?: scriptFile.name
                // Serve the skill directory over a virtual https origin via WebViewAssetLoader
                // instead of loading it from file://. The asset loader confines reads to THIS
                // skill's subtree, so a malicious skill can no longer fetch arbitrary app-private
                // files (databases/, datastore) the way a file:// page with universal access could.
                //
                // InternalStoragePathHandler derives the response MIME from the file name, so an
                // entry document whose name isn't *.html (e.g. "index.htm" or extensionless) is
                // served as application/octet-stream and the WebView never parses it as HTML;
                // ai_edge_gallery_get_result is then never defined and the run dies with
                // script_timeout. Wrap the handler so ONLY the top-level entry relPath has its
                // response rewritten to text/html; every other resource/subresource keeps the
                // MIME the handler inferred (so scripts, images, CSS still load correctly).
                val storageHandler = WebViewAssetLoader.InternalStoragePathHandler(context, rootDir)
                val htmlEntryHandler = WebViewAssetLoader.PathHandler { path ->
                    val response = storageHandler.handle(path) ?: return@PathHandler null
                    if (path == relPath && response.mimeType != "text/html") {
                        WebResourceResponse(
                            "text/html",
                            response.encoding,
                            response.statusCode,
                            response.reasonPhrase,
                            response.responseHeaders,
                            response.data,
                        )
                    } else {
                        response
                    }
                }
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler(SKILL_PATH, htmlEntryHandler)
                    .build()
                val wv = WebView(context.applicationContext).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    // File access is OFF: the page is now served from https via the asset loader,
                    // not file://, so these flags are no longer needed and would only re-open the
                    // app-private-file exfiltration path.
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.domStorageEnabled = true           // localStorage for skills that store state
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // COMPATIBILITY (not ALWAYS_ALLOW): on the virtual https origin the WebView
                    // applies standard mixed-content rules. Skills hitting genuinely cross-origin
                    // https APIs (query-wikipedia → fetch) or CDN libs (qr-code → qrcodejs) still
                    // work via normal CORS; skills that relied on universal-access to bypass CORS
                    // must use a CORS-enabled endpoint.
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

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
                // https://appassets.androidplatform.net/skill/<relPath> — intercepted by the
                // asset loader and served from the skill root; never touches the network.
                val skillUrl = "https://$ASSET_DOMAIN$SKILL_PATH$relPath"
                Log.d(TAG, "loading: $skillUrl (data=${data.take(80)}, secret=${if (secret.isNotEmpty()) "<set>" else "<empty>"})")
                wv.loadUrl(skillUrl)

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
        // WebViewAssetLoader's default reserved domain; never resolves on the real network.
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val SKILL_PATH = "/skill/"
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
