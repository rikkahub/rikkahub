package me.rerere.rikkahub.ui.components.richtext

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowExpandDiagonal01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.ChatComposerBridge
import me.rerere.rikkahub.data.event.ChatHistoryBridge
import me.rerere.rikkahub.ui.components.webview.WebContent
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewState
import me.rerere.rikkahub.utils.toCssHex
import org.koin.compose.koinInject

private const val MIN_PREVIEW_HEIGHT_DP = 10

private const val INITIAL_PREVIEW_HEIGHT_DP = 180

private const val EXPANDED_PREVIEW_ANIMATION_MS = 280
private const val EXPANDED_PREVIEW_BLUR_RADIUS = 96
private const val RENDERED_CODE_BLOCK_BASE_URL = "https://rikkahub.local"
private const val RENDERED_CODE_BLOCK_MIME_TYPE = "text/html"
private const val RENDERED_CODE_BLOCK_ENCODING = "utf-8"

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

private class CodeBlockChatActionBridge(
    private val composerBridge: ChatComposerBridge,
    private val historyBridge: ChatHistoryBridge,
) {
    @JavascriptInterface
    fun getDraftText(): String = composerBridge.getDraftText()

    @JavascriptInterface
    fun setDraftText(text: String?) {
        composerBridge.setDraftText(text.orEmpty())
    }

    @JavascriptInterface
    fun appendDraftText(text: String?) {
        composerBridge.appendDraftText(text.orEmpty())
    }

    @JavascriptInterface
    fun sendCurrentDraft(answer: Boolean) {
        composerBridge.sendCurrentDraft(answer)
    }

    @JavascriptInterface
    fun sendText(text: String?, answer: Boolean) {
        composerBridge.sendText(
            text = text.orEmpty(),
            answer = answer,
        )
    }

    @JavascriptInterface
    fun getHistorySnapshot(): String = historyBridge.getSnapshotJson()

    @JavascriptInterface
    fun editHistoryMessage(nodeId: String?, text: String?) {
        historyBridge.editMessage(
            nodeId = nodeId.orEmpty(),
            text = text.orEmpty(),
        )
    }

    @JavascriptInterface
    fun deleteHistoryMessage(nodeId: String?) {
        historyBridge.deleteMessage(nodeId.orEmpty())
    }

    @JavascriptInterface
    fun selectHistoryMessageNode(nodeId: String?, selectIndex: Int) {
        historyBridge.selectMessageNode(nodeId.orEmpty(), selectIndex)
    }

    @JavascriptInterface
    fun regenerateHistoryMessage(nodeId: String?, regenerateAssistantMessage: Boolean) {
        historyBridge.regenerateMessage(
            nodeId = nodeId.orEmpty(),
            regenerateAssistantMessage = regenerateAssistantMessage,
        )
    }

    @JavascriptInterface
    fun continueHistoryMessage(nodeId: String?) {
        historyBridge.continueMessage(nodeId.orEmpty())
    }
}

@Composable
private fun rememberRenderedCodeBlockWebViewState(
    initialHtml: String,
    interfaces: Map<String, Any> = emptyMap(),
): WebViewState = remember(interfaces) {
    WebViewState(
        initialContent = WebContent.Data(
            data = initialHtml,
            baseUrl = RENDERED_CODE_BLOCK_BASE_URL,
            mimeType = RENDERED_CODE_BLOCK_MIME_TYPE,
            encoding = RENDERED_CODE_BLOCK_ENCODING,
        ),
        interfaces = interfaces,
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
    )
}

private fun WebViewState.loadRenderedCodeBlockHtml(html: String) {
    val currentContent = content as? WebContent.Data
    if (
        currentContent?.data == html &&
        currentContent.baseUrl == RENDERED_CODE_BLOCK_BASE_URL &&
        currentContent.mimeType == RENDERED_CODE_BLOCK_MIME_TYPE &&
        currentContent.encoding == RENDERED_CODE_BLOCK_ENCODING
    ) {
        return
    }

    loadData(
        data = html,
        baseUrl = RENDERED_CODE_BLOCK_BASE_URL,
        mimeType = RENDERED_CODE_BLOCK_MIME_TYPE,
        encoding = RENDERED_CODE_BLOCK_ENCODING,
    )
}

