package me.rerere.rikkahub.data.ai.account

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient

/**
 * ChatGPT 账号登录的协议参数，与 openai/codex（codex-rs/login）保持一致。
 *
 * client_id 和回调地址是 Codex CLI 在 OpenAI 授权服务器上注册的，回调地址必须原样使用。
 */
internal object ChatGPTAuth {
    private const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN_ENDPOINT = "$ISSUER/oauth/token"
    const val CALLBACK_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val CALLBACK_HOST = "127.0.0.1"

    fun authorizationRequest(
        pkce: OAuthHttpClient.Pkce,
        state: String,
        redirectUri: String,
    ) = OAuthHttpClient.AuthorizationRequest(
        authorizationEndpoint = "$ISSUER/oauth/authorize",
        clientId = CLIENT_ID,
        redirectUri = redirectUri,
        pkce = pkce,
        state = state,
        scope = "openid profile email offline_access",
        additionalParameters = mapOf(
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs",
        ),
    )

    /** ChatGPT 的刷新接口只接受 JSON 请求体 */
    fun refreshRequest(refreshToken: String) = OAuthHttpClient.RefreshTokenRequest(
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = CLIENT_ID,
        refreshToken = refreshToken,
        encoding = OAuthHttpClient.TokenEncoding.JSON,
    )

    /**
     * 把令牌响应转成凭据。刷新响应可能不带 id_token 或 refresh_token，此时沿用 [previous] 里的值。
     */
    fun toCredential(
        token: OAuthHttpClient.TokenResponse,
        previous: OAuthCredential?,
        now: Long = System.currentTimeMillis(),
    ): OAuthCredential {
        val idClaims = token.idToken?.let(::decodeJwtPayload)
        val accessClaims = decodeJwtPayload(token.accessToken)
        val authClaims = (idClaims ?: accessClaims)?.get(AUTH_CLAIM) as? JsonObject
        return OAuthCredential(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = token.expiresAtMillis(accessClaims, now),
            accountId = authClaims?.claimString("chatgpt_account_id") ?: previous?.accountId,
            email = idClaims?.claimString("email")
                ?: (idClaims?.get(PROFILE_CLAIM) as? JsonObject)?.claimString("email")
                ?: previous?.email,
            plan = authClaims?.claimString("chatgpt_plan_type") ?: previous?.plan,
        )
    }

    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    /**
     * `chatgpt_plan_type` 的显示名，照搬 codex-rs/protocol 的 `KnownPlan::display_name`，
     * 注意 prolite 才是 "Pro"，pro 是 "Pro (More)"。不认识的值按单词首字母大写显示。
     */
    fun planDisplayName(plan: String): String = when (plan.lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "pro" -> "Pro (More)"
        "prolite" -> "Pro"
        "promax" -> "Pro (Max)"
        "team" -> "Team"
        "self_serve_business_prolite" -> "Self Serve Business ProLite"
        "self_serve_business_usage_based" -> "Self Serve Business Usage Based"
        "business" -> "Business"
        "ent26", "enterprise", "hc" -> "Enterprise"
        "enterprise_cbp_automation" -> "Enterprise (Automation)"
        "enterprise_cbp_usage_based" -> "Enterprise CBP Usage Based"
        "edu", "education" -> "Edu"
        "edu_plus" -> "Edu Plus"
        "edu_pro" -> "Edu Pro"
        else -> plan.split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
