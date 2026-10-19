package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient

/**
 * Grok（SuperGrok）账号登录的协议参数，与 Grok CLI 一致，参照 can1357/oh-my-pi 的 xai-oauth。
 *
 * xAI 给这个 client_id 开放的是设备码登录（RFC 8628），不需要本地回调。
 */
internal object GrokAuth {
    private const val ISSUER = "https://auth.x.ai"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val TOKEN_ENDPOINT = "$ISSUER/oauth2/token"
    const val USERINFO_ENDPOINT = "$ISSUER/oauth2/userinfo"
    private const val SCOPE = "openid profile email offline_access grok-cli:access api:access"

    fun deviceAuthorizationRequest() = OAuthHttpClient.DeviceAuthorizationRequest(
        deviceAuthorizationEndpoint = "$ISSUER/oauth2/device/code",
        clientId = CLIENT_ID,
        scope = SCOPE,
    )

    fun deviceTokenRequest(deviceCode: String) = OAuthHttpClient.DeviceTokenRequest(
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = CLIENT_ID,
        deviceCode = deviceCode,
    )

    fun refreshRequest(refreshToken: String) = OAuthHttpClient.RefreshTokenRequest(
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = CLIENT_ID,
        refreshToken = refreshToken,
    )

    /**
     * 把令牌响应转成凭据。账号 ID 取 token 的 `sub`；刷新响应缺的字段沿用 [previous] 里的值。
     */
    fun toCredential(
        token: OAuthHttpClient.TokenResponse,
        previous: OAuthCredential?,
        now: Long = System.currentTimeMillis(),
    ): OAuthCredential {
        val accessClaims = decodeJwtPayload(token.accessToken)
        val idClaims = token.idToken?.let(::decodeJwtPayload)
        return OAuthCredential(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = token.expiresAtMillis(accessClaims, now),
            accountId = accessClaims?.claimString("sub") ?: idClaims?.claimString("sub") ?: previous?.accountId,
            email = idClaims?.claimString("email") ?: accessClaims?.claimString("email") ?: previous?.email,
            plan = previous?.plan,
        )
    }
}
