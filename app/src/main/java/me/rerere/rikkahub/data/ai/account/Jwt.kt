package me.rerere.rikkahub.data.ai.account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.stringOrNull
import me.rerere.oauth.OAuthHttpClient
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Base64

/** 只解码 JWT 的 payload，不校验签名：token 直接来自授权服务器的 HTTPS 响应 */
internal fun decodeJwtPayload(jwt: String): JsonObject? {
    val parts = jwt.split('.')
    if (parts.size != 3) return null
    return runCatching {
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        JsonInstant.parseToJsonElement(payload).jsonObject
    }.getOrNull()
}

internal fun JsonObject.claimString(key: String): String? = stringOrNull(key)?.takeIf { it.isNotBlank() }

internal fun JsonObject.claimLong(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

/** access token 的过期时间（毫秒时间戳）：优先用响应的 expires_in，其次是 JWT 的 exp，都没有时为 0（未知） */
internal fun OAuthHttpClient.TokenResponse.expiresAtMillis(accessClaims: JsonObject?, now: Long): Long =
    expiresIn?.takeIf { it > 0 }?.let { now + it * 1000 }
        ?: accessClaims?.claimLong("exp")?.let { it * 1000 }
        ?: 0L
