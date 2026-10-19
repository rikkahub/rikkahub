package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth 2.x 的通用 HTTP、PKCE、授权码和刷新令牌客户端。 */
class OAuthHttpClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** RFC 7591 动态客户端注册请求。 */
    @Serializable
    data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> =
            listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        val scope: String? = null,
    )

    /** 令牌端点请求体的编码方式。标准 OAuth 用表单，部分厂商（如 ChatGPT 的刷新）只认 JSON。 */
    enum class TokenEncoding { FORM, JSON }

    data class Pkce(val verifier: String, val challenge: String)

    /** RFC 8628 设备授权请求。 */
    data class DeviceAuthorizationRequest(
        val deviceAuthorizationEndpoint: String,
        val clientId: String,
        val scope: String? = null,
    )

    /** 设备授权响应：让用户打开 [verificationUri] 输入 [userCode]，同时用 [deviceCode] 轮询令牌端点。 */
    @Serializable
    data class DeviceAuthorization(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        // 已带上 user_code 的地址，打开后不用手动输入
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 600,
        val interval: Long = 5,
    )

    data class DeviceTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val deviceCode: String,
    )

    /** 设备授权没有完成：用户拒绝、设备码过期或令牌端点返回了其他错误。 */
    class DeviceAuthorizationException(val error: String, message: String) : IOException(message)

    data class AuthorizationRequest(
        val authorizationEndpoint: String,
        val clientId: String,
        val redirectUri: String,
        val pkce: Pkce,
        val state: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
    )

    data class AuthorizationCodeTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
    )

    data class RefreshTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val refreshToken: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
        val encoding: TokenEncoding = TokenEncoding.FORM,
    )

    suspend fun registerClient(
        registrationEndpoint: String,
        request: ClientRegistrationRequest,
    ): ClientRegistrationResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ClientRegistrationRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = execute(httpRequest)
        json.decodeFromString(ClientRegistrationResponse.serializer(), text)
    }

    fun generatePkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(digest))
    }

    fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    fun buildAuthorizationUrl(request: AuthorizationRequest): String {
        val base = request.authorizationEndpoint.toHttpUrlOrNull()
            ?: error("非法的授权端点: ${request.authorizationEndpoint}")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", request.clientId)
            .addQueryParameter("redirect_uri", request.redirectUri)
            .addQueryParameter("code_challenge", request.pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", request.state)
            .apply {
                if (!request.scope.isNullOrBlank()) addQueryParameter("scope", request.scope)
                request.resources.forEach { addQueryParameter("resource", it) }
                request.additionalParameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }
            .build()
            .toString()
    }

    suspend fun exchangeAuthorizationCode(
        request: AuthorizationCodeTokenRequest,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", request.code)
            .add("redirect_uri", request.redirectUri)
            .add("client_id", request.clientId)
            .add("code_verifier", request.codeVerifier)
            .apply {
                if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                request.resources.forEach { add("resource", it) }
                request.additionalParameters.forEach { (name, value) -> add(name, value) }
            }
            .build()
        postToken(request.tokenEndpoint, form)
    }

    suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse =
        withContext(Dispatchers.IO) {
            val parameters = buildList {
                add("grant_type" to "refresh_token")
                add("refresh_token" to request.refreshToken)
                add("client_id" to request.clientId)
                if (!request.clientSecret.isNullOrBlank()) add("client_secret" to request.clientSecret)
                if (!request.scope.isNullOrBlank()) add("scope" to request.scope)
                request.resources.forEach { add("resource" to it) }
                request.additionalParameters.forEach { (name, value) -> add(name to value) }
            }
            val body = when (request.encoding) {
                TokenEncoding.FORM -> FormBody.Builder()
                    .apply { parameters.forEach { (name, value) -> add(name, value) } }
                    .build()

                // JSON 对象不能有重复键，多个 resource 只保留最后一个
                TokenEncoding.JSON -> buildJsonObject {
                    parameters.forEach { (name, value) -> put(name, value) }
                }.toString().toRequestBody(JSON_MEDIA_TYPE)
            }
            postToken(request.tokenEndpoint, body)
        }

    suspend fun requestDeviceAuthorization(
        request: DeviceAuthorizationRequest,
    ): DeviceAuthorization = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", request.clientId)
            .apply { if (!request.scope.isNullOrBlank()) add("scope", request.scope) }
            .build()
        val httpRequest = Request.Builder()
            .url(request.deviceAuthorizationEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        json.decodeFromString(DeviceAuthorization.serializer(), execute(httpRequest))
    }

    /**
     * 按 [DeviceAuthorization.interval] 轮询令牌端点，直到用户在浏览器里完成授权（RFC 8628 3.4、3.5）。
     *
     * 收到 `slow_down` 时间隔加 5 秒；用户拒绝、设备码过期或超过 [DeviceAuthorization.expiresIn]
     * 时抛出 [DeviceAuthorizationException]。取消协程即停止轮询。
     */
    suspend fun awaitDeviceToken(
        request: DeviceTokenRequest,
        authorization: DeviceAuthorization,
    ): TokenResponse {
        var interval = authorization.interval.coerceIn(1, 60)
        var waited = 0L
        while (waited < authorization.expiresIn) {
            delay(interval * 1000)
            waited += interval
            val form = FormBody.Builder()
                .add("grant_type", DEVICE_CODE_GRANT_TYPE)
                .add("device_code", request.deviceCode)
                .add("client_id", request.clientId)
                .build()
            val httpRequest = Request.Builder()
                .url(request.tokenEndpoint)
                .header("Accept", "application/json")
                .post(form)
                .build()
            // 响应体在回调所在的调度器上读，调用方可能在主线程，读网络放到 IO
            val (code, body) = withContext(Dispatchers.IO) {
                executeRaw(httpRequest).use { it.code to it.body.string() }
            }
            if (code in 200..299) return json.decodeFromString(TokenResponse.serializer(), body)
            val error = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            when (error) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                "access_denied" -> throw DeviceAuthorizationException(error, "Authorization was denied")
                "expired_token" -> throw DeviceAuthorizationException(error, "Device code expired")
                else -> throw DeviceAuthorizationException(
                    error ?: "http_$code",
                    "HTTP $code for ${request.tokenEndpoint}: ${body.take(300)}",
                )
            }
        }
        throw DeviceAuthorizationException("expired_token", "Device code expired")
    }

    private suspend fun postToken(tokenEndpoint: String, body: RequestBody): TokenResponse {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val text = execute(request)
        return json.decodeFromString(TokenResponse.serializer(), text)
    }

    private suspend fun execute(request: Request): String {
        executeRaw(request).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
            }
            return body
        }
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
