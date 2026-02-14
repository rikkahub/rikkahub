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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * WebView-based crawler for handling dynamic content and JavaScript-heavy websites.
 * Used by RikkaLocalSearchService to enhance scraping capabilities.
 */
object WebViewCrawler {
    private const val TAG = "WebViewCrawler"
    // Use a modern desktop/mobile Chrome UA to pass some basic checks
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    suspend fun scrape(context: Context, url: String, timeout: Long = 20000L): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            // Create a headless WebView
            val webView = WebView(context)
            var isResumed = false

            val handler = Handler(Looper.getMainLooper())

            // 追踪内部 Runnable，便于取消
            val pageFinishedRunnableRef = AtomicReference<Runnable?>(null)

            val timeoutRunnable = Runnable {
                if (!isResumed) {
                    isResumed = true
                    Log.w(TAG, "Scraping timeout for: $url")
                    // 取消内部 Runnable
                    pageFinishedRunnableRef.get()?.let { handler.removeCallbacks(it) }
                    // Try to rescue content on timeout
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
                    } catch (e: Exception) {
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
                blockNetworkImage = true // Block images for speed
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = USER_AGENT
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isResumed) {
                        // Wait for dynamic content (simple delay strategy)
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
                        handler.postDelayed(pageFinishedRunnable, 1500) // Wait 1.5s for JS execution
                    }
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "WebView error: $errorCode, $description")
                    // Do not fail immediately as many sites have non-critical resource errors
                }
            }

            try {
                webView.loadUrl(url)
            } catch (e: Exception) {
                if(!isResumed) {
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

    private fun processHtml(html: String?): String {
        return html?.trim('"')
            ?.replace("\\u003C", "<")
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?.replace("\\t", "\t")
            ?: ""
    }

    /**
     * 清理 WebView 资源，防止内存泄漏
     */
    private fun cleanupWebView(webView: WebView, handler: Handler) {
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()  // 替换为空客户端，断开原回调引用
            webView.settings.javaScriptEnabled = false  // 关闭 JS 引擎
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")  // 释放当前页面内存
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView cleanup error: ${e.message}")
        }
    }
}
