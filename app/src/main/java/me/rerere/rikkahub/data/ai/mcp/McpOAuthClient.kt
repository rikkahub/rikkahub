package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64

private const val TAG = "McpOAuthClient"

/**
 * MCP OAuth 2.1 授权客户端，实现规范 (2025-11-25 basic/authorization) 所需的各环节：
 *
 * - RFC 9728 受保护资源元数据发现
 * - RFC 8414 / OIDC 授权服务器元数据发现
 * - RFC 7591 动态客户端注册 (DCR)
 * - 带 PKCE (S256) 的授权码流程
 * - RFC 8707 Resource Indicators
 * - 令牌刷新
 *
 * SDK (kotlin-sdk 0.13.0) 本身不提供 OAuth 支持，因此该逻辑完全独立实现，
 * 最终仅通过 transport 的 requestBuilder 注入 `Authorization: Bearer` 请求头。
 */
class McpOAuthClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class ProtectedResourceMetadata(
        val resource: String? = null,
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    data class AuthorizationServerMetadata(
        val issuer: String? = null,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
        @SerialName("token_endpoint") val tokenEndpoint: String? = null,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
        @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    )

    @Serializable
    private data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
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

    /** PKCE 参数对 (code_verifier / code_challenge)。 */
    data class Pkce(val verifier: String, val challenge: String)

    // ---------------------------------------------------------------------
    // 元数据发现
    // ---------------------------------------------------------------------

    /**
     * 发现受保护资源元数据 (RFC 9728)。优先根据服务器 401 响应中的
     * `WWW-Authenticate: resource_metadata="..."` 定位，退回到 well-known 路径。
     */
    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                probeResourceMetadataUrl(serverUrl)?.let { add(it) }
                addAll(wellKnownPrmUrls(serverUrl))
            }.distinct()
            for (url in candidates) {
                val meta = runCatching { getJson<ProtectedResourceMetadata>(url) }.getOrNull()
                if (meta != null && meta.authorizationServers.isNotEmpty()) {
                    Log.i(TAG, "discoverProtectedResource: found via $url -> ${meta.authorizationServers}")
                    return@withContext meta
                }
            }
            error("无法发现受保护资源元数据 (protected resource metadata)")
        }

    /**
     * 发现授权服务器元数据 (RFC 8414 / OIDC discovery)。依次尝试
     * oauth-authorization-server 与 openid-configuration 的多种 well-known 组合。
     */
    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata =
        withContext(Dispatchers.IO) {
            for (url in wellKnownAsUrls(issuer)) {
                val meta = runCatching { getJson<AuthorizationServerMetadata>(url) }.getOrNull()
                if (meta?.authorizationEndpoint != null && meta.tokenEndpoint != null) {
                    Log.i(TAG, "discoverAuthorizationServer: found via $url")
                    return@withContext meta
                }
            }
            error("无法发现授权服务器元数据 (authorization server metadata): $issuer")
        }

    /** 动态客户端注册 (RFC 7591)，返回 client_id (公共客户端通常无 secret)。 */
    suspend fun registerClient(
        registrationEndpoint: String,
        clientName: String,
        redirectUri: String,
        scope: String?,
    ): ClientRegistrationResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ClientRegistrationRequest.serializer(),
            ClientRegistrationRequest(
                clientName = clientName.ifBlank { "RikkaHub" },
                redirectUris = listOf(redirectUri),
                scope = scope,
            )
        )
        val request = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = execute(request)
        json.decodeFromString(ClientRegistrationResponse.serializer(), text)
    }

    // ---------------------------------------------------------------------
    // 授权码流程
    // ---------------------------------------------------------------------

    fun generatePkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(digest))
    }

    fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    /** 拼接授权端点 URL，附带 PKCE、state 以及 RFC 8707 resource 参数。 */
    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        pkce: Pkce,
        state: String,
        scope: String?,
        resource: String,
    ): String {
        val base = authorizationEndpoint.toHttpUrlOrNull()
            ?: error("非法的授权端点: $authorizationEndpoint")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("resource", resource)
            .apply { if (!scope.isNullOrBlank()) addQueryParameter("scope", scope) }
            .build()
            .toString()
    }

    /** 用授权码换取访问令牌。 */
    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .add("resource", resource)
            .apply { if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret) }
            .build()
        postToken(tokenEndpoint, form)
    }

    /** 使用 refresh_token 刷新访问令牌。 */
    suspend fun refreshToken(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        resource: String,
        scope: String?,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("resource", resource)
            .apply {
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
                if (!scope.isNullOrBlank()) add("scope", scope)
            }
            .build()
        postToken(tokenEndpoint, form)
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

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    /** 向 MCP Server 发一次探测请求，从 401 的 WWW-Authenticate 提取 resource_metadata。 */
    private suspend fun probeResourceMetadataUrl(serverUrl: String): String? {
        val request = Request.Builder()
            .url(serverUrl)
            .header("Accept", "application/json, text/event-stream")
            .get()
            .build()
        return runCatching {
            executeRaw(request).use { response ->
                if (response.code != 401) return null
                val header = response.header("WWW-Authenticate") ?: return null
                parseResourceMetadata(header)
            }
        }.getOrNull()
    }

    private fun parseResourceMetadata(wwwAuthenticate: String): String? {
        // 例如: Bearer resource_metadata="https://host/.well-known/oauth-protected-resource", error="..."
        val regex = Regex("resource_metadata=\"([^\"]+)\"")
        return regex.find(wwwAuthenticate)?.groupValues?.getOrNull(1)
    }

    private fun wellKnownPrmUrls(serverUrl: String): List<String> {
        val url = serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-protected-resource$path")
            }
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()
    }

    private fun wellKnownAsUrls(issuer: String): List<String> {
        val url = issuer.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-authorization-server$path")
                add("$origin/.well-known/openid-configuration$path")
                add("$origin$path/.well-known/openid-configuration")
            }
            add("$origin/.well-known/oauth-authorization-server")
            add("$origin/.well-known/openid-configuration")
        }.distinct()
    }

    private fun portSuffix(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        return if (url.port == defaultPort) "" else ":${url.port}"
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val text = execute(request)
        return json.decodeFromString(text)
    }

    private suspend fun execute(request: Request): String {
        executeRaw(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
            }
            return body
        }
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun base64Url(bytes: ByteArray): String =
            Base64.UrlSafe.encode(bytes).trimEnd('=')

        /**
         * 规范化 canonical resource URI (RFC 8707 + MCP 规范)：小写 scheme/host、去掉 fragment。
         */
        fun canonicalResource(serverUrl: String): String {
            val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
            return url.newBuilder().fragment(null).build().toString()
        }
    }
}
