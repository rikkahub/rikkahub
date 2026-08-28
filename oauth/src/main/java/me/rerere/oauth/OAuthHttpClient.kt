package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val scope: String? = null,
    )

    data class Pkce(val verifier: String, val challenge: String)

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
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", request.refreshToken)
                .add("client_id", request.clientId)
                .apply {
                    if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                    if (!request.scope.isNullOrBlank()) add("scope", request.scope)
                    request.resources.forEach { add("resource", it) }
                    request.additionalParameters.forEach { (name, value) -> add(name, value) }
                }
                .build()
            postToken(request.tokenEndpoint, form)
        }

    private suspend fun postToken(tokenEndpoint: String, form: FormBody): TokenResponse {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
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
    }
}
