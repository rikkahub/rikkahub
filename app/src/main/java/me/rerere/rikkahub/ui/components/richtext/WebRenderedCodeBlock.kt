package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

private const val MIN_PREVIEW_HEIGHT_DP = 72

private const val INITIAL_PREVIEW_HEIGHT_DP = 180

private class CodeBlockRenderBridge(
    private val onHeightChanged: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onContentHeight(heightText: String?) {
        val height = heightText
            ?.trim()
            ?.toFloatOrNull()
            ?.toInt()
            ?.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP)
            ?: return
        mainHandler.post {
            onHeightChanged(height)
        }
    }
}

private fun removeDuplicateSiblingWebViews(
    webView: android.webkit.WebView,
    signature: String,
) {
    val parent = webView.parent as? ViewGroup ?: return
    val duplicates = mutableListOf<android.webkit.WebView>()
    for (index in 0 until parent.childCount) {
        val child = parent.getChildAt(index)
        if (child === webView || child !is android.webkit.WebView) continue
        val childSignature = child.getTag(R.id.tag_code_block_render_signature) as? String
        if (childSignature == signature) {
            duplicates += child
        }
    }
    duplicates.forEach { duplicate ->
        parent.removeView(duplicate)
    }
}

@Composable
internal fun WebRenderedCodeBlock(
    target: CodeBlockRenderTarget,
    code: String,
    modifier: Modifier = Modifier,
) {
    val renderSignature = remember(target, code) {
        "${target.normalizedLanguage}:${target.renderType}:${code.hashCode()}"
    }
    val html = remember(target, code) {
        CodeBlockRenderResolver.buildHtmlForWebView(target, code)
    }
    var contentHeightDp by remember(renderSignature) { mutableIntStateOf(INITIAL_PREVIEW_HEIGHT_DP) }
    val renderBridge = remember(renderSignature) {
        CodeBlockRenderBridge { nextHeight ->
            if (nextHeight != contentHeightDp) {
                contentHeightDp = nextHeight
            }
        }
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        encoding = "utf-8",
        interfaces = mapOf(CODE_BLOCK_HEIGHT_BRIDGE_NAME to renderBridge),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
    )

    WebView(
        state = webViewState,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .fillMaxWidth()
            .height(contentHeightDp.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP).dp),
        onCreated = { webView ->
            webView.setTag(R.id.tag_code_block_render_signature, renderSignature)
            webView.setOnTouchListener { view, event ->
                when (event?.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        },
        onUpdated = { webView ->
            val currentSignature = webView.getTag(R.id.tag_code_block_render_signature) as? String
            if (currentSignature != renderSignature) {
                webView.setTag(R.id.tag_code_block_render_signature, renderSignature)
            }
            removeDuplicateSiblingWebViews(webView, renderSignature)
        }
    )
}
