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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "McpOAuthDiscovery"

/** MCP 授权规范特有的资源服务器与授权服务器元数据发现。 */
internal class McpOAuthDiscoveryClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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

    /**
     * 优先根据 MCP Server 401 响应中的 resource_metadata 定位，退回 RFC 9728
     * well-known 路径。
     */
    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                probeResourceMetadataUrl(serverUrl)?.let { add(it) }
                addAll(wellKnownProtectedResourceUrls(serverUrl))
            }.distinct()
            for (url in candidates) {
                val metadata = runCatching { getJson<ProtectedResourceMetadata>(url) }.getOrNull()
                if (metadata != null && metadata.authorizationServers.isNotEmpty()) {
                    Log.i(TAG, "Protected resource metadata: $url -> ${metadata.authorizationServers}")
                    return@withContext metadata
                }
            }
            error("无法发现受保护资源元数据 (protected resource metadata)")
        }

    /** 依次尝试 RFC 8414 与 OIDC Discovery 的 well-known 路径。 */
    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata =
        withContext(Dispatchers.IO) {
            for (url in wellKnownAuthorizationServerUrls(issuer)) {
                val metadata = runCatching { getJson<AuthorizationServerMetadata>(url) }.getOrNull()
                if (metadata?.authorizationEndpoint != null && metadata.tokenEndpoint != null) {
                    Log.i(TAG, "Authorization server metadata: $url")
                    return@withContext metadata
                }
            }
            error("无法发现授权服务器元数据 (authorization server metadata): $issuer")
        }

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
                RESOURCE_METADATA_REGEX.find(header)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    private fun wellKnownProtectedResourceUrls(serverUrl: String): List<String> {
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

    private fun wellKnownAuthorizationServerUrls(issuer: String): List<String> {
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

    companion object {
        private val RESOURCE_METADATA_REGEX = Regex("resource_metadata=\"([^\"]+)\"")

        /** RFC 8707 与 MCP 规范使用的 canonical resource URI。 */
        fun canonicalResource(serverUrl: String): String {
            val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
            return url.newBuilder().fragment(null).build().toString()
        }
    }
}
