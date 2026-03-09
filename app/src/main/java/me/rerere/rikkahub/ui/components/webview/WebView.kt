package me.rerere.rikkahub.ui.components.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "WebView"

private fun WebContent.Url.signature(): Int {
    var result = url.hashCode()
    result = 31 * result + additionalHttpHeaders.hashCode()
    result = 31 * result + clearHistory.hashCode()
    return result
}

private fun WebContent.Data.signature(): Int {
    var result = data.hashCode()
    result = 31 * result + (baseUrl?.hashCode() ?: 0)
    result = 31 * result + encoding.hashCode()
    result = 31 * result + (mimeType?.hashCode() ?: 0)
    result = 31 * result + (historyUrl?.hashCode() ?: 0)
    return result
}

internal class MyWebChromeClient(private val state: () -> WebViewState) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        state().loadingProgress = newProgress / 100f
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        state().pageTitle = title
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val currentState = state()
        currentState.pushConsoleMessage(consoleMessage)
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR || consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
            Log.e(
                TAG,
                "onConsoleMessage:  ${consoleMessage.message()}  ${consoleMessage.lineNumber()}  ${consoleMessage.sourceId()}"
            )
        }
        return super.onConsoleMessage(consoleMessage);
    }
}

internal class MyWebViewClient(private val state: () -> WebViewState) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val currentState = state()
        currentState.isLoading = true
        currentState.currentUrl = url // Update current URL
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val currentState = state()
        currentState.isLoading = false
        currentState.loadingProgress = 0f // Reset progress when finished
        currentState.pageTitle = view?.title // Update title
        currentState.canGoBack = view?.canGoBack() == true
        currentState.canGoForward = view?.canGoForward() == true
    }
}

