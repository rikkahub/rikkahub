package me.rerere.rikkahub.data.ai.account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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

internal fun JsonObject.claimString(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.claimLong(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
