package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.BuildConfig
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Top-level Compose root for [BrowserActivity]. Lays out:
 *   - [BrowserAddressBar] (top — read-only URL + nav controls)
 *   - [WebView] embedded via AndroidView (middle — fills weight=1f)
 *   - [BrowserAiStripe] (bottom — AI status, expandable to recent actions list)
 *
 * The WebView is created exactly once via `remember { WebView(ctx).apply { … } }` and
 * reused across recompositions — recreating it on every recomp would lose page state
 * and re-fetch the URL. Same pattern as Phase 19's WebViewPage.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserView(
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    canGoBackState: MutableState<Boolean>,
    canGoForwardState: MutableState<Boolean>,
    currentUrlState: MutableState<String>,
    currentTitleState: MutableState<String>,
    loadProgressState: MutableState<Int>,
    onClose: () -> Unit,
    onBackTap: () -> Unit,
    onForwardTap: () -> Unit,
    onRefreshTap: () -> Unit,
    onStopAi: () -> Unit,
    onNavigate: (String) -> Unit,
    initialUrl: String,
    conversationId: Uuid?,
) {
    Scaffold(
        topBar = {
            BrowserAddressBar(
                url = currentUrlState.value,
                canGoBack = canGoBackState.value,
                canGoForward = canGoForwardState.value,
                onClose = onClose,
                onBack = onBackTap,
                onForward = onForwardTap,
                onRefresh = onRefreshTap,
                onStopAi = onStopAi,
                onNavigate = onNavigate,
            )
        },
        bottomBar = {
            BrowserAiStripe()
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (loadProgressState.value in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgressState.value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                WebViewHost(
                    modifier = Modifier.fillMaxSize(),
                    initialUrl = initialUrl,
                    onWebViewReady = onWebViewReady,
                    onUrlChange = onUrlChange,
                    onTitleChange = onTitleChange,
                    onLoadProgress = onLoadProgress,
                    onCanGoBackChange = onCanGoBackChange,
                    onCanGoForwardChange = onCanGoForwardChange,
                )
            }
            // Bottom-anchored mini-chat overlay. Self-hides when conversationId is null
            // (manual launch from Settings without a chat context).
            BrowserMiniChat(
                conversationId = conversationId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewHost(
    modifier: Modifier = Modifier,
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // Construct the WebView ONCE — `remember` survives recomp + (with key=Unit) survives
    // configuration changes (the Activity declares `configChanges` so the system never
    // recreates us). Recreating per-recomp would reset history, scroll, and JS state.
    val webView = remember {
        // Enable Chrome DevTools attachment ONLY in debug builds. In release, leaving
        // this on lets anyone with adb (lent phone, ADB-over-WiFi attacker) attach
        // chrome://inspect and read the WebView's cookies / localStorage / authenticated
        // session bodies. Gate behind BuildConfig.DEBUG — turning Chrome inspection on
        // in release is a privacy posture choice the user never consented to.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            // Shared with HeadlessBrowserSession — every render-related setting
            // (mixedContentMode, hardware layer, autoplay, UA strip, file:// access)
            // lives in configureWebViewForRikka so foreground + headless behave
            // identically. See BrowserWebViewConfig.kt for the why.
            configureWebViewForRikka(this)

            // Profile dir is informational — the global WebView databases live where the
            // WebView wants. We create the dir ourselves in BrowserActivity.onCreate so
            // Pass 3's "Clear browsing data" has a stable target to wipe.
            File(ctx.filesDir, "browser-profile").apply { if (!exists()) mkdirs() }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Block page/JS-initiated navigation into file:// unless the current
                    // document is already file:// (skill webview cards moving between
                    // their own sub-pages). App-initiated loadUrl() bypasses this
                    // callback, so opening a skill card stays unaffected. Without this,
                    // a browsed page (or eval'd JS) could steer the WebView into
                    // app-private files and exfiltrate them via browser_get_text.
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    return toFile && view?.url?.startsWith("file:", ignoreCase = true) != true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) onUrlChange(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) onUrlChange(url)
                    onCanGoBackChange(view?.canGoBack() == true)
                    onCanGoForwardChange(view?.canGoForward() == true)
                    // Adb-friendly white-page diagnostic. Tag = "RikkaWebView". Filter:
                    //   adb logcat -s RikkaWebView
                    // Dumps body innerText length + first 100 chars + meta viewport so
                    // you can tell at a glance whether the DOM is populated (render bug)
                    // or empty (load bug) without needing chrome://inspect.
                    view?.evaluateJavascript(
                        """
                        JSON.stringify({
                          rs: document.readyState,
                          docLen: (document.documentElement ? document.documentElement.outerHTML.length : 0),
                          bodyLen: (document.body ? document.body.innerText.length : 0),
                          children: (document.body ? document.body.children.length : 0),
                          firstText: (document.body ? document.body.innerText.slice(0, 100) : ''),
                          viewport: (function(){var m=document.querySelector('meta[name=viewport]');return m?m.content:'';})()
                        })
                        """.trimIndent(),
                    ) { json ->
                        Log.d("RikkaWebView", "onPageFinished $url -> $json")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    val isMainFrame = request?.isForMainFrame == true
                    Log.w(
                        "RikkaWebView",
                        "onReceivedError mainFrame=$isMainFrame url=${request?.url} code=${error?.errorCode} desc=${error?.description}",
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onLoadProgress(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) onTitleChange(title)
                }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    // Surface page-side console.log / console.error to logcat so the user
                    // can debug white pages from a terminal without DevTools.
                    if (message != null) {
                        val priority = when (message.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            else -> Log.INFO
                        }
                        Log.println(
                            priority,
                            "RikkaWebViewConsole",
                            "${message.sourceId()}:${message.lineNumber()} ${message.message()}",
                        )
                    }
                    return true
                }
            }

            loadUrl(initialUrl)
            onWebViewReady(this)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}
