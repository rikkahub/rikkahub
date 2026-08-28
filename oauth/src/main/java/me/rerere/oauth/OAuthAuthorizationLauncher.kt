package me.rerere.oauth

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

fun interface OAuthAuthorizationLauncher {
    fun launch(context: Context, authorizationUrl: String)
}

/** 使用 Custom Tabs 打开 OAuth 授权页面。 */
object CustomTabsOAuthAuthorizationLauncher : OAuthAuthorizationLauncher {
    override fun launch(context: Context, authorizationUrl: String) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, authorizationUrl.toUri())
    }
}
