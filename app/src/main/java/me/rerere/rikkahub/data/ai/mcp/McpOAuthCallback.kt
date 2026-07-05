package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** OAuth 授权回调的 redirect_uri，需与 AndroidManifest 中 McpOAuthCallbackActivity 的 intent-filter 保持一致。 */
const val MCP_OAUTH_REDIRECT_URI = "rikkahub://mcp-oauth-callback"

/** 使用 Chrome Custom Tabs 打开授权 URL。 */
fun launchOAuthAuthorization(context: Context, authorizationUrl: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}
