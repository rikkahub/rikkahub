package me.rerere.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.dokar.sonner.ToastType
import com.google.common.cache.CacheBuilder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.toCssHex

private val mermaidHeightCache = CacheBuilder.newBuilder()
    .maximumSize(100)
    .build<String, Int>()

/**
 * A component that renders Mermaid diagrams.
 *
 * @param code The Mermaid diagram code
 * @param modifier The modifier to be applied to the component
 */
@Composable
fun Mermaid(
    code: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkMode = LocalDarkMode.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current

    var contentHeight by remember { mutableIntStateOf(mermaidHeightCache.getIfPresent(code) ?: 150) }
    val height = with(density) {
        contentHeight.toDp()
    }
    val jsInterface = remember {
        MermaidInterface(
            onHeightChanged = { height ->
                // 需要乘以density
                // https://stackoverflow.com/questions/43394498/how-to-get-the-full-height-of-in-android-webview
                contentHeight = (height * density.density).toInt()
                mermaidHeightCache.put(code, contentHeight)
            },
            onExportImage = { base64Image ->
                runCatching {
                    activity?.let {
                        // 解码Base64图像并保存
                        try {
                            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                            val bitmap =
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            context.exportImage(
                                it,
                                bitmap,
                                "mermaid_${System.currentTimeMillis()}.png"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    toaster.show(
                        context.getString(R.string.mermaid_export_success),
                        type = ToastType.Success
                    )
                }.onFailure {
                    it.printStackTrace()
                    toaster.show(
                        context.getString(R.string.mermaid_export_failed),
                        type = ToastType.Error
                    )
                }
            }
        )
    }

    val html = remember(code, colorScheme) {
        buildMermaidHtml(
            code = code,
            theme = if (darkMode) MermaidTheme.DARK else MermaidTheme.DEFAULT,
            colorScheme = colorScheme,
        )
    }

    val webViewState = rememberWebViewState(
        data = html,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
        }
    )

    var preview by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
    ) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .animateContentSize()
                .height(height),
            onUpdated = {
                it.evaluateJavascript("calculateAndSendHeight();", null)
            }
        )

        // 导出图片按钮
        if (activity != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        preview = true
                    },
                ) {
                    Icon(
                        Lucide.Eye,
                        contentDescription = "Prewview"
                    )
                }
                IconButton(
                    onClick = {
                        webViewState.webView?.evaluateJavascript(
                            "exportSvgToPng();",
                            null
                        )
                    },
                ) {
                    Icon(
                        Lucide.Download,
                        contentDescription = stringResource(R.string.mermaid_export)
                    )
                }
            }
        }
    }

    if (preview) {
        ModalBottomSheet(
            onDismissRequest = {
                preview = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = false,
            dragHandle = {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = {
                            preview = false
                        }
                    ) {
                        Icon(
                            Lucide.X,
                            contentDescription = "Close"
                        )
                    }
                }
                WebView(
                    state = rememberWebViewState(
                        data = html,
                        mimeType = "text/html",
                        encoding = "UTF-8",
                        interfaces = mapOf(
                            "AndroidInterface" to jsInterface
                        ),
                        settings = {
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

/**
 * JavaScript interface to receive height updates and handle image export from the WebView
 */
private class MermaidInterface(
    private val onHeightChanged: (Int) -> Unit,
    private val onExportImage: (String) -> Unit
) {
    @JavascriptInterface
    fun updateHeight(height: Int) {
        onHeightChanged(height)
    }

    @JavascriptInterface
    fun exportImage(base64Image: String) {
        onExportImage(base64Image)
    }
}

/**
 * Builds HTML with Mermaid JS to render the diagram
 */
private fun buildMermaidHtml(
    code: String,
    theme: MermaidTheme,
    colorScheme: ColorScheme,
): String {
    // 将 ColorScheme 颜色转为 HEX 字符串
    val primaryColor = colorScheme.primaryContainer.toCssHex()
    val secondaryColor = colorScheme.secondaryContainer.toCssHex()
    val tertiaryColor = colorScheme.tertiaryContainer.toCssHex()
    val background = colorScheme.background.toCssHex()
    val surface = colorScheme.surface.toCssHex()
    val onPrimary = colorScheme.onPrimaryContainer.toCssHex()
    val onSecondary = colorScheme.onSecondaryContainer.toCssHex()
    val onTertiary = colorScheme.onTertiaryContainer.toCssHex()
    val onBackground = colorScheme.onBackground.toCssHex()
    val errorColor = colorScheme.error.toCssHex()
    val onErrorColor = colorScheme.onError.toCssHex()

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes, maximum-scale=5.0">
            <title>Mermaid Diagram</title>
            <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
            <style>
                body {
                    margin: 0;
                    padding: 0;
                    background-color: transparent;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: auto;
                    background-color: ${background};
                }
                .mermaid {
                    width: 100%;
                    padding: 8px;
                }
            </style>
        </head>
        <body>
            <pre class="mermaid">
                ${code.escapeHtml()}
            </pre>
            <script>
              mermaid.initialize({
                    startOnLoad: false,
                    theme: '${theme.value}',
                    themeVariables: {
                        primaryColor: '${primaryColor}',
                        primaryTextColor: '${onPrimary}',
                        primaryBorderColor: '${primaryColor}',

                        secondaryColor: '${secondaryColor}',
                        secondaryTextColor: '${onSecondary}',
                        secondaryBorderColor: '${secondaryColor}',

                        tertiaryColor: '${tertiaryColor}',
                        tertiaryTextColor: '${onTertiary}',
                        tertiaryBorderColor: '${tertiaryColor}',

                        background: '${background}',
                        mainBkg: '${primaryColor}',
                        secondBkg: '${secondaryColor}',

                        lineColor: '${onBackground}',
                        textColor: '${onBackground}',

                        nodeBkg: '${surface}',
                        nodeBorder: '${primaryColor}',
                        clusterBkg: '${surface}',
                        clusterBorder: '${primaryColor}',

                        // 序列图变量
                        actorBorder: '${primaryColor}',
                        actorBkg: '${surface}',
                        actorTextColor: '${onBackground}',
                        actorLineColor: '${primaryColor}',

                        // 甘特图变量
                        taskBorderColor: '${primaryColor}',
                        taskBkgColor: '${primaryColor}',
                        taskTextLightColor: '${onPrimary}',
                        taskTextDarkColor: '${onBackground}',

                        // 状态图变量
                        labelColor: '${onBackground}',
                        errorBkgColor: '${errorColor}',
                        errorTextColor: '${onErrorColor}'
                    }
              });

              function calculateAndSendHeight() {
                    // 获取实际内容高度，考虑缩放因素
                    const contentElement = document.querySelector('.mermaid');
                    const contentBox = contentElement.getBoundingClientRect();
                    // 添加内边距和一点额外空间以确保完整显示
                    const height = Math.ceil(contentBox.height) + 20;

                    // 处理移动设备的初始缩放
                    const visualViewportScale = window.visualViewport ? window.visualViewport.scale : 1;
                    console.warn('visualViewportScale', visualViewportScale)
                    const adjustedHeight = Math.ceil(height * visualViewportScale);

                    AndroidInterface.updateHeight(adjustedHeight);
              }

              mermaid.run({
                    querySelector: '.mermaid'
              }).catch((err) => {
                 console.error(err);
              }).then(() => {
                calculateAndSendHeight();
              });

              // 监听窗口大小变化以重新计算高度
              window.addEventListener('resize', calculateAndSendHeight);

              // 导出SVG为PNG图像
              window.exportSvgToPng = function() {
                try {
                    const svgElement = document.querySelector('.mermaid svg');
                    if (!svgElement) {
                        console.error('No SVG element found');
                        AndroidInterface.exportImage(''); // Notify error or send empty
                        return;
                    }

                    // Create a temporary canvas
                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');

                    // Get SVG's dimensions
                    const svgRect = svgElement.getBoundingClientRect();
                    const width = svgRect.width;
                    const height = svgRect.height;

                    // Set canvas dimensions with scaling for better resolution
                    const scaleFactor = window.devicePixelRatio * 2; // Increase resolution
                    canvas.width = width * scaleFactor;
                    canvas.height = height * scaleFactor;

                    // Serialize SVG to XML
                    const svgXml = new XMLSerializer().serializeToString(svgElement);
                    const svgBase64 = btoa(unescape(encodeURIComponent(svgXml))); // Properly encode to base64

                    const img = new Image();
                    img.onload = function() {
                        // Set background color (optional, matches HTML background)
                        ctx.fillStyle = '${background}';
                        ctx.fillRect(0, 0, canvas.width, canvas.height);

                        // Draw the SVG image onto the canvas
                        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

                        // Draw watermark
                        ctx.font = '14px Arial';
                        ctx.fillStyle = '${onBackground}';
                        ctx.fillText('rikka-ai.com', 20, canvas.height - 10);

                        // Get PNG image as base64
                        const pngBase64 = canvas.toDataURL('image/png').split(',')[1];
                        AndroidInterface.exportImage(pngBase64);
                    };
                    img.onerror = function(e) {
                        console.error('Error loading SVG image:', e);
                        AndroidInterface.exportImage(''); // Notify error or send empty
                    }
                    img.src = 'data:image/svg+xml;base64,' + svgBase64;
                } catch (e) {
                    console.error('Error exporting SVG:', e);
                    AndroidInterface.exportImage(''); // Notify error or send empty
                }
              };
            </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Enum class for Mermaid diagram themes
 */
enum class MermaidTheme(val value: String) {
    DEFAULT("default"),
    DARK("dark"),
}