private fun WebView.syncJavascriptInterfaces(state: WebViewState) {
    val installedInterfaces = state.attachedInterfaces
    (installedInterfaces.keys - state.interfaces.keys).forEach { name ->
        removeJavascriptInterface(name)
    }
    state.interfaces.forEach { (name, obj) ->
        if (installedInterfaces[name] !== obj) {
            if (name in installedInterfaces) {
                removeJavascriptInterface(name)
            }
            addJavascriptInterface(obj, name)
        }
    }
    state.attachedInterfaces = state.interfaces
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    allowFocus: Boolean = false,
    onCreated: (WebView) -> Unit = {},
    onUpdated: (WebView) -> Unit = {},
) {
    val currentState = rememberUpdatedState(state)
    val webChromeClient = remember { MyWebChromeClient { currentState.value } }
    val webViewClient = remember { MyWebViewClient { currentState.value } }
    val androidViewModifier = Modifier
        .fillMaxWidth()
        .focusProperties {
            canFocus = allowFocus
        }

    Box(
        modifier = modifier
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )
                    // Keep WebView composable-friendly and avoid default white flash/background.
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isFocusable = allowFocus
                    isFocusableInTouchMode = allowFocus
                    descendantFocusability = if (allowFocus) {
                        ViewGroup.FOCUS_AFTER_DESCENDANTS
                    } else {
                        ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }

                    state.webView = this // Assign the WebView instance to the state

                    onCreated(this)

                    settings.javaScriptEnabled = true // Enable JavaScript
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.apply(state.settings)

                    // Use the created clients
                    this.webChromeClient = webChromeClient
                    this.webViewClient = webViewClient

                    syncJavascriptInterfaces(state)
                }
            },
            modifier = androidViewModifier, // Make WebView fill the width
            onReset = {
                it.clearFocus()
                it.stopLoading()
                state.attachedInterfaces.keys.forEach { name ->
                    it.removeJavascriptInterface(name)
                }
                state.attachedInterfaces = emptyMap()
                if (state.webView === it) {
                    state.webView = null
                }
                state.clearLoadSignatures()
                Log.d(TAG, "AndroidView: Resetting WebView")
            },
            onRelease = {
                it.clearFocus()
                it.stopLoading()
                state.attachedInterfaces.keys.forEach { name ->
                    it.removeJavascriptInterface(name)
                }
                state.attachedInterfaces = emptyMap()
                if (state.webView === it) {
                    state.webView = null
                }
                state.clearLoadSignatures()
                it.destroy()
                Log.d(TAG, "AndroidView: Releasing WebView")
            },
            update = { webView ->
                state.webView = webView
                webView.syncJavascriptInterfaces(state)
                Log.d(TAG, "AndroidView: Updating WebView")
                webView.webChromeClient = webChromeClient
                webView.webViewClient = webViewClient

                // Update settings that might change
                webView.settings.javaScriptEnabled = state.javaScriptEnabled
                webView.isFocusable = allowFocus
                webView.isFocusableInTouchMode = allowFocus
                webView.descendantFocusability = if (allowFocus) {
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
                } else {
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                if (!allowFocus && webView.hasFocus()) {
                    webView.clearFocus()
                }

                when (val content = state.content) {
                    is WebContent.Url -> {
                        val url = content.url
                        val currentWebViewUrl = webView.url
                        val signature = content.signature()
                        val shouldLoad = url.isNotEmpty() && (
                            state.forceReload ||
                                currentWebViewUrl.isNullOrBlank() ||
                                state.lastLoadedUrlSignature != signature
                            )
                        if (shouldLoad) {
                            webView.loadUrl(content.url, content.additionalHttpHeaders)
                            state.lastLoadedUrlSignature = signature
                            state.lastLoadedDataSignature = null
                            state.forceReload = false
                        }
                    }

                    is WebContent.Data -> {
                        val signature = content.signature()
                        val shouldLoad = state.forceReload || state.lastLoadedDataSignature != signature
                        if (shouldLoad) {
                            webView.loadDataWithBaseURL(
                                content.baseUrl,
                                content.data,
                                content.mimeType,
                                content.encoding,
                                content.historyUrl
                            )
                            state.lastLoadedDataSignature = signature
                            state.lastLoadedUrlSignature = null
                            state.forceReload = false
                        }
                    }

                    WebContent.NavigatorOnly -> {
                        // NO-OP: State changes related to navigation are handled by the methods in WebViewState
                    }
                }
                onUpdated(webView)
            }
        )

        // Loading Progress Indicator
        if (state.isLoading) {
            LinearProgressIndicator(
                progress = { state.loadingProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// --- State and Content Definition ---
sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
        val clearHistory: Boolean = false
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null
    ) : WebContent()

    data object NavigatorOnly : WebContent()
}

@Stable // Mark as Stable for better Compose performance
class WebViewState(
    initialContent: WebContent = WebContent.NavigatorOnly,
    val interfaces: Map<String, Any> = emptyMap(),
    val settings: WebSettings.() -> Unit = {}
) {
    // --- Content State ---
    var content: WebContent by mutableStateOf(initialContent)
    internal var forceReload: Boolean by mutableStateOf(false) // Internal state to force URL reload if needed
    internal var lastLoadedUrlSignature: Int? = null
    internal var lastLoadedDataSignature: Int? = null
    internal var attachedInterfaces: Map<String, Any> = emptyMap()

    // --- Loading State ---
    var isLoading: Boolean by mutableStateOf(false)
        internal set // Only WebViewClients should modify this
    var loadingProgress: Float by mutableFloatStateOf(0f)
        internal set

    // --- Page Information ---
    var pageTitle: String? by mutableStateOf(null)
        internal set
    var currentUrl: String? by mutableStateOf(null)
        internal set

    // --- Navigation State ---
    var canGoBack: Boolean by mutableStateOf(false)
        internal set
    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    // --- Console Message ---
    var consoleMessages: List<ConsoleMessage> by mutableStateOf(emptyList())
        internal set

    // --- Settings ---
    var javaScriptEnabled: Boolean by mutableStateOf(true) // Example setting

    // --- WebView Instance ---
    // Hold the WebView instance internally to perform actions.
    // Be cautious with this reference, ensure it doesn't leak context.
    internal var webView: WebView? by mutableStateOf(null)

    // --- Public Actions ---

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String> = emptyMap()
    ) {
        // Determine if reload is needed: same URL or explicit force flag set elsewhere
        forceReload =
            (content is WebContent.Url && (content as WebContent.Url).url == url) || forceReload
        content = WebContent.Url(url, additionalHttpHeaders)
    }

    fun loadData(
        data: String,
        baseUrl: String? = null,
        encoding: String = "utf-8",
        mimeType: String? = null,
        historyUrl: String? = null
    ) {
        content = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
    }

    // --- Navigation Methods ---
    fun goBack() {
        webView?.goBack()
    }

    fun goForward() {
        webView?.goForward()
    }

    fun reload() {
        // Set forceReload flag for URL content type to ensure `update` block reloads
        forceReload = true
        // Trigger recomposition/update by changing the content reference slightly,
        // even if the URL is the same. Assigning the same Url object might not trigger update.
        // Or simply call webView?.reload() directly.
        webView?.reload()
        // If content is Data, reloading might mean re-setting the data.
        if (content is WebContent.Data) {
            // Re-assign to trigger update block if necessary
            content = (content as WebContent.Data).copy()
        }
    }

    fun stopLoading() {
        webView?.stopLoading()
    }

    fun clearHistory() {
        webView?.clearHistory()
    }

    fun pushConsoleMessage(message: ConsoleMessage) {
        consoleMessages = consoleMessages + message
        if (consoleMessages.size > 64) { // Limit to 64 messages
            consoleMessages = consoleMessages.takeLast(64)
        }
    }

    internal fun clearLoadSignatures() {
        lastLoadedUrlSignature = null
        lastLoadedDataSignature = null
    }
}

@Composable
fun rememberWebViewState(
    url: String = "about:blank",
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(url, additionalHttpHeaders) { // Use keys for better recomposition control
    WebViewState(
        initialContent = WebContent.Url(url, additionalHttpHeaders),
        interfaces = interfaces,
        settings = settings
    )
}

@Composable
fun rememberWebViewState(
    data: String,
    baseUrl: String? = null,
    encoding: String = "utf-8",
    mimeType: String? = null,
    historyUrl: String? = null,
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(data, baseUrl, encoding, mimeType, historyUrl) { // Use keys
    WebViewState(
        initialContent = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl),
        interfaces = interfaces,
        settings = settings
    )
}
