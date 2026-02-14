package me.rerere.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebView-based crawler for handling dynamic content and JavaScript-heavy websites.
 * Used by RikkaLocalSearchService to enhance scraping capabilities.
 */
object WebViewCrawler {
    private const val TAG = "WebViewCrawler"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Hard limits to avoid very large WebView payloads from causing memory pressure.
    private const val MAX_RAW_HTML_CHARS = 2_000_000
    private const val MAX_PROCESSED_HTML_CHARS = 1_200_000
    private val scrapeSemaphore = Semaphore(1)

    suspend fun scrape(context: Context, url: String, timeout: Long = 20000L): String =
        scrapeSemaphore.withPermit {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(context)
                    var isResumed = false

                    val handler = Handler(Looper.getMainLooper())
                    val pageFinishedRunnableRef = AtomicReference<Runnable?>(null)

                    val timeoutRunnable = Runnable {
                        if (!isResumed) {
                            isResumed = true
                            Log.w(TAG, "Scraping timeout for: $url")
                            pageFinishedRunnableRef.get()?.let { handler.removeCallbacks(it) }
                            try {
                                webView.evaluateJavascript(
                                    "(function() { return document.documentElement.outerHTML; })();"
                                ) { html ->
                                    val content = processHtml(html)
                                    if (content.isNotEmpty()) {
                                        continuation.resume(content)
                                    } else {
                                        continuation.resumeWithException(TimeoutException("Scraping timeout"))
                                    }
                                    cleanupWebView(webView, handler)
                                }
                            } catch (_: Exception) {
                                continuation.resumeWithException(TimeoutException("Scraping timeout and failed to retrieve content"))
                                cleanupWebView(webView, handler)
                            }
                        }
                    }
                    handler.postDelayed(timeoutRunnable, timeout)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        blockNetworkImage = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = USER_AGENT
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!isResumed) {
                                val pageFinishedRunnable = Runnable {
                                    if (!isResumed) {
                                        view?.evaluateJavascript(
                                            "(function() { return document.documentElement.outerHTML; })();"
                                        ) { html ->
                                            isResumed = true
                                            handler.removeCallbacks(timeoutRunnable)
                                            val content = processHtml(html)
                                            continuation.resume(content)
                                            cleanupWebView(webView, handler)
                                        }
                                    }
                                }
                                pageFinishedRunnableRef.set(pageFinishedRunnable)
                                handler.postDelayed(pageFinishedRunnable, 1500)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            Log.e(TAG, "WebView error: $errorCode, $description")
                        }
                    }

                    try {
                        webView.loadUrl(url)
                    } catch (e: Exception) {
                        if (!isResumed) {
                            isResumed = true
                            handler.removeCallbacks(timeoutRunnable)
                            pageFinishedRunnableRef.get()?.let { handler.removeCallbacks(it) }
                            continuation.resumeWithException(e)
                            cleanupWebView(webView, handler)
                        }
                    }

                    continuation.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                        pageFinishedRunnableRef.get()?.let { handler.removeCallbacks(it) }
                        cleanupWebView(webView, handler)
                    }
                }
            }
        }

    private fun processHtml(html: String?): String {
        if (html.isNullOrBlank()) return ""

        val raw = html.trim('"')
        if (raw.isEmpty()) return ""

        val boundedRaw = if (raw.length > MAX_RAW_HTML_CHARS) {
            raw.substring(0, MAX_RAW_HTML_CHARS)
        } else {
            raw
        }

        val processed = boundedRaw
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")

        return if (processed.length > MAX_PROCESSED_HTML_CHARS) {
            processed.substring(0, MAX_PROCESSED_HTML_CHARS)
        } else {
            processed
        }
    }

    private fun cleanupWebView(webView: WebView, handler: Handler) {
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.settings.javaScriptEnabled = false
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView cleanup error: ${e.message}")
        }
    }
}