@Composable
private fun ExpandedRenderedCodeBlockDialog(
    state: WebViewState,
    target: CodeBlockRenderTarget,
    onDismissed: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.14f else 0f,
        animationSpec = tween(durationMillis = EXPANDED_PREVIEW_ANIMATION_MS),
        label = "expandedPreviewScrimAlpha",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = EXPANDED_PREVIEW_ANIMATION_MS),
        label = "expandedPreviewCardAlpha",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = tween(durationMillis = EXPANDED_PREVIEW_ANIMATION_MS),
        label = "expandedPreviewCardScale",
    )
    val cardOffsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 20.dp,
        animationSpec = tween(durationMillis = EXPANDED_PREVIEW_ANIMATION_MS),
        label = "expandedPreviewCardOffsetY",
    )
    val blurRadius by animateIntAsState(
        targetValue = if (visible) EXPANDED_PREVIEW_BLUR_RADIUS else 0,
        animationSpec = tween(durationMillis = EXPANDED_PREVIEW_ANIMATION_MS),
        label = "expandedPreviewBlurRadius",
    )

    fun requestDismiss() {
        if (dismissing) return
        dismissing = true
        visible = false
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    LaunchedEffect(visible, dismissing) {
        if (dismissing && !visible) {
            kotlinx.coroutines.delay(EXPANDED_PREVIEW_ANIMATION_MS.toLong())
            onDismissed()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ConfigureExpandedPreviewDialogWindow(blurRadius = blurRadius)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 36.dp)
                    .fillMaxWidth()
                    .widthIn(max = 920.dp)
                    .fillMaxHeight(0.82f)
                    .heightIn(min = 280.dp, max = 760.dp)
                    .graphicsLayer {
                        alpha = cardAlpha
                        scaleX = cardScale
                        scaleY = cardScale
                        translationY = with(density) { cardOffsetY.toPx() }
                    },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    tonalElevation = 0.dp,
                    shadowElevation = 10.dp,
                    border = BorderStroke(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                border = BorderStroke(
                                    width = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                                ),
                            ) {
                                Text(
                                    text = target.normalizedLanguage.uppercase(),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
                        ) {
                            WebView(
                                state = state,
                                allowFocus = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 12.dp, y = (-12).dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 8.dp,
                    border = BorderStroke(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                    ),
                ) {
                    IconButton(
                        onClick = ::requestDismiss,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureExpandedPreviewDialogWindow(blurRadius: Int) {
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    SideEffect {
        dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialogWindow?.setDimAmount(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dialogWindow != null) {
            dialogWindow.setBackgroundBlurRadius(blurRadius)
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            dialogWindow.attributes = dialogWindow.attributes.apply {
                setBlurBehindRadius(blurRadius)
            }
        }
    }

    DisposableEffect(dialogWindow) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dialogWindow != null) {
                dialogWindow.setBackgroundBlurRadius(0)
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    setBlurBehindRadius(0)
                }
                dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
    }
}

@Composable
internal fun WebRenderedCodeBlock(
    target: CodeBlockRenderTarget,
    code: String,
    modifier: Modifier = Modifier,
) {
    val composerBridge: ChatComposerBridge = koinInject()
    val historyBridge: ChatHistoryBridge = koinInject()
    val renderSignature = remember(target, code) {
        "${target.normalizedLanguage}:${target.renderType}:${code.hashCode()}"
    }
    val backgroundColor = MaterialTheme.colorScheme.surface.toCssHex()
    val textColor = MaterialTheme.colorScheme.onSurface.toCssHex()
    val inlineHtml = remember(target, code, backgroundColor, textColor) {
        CodeBlockRenderResolver.buildHtmlForWebView(
            target, code,
            backgroundColor = backgroundColor,
            textColor = textColor,
            scrollMode = CodeBlockRenderScrollMode.AUTO_HEIGHT,
        )
    }
    var expandedHtmlCache by remember(target, code, backgroundColor, textColor) { mutableStateOf<String?>(null) }
    var contentHeightDp by remember(renderSignature) { mutableIntStateOf(INITIAL_PREVIEW_HEIGHT_DP) }
    var showExpandedPreview by remember(renderSignature) { mutableStateOf(false) }
    val expandedPreviewVisible = rememberUpdatedState(showExpandedPreview)
    val renderBridge = remember(renderSignature) {
        CodeBlockRenderBridge { nextHeight ->
            if (!expandedPreviewVisible.value && nextHeight != contentHeightDp) {
                contentHeightDp = nextHeight
            }
        }
    }
    val chatActionBridge = remember(composerBridge, historyBridge) {
        CodeBlockChatActionBridge(
            composerBridge = composerBridge,
            historyBridge = historyBridge,
        )
    }
    val webViewInterfaces = remember(renderBridge, chatActionBridge) {
        mapOf(
            CODE_BLOCK_HEIGHT_BRIDGE_NAME to renderBridge,
            CODE_BLOCK_ACTION_BRIDGE_NAME to chatActionBridge,
        )
    }
    val webViewState = rememberRenderedCodeBlockWebViewState(
        initialHtml = inlineHtml,
        interfaces = webViewInterfaces,
    )

    fun resolveExpandedHtml(): String {
        val cachedHtml = expandedHtmlCache
        if (cachedHtml != null) return cachedHtml
        return CodeBlockRenderResolver.buildHtmlForWebView(
            target = target,
            code = code,
            backgroundColor = backgroundColor,
            textColor = textColor,
            scrollMode = CodeBlockRenderScrollMode.SCROLLABLE,
        ).also { expandedHtmlCache = it }
    }

    val animatedHeight by animateDpAsState(
        targetValue = contentHeightDp.coerceAtLeast(MIN_PREVIEW_HEIGHT_DP).dp,
        animationSpec = tween(durationMillis = 300),
        label = "codeBlockHeight",
    )
    val activeHtml = if (showExpandedPreview) resolveExpandedHtml() else inlineHtml

    LaunchedEffect(activeHtml) {
        webViewState.loadRenderedCodeBlockHtml(activeHtml)
    }

    key(renderSignature) {
        Box(
            modifier = Modifier
                .then(modifier)
                .clip(RoundedCornerShape(12.dp))
                .fillMaxWidth()
                .height(animatedHeight),
        ) {
            if (!showExpandedPreview) {
                WebView(
                    state = webViewState,
                    allowFocus = false,
                    modifier = Modifier.fillMaxSize(),
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

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                    shadowElevation = 10.dp,
                ) {
                    IconButton(
                        onClick = {
                            showExpandedPreview = true
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(HugeIcons.ArrowExpandDiagonal01, contentDescription = stringResource(R.string.code_block_expand))
                    }
                }
            }
        }
    }

    if (showExpandedPreview) {
        ExpandedRenderedCodeBlockDialog(
            state = webViewState,
            target = target,
            onDismissed = {
                showExpandedPreview = false
            },
        )
    }
}
