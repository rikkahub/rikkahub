package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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
        // Enable Chrome DevTools attachment for the WebView once per process. Costs
        // nothing at runtime (debug builds enable it by default in many apps) but lets
        // us attach `chrome://inspect` from a desktop browser when a page renders weird.
        WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION") // Removed in API 35 but the symbol is still here at compile time
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                // Match Chrome's default — most modern pages assume autoplay is allowed.
                // Forcing user gesture made some news / video pages render blank because
                // their player JS errored out before the layout settled.
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                // Many HTTPS pages pull HTTP images / scripts (analytics, ads, fonts).
                // The default NEVER_ALLOW silently blocks all of them, leaving white
                // pages on a depressing number of mainstream sites. COMPATIBILITY_MODE
                // is what stock Chrome ships and matches the user's expectation that
                // "page works in Chrome → page works here".
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // User-Agent: leave the WebView default. Spec rationale: most bot detectors
                // are happy with "looks like Chrome on Android"; modifying gives a worse signal.
            }
            // Force the WebView onto a hardware layer. Compose's AndroidView interop has
            // a known quirk where the WebView sometimes loses its hardware layer when
            // hosted inside a Box and ends up rendering pages all-white. Setting it
            // explicitly is cheap and idempotent.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Profile dir is informational — the global WebView databases live where the
            // WebView wants. We create the dir ourselves in BrowserActivity.onCreate so
            // Pass 3's "Clear browsing data" has a stable target to wipe.
            File(ctx.filesDir, "browser-profile").apply { if (!exists()) mkdirs() }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) onUrlChange(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) onUrlChange(url)
                    onCanGoBackChange(view?.canGoBack() == true)
                    onCanGoForwardChange(view?.canGoForward() == true)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onLoadProgress(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) onTitleChange(title)
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
