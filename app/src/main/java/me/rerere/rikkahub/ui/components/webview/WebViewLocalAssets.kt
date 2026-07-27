package me.rerere.rikkahub.ui.components.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://rikkahub.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://rikkahub.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

private const val ASSET_HOST = "rikkahub.local"
private const val ASSET_PATH_PREFIX = "/assets/"

internal object WebViewLocalAssets {
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!uri.host.equals(ASSET_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (!path.startsWith(ASSET_PATH_PREFIX)) return null

        val assetPath = path.removePrefix(ASSET_PATH_PREFIX)
        if (assetPath.isEmpty() || assetPath.contains("..")) return null

        val mimeType = mimeTypeOf(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                context.assets.open(assetPath)
            )
        }.getOrNull()
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
