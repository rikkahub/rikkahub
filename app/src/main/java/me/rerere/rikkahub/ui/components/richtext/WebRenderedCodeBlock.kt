package me.rerere.rikkahub.ui.components.richtext

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

private const val MIN_PREVIEW_HEIGHT_DP = 72

private const val HEIGHT_SCRIPT = """
    (function() {
        var body = document.body;
        var doc = document.documentElement;
        var height = Math.max(
            body ? body.scrollHeight : 0,
            body ? body.offsetHeight : 0,
            doc ? doc.scrollHeight : 0,
            doc ? doc.offsetHeight : 0
        );
        return String(Math.ceil(height));
    })();
"""

@Composable
internal fun WebRenderedCodeBlock(
    target: CodeBlockRenderTarget,
    code: String,
    modifier: Modifier = Modifier,
) {
    val html = remember(target, code) {
        CodeBlockRenderResolver.buildHtmlForWebView(target, code)
    }
    var contentHeightDp by remember(html) { mutableIntStateOf(180) }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        encoding = "utf-8",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = target.normalizedLanguage,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        WebView(
            state = webViewState,
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeightDp.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP).dp),
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
            },
            onUpdated = { webView ->
                webView.evaluateJavascript(HEIGHT_SCRIPT) { result ->
                    val nextHeight = result
                        ?.trim()
                        ?.trim('"')
                        ?.toFloatOrNull()
                        ?.toInt()
                        ?.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP)
                        ?: return@evaluateJavascript
                    if (nextHeight != contentHeightDp) {
                        contentHeightDp = nextHeight
                    }
                }
            }
        )
    }
}
