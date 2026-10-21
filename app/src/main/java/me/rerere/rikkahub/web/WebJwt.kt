package me.rerere.rikkahub.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Web API 用的 HS256 JWT 签发和校验，只覆盖 Web 端登录需要的声明（iss / aud / sub / iat / exp）。
 * 格式与之前的 auth0 java-jwt 一致，升级前签发的 token 仍然有效。
 */
internal object WebJwt {
    private const val ALGORITHM = "HS256"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val header = encode(
        buildJsonObject {
            put("alg", ALGORITHM)
            put("typ", "JWT")
        }.toString().toByteArray(Charsets.UTF_8)
    )

    fun sign(
        secret: String,
        issuer: String,
        audience: String,
        subject: String,
        issuedAtMillis: Long,
        expiresAtMillis: Long,
    ): String {
        val payload = buildJsonObject {
            put("iss", issuer)
            put("aud", audience)
            put("sub", subject)
            put("iat", issuedAtMillis / 1000)
            put("exp", expiresAtMillis / 1000)
        }
        val content = "$header.${encode(payload.toString().toByteArray(Charsets.UTF_8))}"
        return "$content.${encode(hmac(secret, content))}"
    }

    fun verify(
        token: String,
        secret: String,
        issuer: String,
        audience: String,
        subject: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        val (headerPart, payloadPart, signaturePart) = parts

        val signature = decode(signaturePart) ?: return false
        if (!MessageDigest.isEqual(signature, hmac(secret, "$headerPart.$payloadPart"))) return false

        val header = decodeJson(headerPart) ?: return false
        if (header.string("alg") != ALGORITHM) return false

        val payload = decodeJson(payloadPart) ?: return false
        if (payload.string("iss") != issuer) return false
        if (payload.string("sub") != subject) return false
        val audiences = when (val aud = payload["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (audience !in audiences) return false

        val nowSeconds = nowMillis / 1000
        val expiresAt = payload.long("exp") ?: return false
        if (nowSeconds > expiresAt) return false
        payload.long("iat")?.let { if (nowSeconds < it) return false }
        payload.long("nbf")?.let { if (nowSeconds < it) return false }
        return true
    }

    private fun hmac(secret: String, content: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(content.toByteArray(Charsets.UTF_8))
    }

    private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    private fun decode(part: String): ByteArray? = runCatching { decoder.decode(part) }.getOrNull()

    private fun decodeJson(part: String): JsonObject? = runCatching {
        Json.parseToJsonElement(decode(part)!!.toString(Charsets.UTF_8)).jsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
