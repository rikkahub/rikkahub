package me.rerere.ai.provider.providers.vertex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 使用服务账号（email + private key PEM）换取 Google OAuth2 Access Token。
 * 构造时传入 OkHttpClient；调用时传 email、私钥 PEM 与 scopes。
 */
class ServiceAccountTokenProvider(
    private val http: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Token cache to avoid frequent token requests
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    @Serializable
    private data class CachedToken(
        val token: String,
        val expiresAt: Long // Unix timestamp in seconds
    )

    /**
     * Generate cache key based on service account email, scopes and private key.
     * The private key is included as a SHA-256 hash so that a key rotation (same
     * email/scopes, new key) does not reuse a token signed by the old/revoked key,
     * while never retaining the raw secret as a map key.
     */
    private fun generateCacheKey(
        serviceAccountEmail: String,
        scopes: List<String>,
        privateKeyPem: String
    ): String {
        val keyHash = sha256Hex(privateKeyPem.toByteArray(Charsets.UTF_8))
        return "$serviceAccountEmail:${scopes.sorted().joinToString(",")}:$keyHash"
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if cached token is still valid (not expired with 5 minutes buffer)
     */
    private fun isCachedTokenValid(cachedToken: CachedToken): Boolean {
        val now = Instant.now().epochSecond
        val bufferSeconds = 300 // 5 minutes buffer before actual expiration
        return cachedToken.expiresAt > (now + bufferSeconds)
    }

    /**
     * @param serviceAccountEmail  形如 xxx@project-id.iam.gserviceaccount.com
     * @param privateKeyPem        服务账号 JSON 中的 private_key 字段（PKCS#8 PEM, 含 -----BEGIN PRIVATE KEY-----）
     * @param scopes               OAuth scopes，默认 cloud-platform；多个 scope 用 List 传入
     * @return                     access token 字符串
     */
    suspend fun fetchAccessToken(
        serviceAccountEmail: String,
        privateKeyPem: String,
        scopes: List<String> = listOf("https://www.googleapis.com/auth/cloud-platform")
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(serviceAccountEmail, scopes, privateKeyPem)

        // Check cache first
        tokenCache[cacheKey]?.let { cachedToken ->
            if (isCachedTokenValid(cachedToken)) {
                return@withContext cachedToken.token
            }
        }
        val now = Instant.now().epochSecond
        val exp = now + 3600 // 最长 1h

        val headerJson = """{"alg":"RS256","typ":"JWT"}"""
        val claimJson = """{
          "iss":"$serviceAccountEmail",
          "scope":"${scopes.joinToString(" ")}",
          "aud":"https://oauth2.googleapis.com/token",
          "iat":$now,
          "exp":$exp
        }""".trimIndent()

        val headerB64 = base64UrlNoPad(headerJson.toByteArray(Charsets.UTF_8))
        val claimB64 = base64UrlNoPad(claimJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimB64"

        val privateKey = parsePkcs8PrivateKey(privateKeyPem)
        val signature = signRs256(signingInput.toByteArray(Charsets.UTF_8), privateKey)
        val assertion = "$signingInput.${base64UrlNoPad(signature)}"

        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()

        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(form)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body.string()
                throw IllegalStateException("Token endpoint ${resp.code}: $body")
            }
            val body = resp.body.string()
            val tokenResp = json.decodeFromString(TokenResponse.serializer(), body)
            val accessToken = tokenResp.accessToken ?: error("No access_token in response")

            // Cache the token with expiration time
            val expiresIn = tokenResp.expiresIn ?: 3600 // Default 1 hour if not provided
            val expiresAt = now + expiresIn
            tokenCache[cacheKey] = CachedToken(accessToken, expiresAt)

            accessToken
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("token_type")
        val tokenType: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null
    )

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parsePkcs8PrivateKey(pem: String): PrivateKey {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(normalized)
        val keySpec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun signRs256(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
