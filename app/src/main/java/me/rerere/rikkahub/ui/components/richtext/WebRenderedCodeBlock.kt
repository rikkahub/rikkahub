package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.utils.toCssHex

private const val MIN_PREVIEW_HEIGHT_DP = 10

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

@Composable
internal fun WebRenderedCodeBlock(
    target: CodeBlockRenderTarget,
    code: String,
    modifier: Modifier = Modifier,
) {
    val renderSignature = remember(target, code) {
        "${target.normalizedLanguage}:${target.renderType}:${code.hashCode()}"
    }
    val backgroundColor = MaterialTheme.colorScheme.surface.toCssHex()
    val textColor = MaterialTheme.colorScheme.onSurface.toCssHex()
    val html = remember(target, code, backgroundColor, textColor) {
        CodeBlockRenderResolver.buildHtmlForWebView(
            target, code,
            backgroundColor = backgroundColor,
            textColor = textColor,
        )
    }
    var contentHeightDp by remember(renderSignature) { mutableIntStateOf(INITIAL_PREVIEW_HEIGHT_DP) }
    val renderBridge = remember(renderSignature) {
        CodeBlockRenderBridge { nextHeight ->
            if (nextHeight != contentHeightDp) {
                contentHeightDp = nextHeight
            }
        }
    }

    val animatedHeight by animateDpAsState(
        targetValue = contentHeightDp.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP).dp,
        animationSpec = tween(durationMillis = 300),
        label = "codeBlockHeight",
    )

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

    key(renderSignature) {
        WebView(
            state = webViewState,
            allowFocus = false,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .fillMaxWidth()
                .height(animatedHeight),
            onCreated = { webView ->
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
            }
        )
    }
}
